package com.hodi.modules.buyers;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auth.OtpChallengeService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.password.PasswordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Buyer self-registration.
 *
 * <p>A buyer is an ordinary {@code users} row with the {@code BUYER} user type, no organisation, and the
 * single {@code BUYER_PORTAL} group. One identity table for all four populations means one login pipeline,
 * one password policy and one session model — see {@code User} for why a separate buyer table was rejected.
 *
 * <h2>The account is inert until a code lands</h2>
 *
 * <p>Registration creates the row and sends a code; it does not produce a session. Until the code is
 * confirmed, {@code JwtAuthenticationFilter} refuses the account even with a valid token — checked there
 * rather than only here, because the requirement is configuration and turning phone verification on has to
 * reach people who registered yesterday.
 *
 * <h2>What registration deliberately does not reveal</h2>
 *
 * <p>Registering with an address that already has an account answers exactly as success does. An endpoint
 * that says "that email is taken" is an account-enumeration oracle, and this one is unauthenticated and free
 * to call. The person who genuinely owns the address gets an email telling them somebody tried; the person
 * probing gets nothing. The cost is that a real user who forgot they had signed up waits for a confirmation
 * code that never comes, which is why that email says what to do next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerRegistrationService {

    /** The group every buyer holds. Carries exactly {@code BUYER_PORTAL_ACCESS}. */
    public static final String BUYER_GROUP_NAME = "Buyer";

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final UserProfileService userProfiles;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final PasswordService passwords;
    private final OtpChallengeService otpChallenges;
    private final ConfigurationService configs;
    private final ConsentService consent;
    private final AuditService audit;

    /** What the client needs to move to the "enter your code" screen. */
    public record RegistrationOutcome(String challengeToken, String channel, String sentToMasked,
                                      boolean verificationRequired) {}

    @Transactional
    public RegistrationOutcome register(BuyerDtos.RegisterRequest request) {
        if (!configs.getBoolean(ConfigKey.BUYER_SELF_REGISTRATION_ENABLED)) {
            throw new HodiException("Sign-ups are closed at the moment.", HttpStatus.FORBIDDEN);
        }

        String email = request.email().trim().toLowerCase();
        String phone = request.phone() == null || request.phone().isBlank()
                ? null : request.phone().trim();

        boolean emailRequired = configs.getBoolean(ConfigKey.BUYER_EMAIL_VERIFICATION_REQUIRED);
        boolean phoneRequired = configs.getBoolean(ConfigKey.BUYER_PHONE_VERIFICATION_REQUIRED);
        if (phoneRequired && phone == null) {
            throw new HodiException("A phone number is required to sign up.", HttpStatus.BAD_REQUEST);
        }

        var existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User held = existing.get();
            /*
             * An unverified registration for the same address is treated as a re-send rather than a
             * duplicate. Somebody who closed the tab before entering the code would otherwise be permanently
             * stuck: they cannot register (the address is taken) and cannot sign in (unverified), with no way
             * out that does not involve support.
             */
            if (hasBuyerProfile(held) && held.getEmailVerifiedAt() == null) {
                log.debug("Re-issuing a verification code for an unverified registration");
                var challenge = otpChallenges.issueEmailVerification(held);
                return new RegistrationOutcome(challenge.token(), challenge.channel(),
                        challenge.sentToMasked(), true);
            }
            /*
             * A real account. Answer as though registration succeeded, and issue no challenge — the client
             * shows the same "check your email" screen either way, so the response shape must not differ. The
             * token is a decoy in the sense that it is absent; the client's next step fails the same way a
             * mistyped address would.
             *
             * This branch also catches the address of somebody who is staff and not yet a buyer, and it
             * deliberately does NOT add them a buyer profile. FR073 says one person may hold both, and they
             * can: from inside their own account, authenticated (see AuthService.addBuyerProfile). Doing it
             * here would let anybody who knows a colleague's address write a profile onto their account, and
             * applying the submitted password would be worse still — a password reset with no proof of
             * anything.
             */
            log.info("Registration attempted for an address that already has an account");
            return new RegistrationOutcome(null, "EMAIL",
                    OtpChallengeService.maskEmail(email), emailRequired);
        }

        UserType buyerType = userTypes.findByCode("BUYER")
                .orElseThrow(() -> new HodiException(
                        "The BUYER user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        UserGroup buyerGroup = userGroups.findGlobalByName(BUYER_GROUP_NAME)
                .orElseThrow(() -> new HodiException(
                        "The Buyer group is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        User buyer = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .username(deriveUsername(email))
                .phone(phone)
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy("self-registration")
                .build();
        // Their own password, through the same policy as staff. A buyer's account holds their enquiries and
        // their finance applications; there is no argument for a weaker rule here.
        passwords.applyTo(buyer, request.password());
        // applyTo() clears must_change_password, which is right: they chose this password themselves.
        if (!emailRequired) buyer.setEmailVerifiedAt(OffsetDateTime.now());
        if (!phoneRequired && phone != null) {
            // Not marked verified — an unverified number is simply not required, which is a different thing
            // from being confirmed. Leaving it null keeps "has this person proved they own this number"
            // answerable.
            buyer.setPhoneVerifiedAt(null);
        }

        User saved = users.save(buyer);
        // No organisation, by definition: a buyer belongs to nobody and is scoped by their own identity.
        userProfiles.provisionFirst(saved.getId(), buyerType, buyerGroup, null, null, null, null);
        /*
         * Their opening position on being contacted, in the same transaction as the account (plan §3.8).
         *
         * Here rather than on first sign-in because the verification email is about to be sent, and a
         * transactional consent recorded after the message it authorises is a record that proves nothing.
         * The one thing they were actually asked is the property-alerts tick; everything else is recorded
         * as the refusal it is, with a source saying nobody asked.
         */
        consent.captureAtRegistration(saved.getId(),
                Boolean.TRUE.equals(request.propertyAlertsOptIn()));
        audit.record(AppConstant.AUDIT_BUYER_REGISTER, "User", saved.getId(), null,
                "self-registered as " + saved.getUsername());
        log.info("Buyer {} registered", saved.getUsername());

        if (!emailRequired) {
            return new RegistrationOutcome(null, "EMAIL", null, false);
        }
        var challenge = otpChallenges.issueEmailVerification(saved);
        return new RegistrationOutcome(challenge.token(), challenge.channel(),
                challenge.sentToMasked(), true);
    }

    /**
     * Confirms a verification code and marks the channel verified.
     *
     * <p>Returns nothing useful on purpose: verification does not produce a session. The client signs in
     * afterwards with the password the buyer chose, which means a leaked verification code alone is not a way
     * into an account.
     */
    @Transactional
    public void verify(BuyerDtos.VerifyRequest request) {
        Long userId = otpChallenges.boundUserId(request.challengeToken());
        User user = users.findById(userId)
                .orElseThrow(() -> new HodiException("That request has expired. Start again.",
                        HttpStatus.BAD_REQUEST));

        String purpose = "PHONE".equalsIgnoreCase(request.channel())
                ? OtpChallengeService.PURPOSE_PHONE_VERIFY
                : OtpChallengeService.PURPOSE_EMAIL_VERIFY;

        otpChallenges.consume(request.challengeToken(), request.code(), purpose, user);

        if (OtpChallengeService.PURPOSE_PHONE_VERIFY.equals(purpose)) {
            user.setPhoneVerifiedAt(OffsetDateTime.now());
        } else {
            user.setEmailVerifiedAt(OffsetDateTime.now());
        }
        users.save(user);
        audit.record(AppConstant.AUDIT_BUYER_VERIFY, "User", user.getId(), null, purpose);
        log.info("Buyer {} completed {}", user.getUsername(), purpose);
    }

    /**
     * Sends a fresh code.
     *
     * <p>Answers identically whether or not the address has an unverified account, for the same reason
     * registration does — otherwise this endpoint becomes the enumeration oracle that one is not.
     */
    @Transactional
    public RegistrationOutcome resend(BuyerDtos.ResendRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        var existing = users.findByEmail(email);

        if (existing.isEmpty() || !hasBuyerProfile(existing.get())
                || existing.get().getEmailVerifiedAt() != null) {
            log.debug("Resend requested for an address with nothing pending");
            return new RegistrationOutcome(null, "EMAIL",
                    OtpChallengeService.maskEmail(email), true);
        }
        var challenge = otpChallenges.issueEmailVerification(existing.get());
        return new RegistrationOutcome(challenge.token(), challenge.channel(),
                challenge.sentToMasked(), true);
    }

    /**
     * Whether this person already browses as a buyer.
     *
     * <p>A question about their profiles now, not about the person: somebody can be a seller's owner and a
     * buyer at once, and "is this a buyer account" has no single answer for them.
     */
    private boolean hasBuyerProfile(User user) {
        return profiles.findLiveForUser(user.getId()).stream().anyMatch(UserProfile::isBuyerActor);
    }

    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        if (base.isEmpty()) base = "buyer";
        if (!users.existsByUsernameIgnoreCase(base)) return base;
        for (int suffix = 2; suffix < 100000; suffix++) {
            String candidate = base + suffix;
            if (!users.existsByUsernameIgnoreCase(candidate)) return candidate;
        }
        // Practically unreachable, and a 409 is the honest answer rather than an infinite loop.
        throw new DuplicateResourceException("Could not allocate a username — please contact support.");
    }
}
