package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.password.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * An agent applying to join the platform (BRD FR160).
 *
 * <h2>Self-registration that grants nothing</h2>
 *
 * <p>This endpoint is public, and it creates a user who will eventually list property — which is a
 * materially different thing from a buyer signing up. It is safe because registering confers no ability:
 *
 * <ul>
 *   <li>the application lands {@code PENDING} and only the platform can move it;
 *   <li>the profile's KYC standing is {@code PENDING}, which the existing gate reads as "may not write a
 *       listing"; and
 *   <li>there is no organisation to list into until approval creates one.
 * </ul>
 *
 * <p>All three, rather than any one of them, because a single gate is a single thing to get wrong.
 *
 * <h2>The signature is captured here, in the same transaction</h2>
 *
 * <p>Not afterwards on a second call. An application that exists without the acceptance it was made under is
 * a row nobody can act on, and a signature without an application is evidence of nothing — so either both
 * are written or neither is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRegistrationService {

    private final AgentProfileRepository agents;
    private final UserRepository users;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final UserProfileService userProfiles;
    private final PasswordService passwords;
    private final SignatureService signatures;
    private final ConfigurationService configs;
    private final AuditService audit;

    public record RegisterAgentRequest(
            @NotBlank(message = "Your first name is required") @Size(max = 64) String firstName,
            @NotBlank(message = "Your last name is required") @Size(max = 64) String lastName,
            @NotBlank(message = "An email address is required") @Email @Size(max = 128) String email,
            @NotBlank(message = "A phone number is required") @Size(max = 32) String phone,
            @NotBlank(message = "Choose a password") String password,

            @Size(max = 64) String idNumber,
            @Size(max = 64) String licenceNumber,
            LocalDate licenceExpiresOn,
            Boolean selfEmployed,
            @Size(max = 255) String agencyName,
            String counties,
            String bio,

            /** The version of the terms the applicant was shown. Checked, not trusted. */
            @NotBlank(message = "Read and accept the terms before applying") String termsVersion,
            /** {@code DRAWN} or {@code TYPED}. */
            @NotBlank(message = "Sign before applying") String signatureKind,
            /** A {@code data:image/png;base64,…} URL, for a drawn signature. */
            String signatureImage,
            @Size(max = 160) String typedName) {}

    /** What the applicant is told, which is deliberately not very much. */
    public record RegistrationOutcome(String reference, String state, String message) {}

    @Transactional
    public RegistrationOutcome register(RegisterAgentRequest request, HttpServletRequest http) {
        if (!configs.getBoolean(ConfigKey.AGENT_SELF_REGISTRATION_ENABLED)) {
            throw new HodiException("Agent applications are closed at the moment.", HttpStatus.FORBIDDEN);
        }

        boolean selfEmployed = request.selfEmployed() == null || request.selfEmployed();
        if (!selfEmployed && (request.agencyName() == null || request.agencyName().isBlank())) {
            throw new HodiException("Name the agency you work for, or say you are self-employed.",
                    HttpStatus.BAD_REQUEST);
        }

        String email = request.email().trim().toLowerCase();
        /*
         * Told plainly, unlike the buyer path.
         *
         * Buyer registration answers identically whether or not the address is taken, because a buyer
         * account is a private fact and confirming one exists is an enumeration oracle. An agent
         * application is a business relationship with a person who will be publicly listed, they will speak
         * to the platform about it either way, and leaving them staring at a pending application that was
         * never created is worse than the disclosure.
         */
        if (users.existsByEmail(email)) {
            throw new DuplicateResourceException(
                    "An account already exists for that email address. Sign in and apply from there, or use "
                            + "a different address.");
        }

        UserType agentType = userTypes.findByCode("AGENT")
                .orElseThrow(() -> new HodiException(
                        "The AGENT user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        UserGroup agentGroup = userGroups.findGlobalByName(AgentService.AGENT_GROUP_NAME)
                .orElseThrow(() -> new HodiException(
                        "The Property Agent group is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .username(deriveUsername(email))
                .phone(request.phone().trim())
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy("agent-application")
                .build();
        passwords.applyTo(user, request.password());
        User saved = users.save(user);

        UserProfile profile = userProfiles.provisionFirst(
                saved.getId(), agentType, agentGroup, null, null, null, null);
        /*
         * PENDING, not NOT_REQUIRED.
         *
         * This is the gate. NOT_REQUIRED clears it — which is right for a platform administrator nobody ever
         * asked anything of, and exactly wrong for somebody who has just asked to be allowed to sell houses.
         */
        profile.setKycStatus(AppConstant.KYC_PENDING);

        SignatureArtifact signature = signatures.capture(
                saved.getId(), profile.getId(), AgentState.SIGNATURE_AGENT_TERMS,
                request.termsVersion(), request.signatureKind(),
                request.signatureImage(), request.typedName(), http);

        AgentProfile agent = agents.save(AgentProfile.builder()
                .reference(RrnGenerator.generate("AP"))
                .userId(saved.getId())
                .profileId(profile.getId())
                .fullName(saved.getFirstName() + " " + saved.getLastName())
                .email(email)
                .phone(saved.getPhone())
                .selfEmployed(selfEmployed)
                .agencyName(blankToNull(request.agencyName()))
                .idNumber(blankToNull(request.idNumber()))
                .licenceNumber(blankToNull(request.licenceNumber()))
                .licenceExpiresOn(request.licenceExpiresOn())
                .counties(blankToNull(request.counties()))
                .bio(blankToNull(request.bio()))
                .state(AgentState.PENDING)
                .signatureId(signature.getId())
                .createdBy("agent-application")
                .build());

        audit.record(AppConstant.AUDIT_AGENT_REGISTER, "AgentProfile", agent.getId(), null,
                "applied as " + agent.tradingName() + " (signature " + signature.getReference() + ")");
        log.info("Agent application {} received from {}", agent.getReference(), email);

        return new RegistrationOutcome(agent.getReference(), agent.getState(),
                "Your application has been received. You can sign in now, and you will be able to list "
                        + "property once the platform has approved it.");
    }

    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        if (base.isEmpty()) base = "agent";
        if (!users.existsByUsernameIgnoreCase(base)) return base;
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + suffix;
            if (!users.existsByUsernameIgnoreCase(candidate)) return candidate;
        }
        throw new HodiException("Could not derive a username.", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
