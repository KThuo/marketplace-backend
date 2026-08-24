package com.hodi.modules.auth;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.password.PasswordService;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Forgotten-password reset, by email or by SMS (BRD FR007).
 *
 * <h2>Either identifier</h2>
 *
 * <p>Somebody who has forgotten their password has often also forgotten which address they signed up with,
 * and a staff account created by an administrator may have been created with an address the holder does not
 * read. So the one field accepts an email or a phone number, and the link is delivered by whichever was
 * used — email to the address, SMS to the number. Sending to <em>both</em> would widen the attack surface for
 * no benefit: whoever typed the identifier already has the one they typed.
 *
 * <p>Phone numbers are not unique in this schema — a shared handset, or an office number typed onto several
 * staff rows — so an ambiguous number resolves to nobody. Picking "the first match" would be a way to trigger
 * a reset for an account you cannot name.
 *
 * <h2>The request path tells the caller nothing</h2>
 *
 * <p>{@link #request} answers the same way whether or not the identifier belongs to an account. An endpoint that
 * distinguishes them is an account-enumeration oracle, and this one is unauthenticated and rate-limited only
 * by the gateway — so it would be a cheap one. The user-visible consequence is that somebody who mistypes
 * their address waits for an email that never comes, which is the better of the two failures.
 *
 * <p>Issuing a reset spends every outstanding one for that user. Without it, every link ever emailed to
 * somebody stays live until it expires — including one an attacker triggered, which is then as good as the one
 * the user asked for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final PasswordResetTokenRepository tokens;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final ConfigurationService configs;
    private final NotifyClient notify;
    private final AuditService audit;

    @Transactional
    public void request(String identifier, String ip) {
        String raw = identifier == null ? "" : identifier.trim();
        boolean byPhone = !raw.contains("@");
        User user = byPhone ? findByPhone(raw) : users.findByEmail(raw.toLowerCase()).orElse(null);
        if (user == null) {
            log.debug("Password reset requested for an identifier with no account");
            return;
        }
        if (!AppConstant.isLive(user.getStatus()) || !user.isEnabled()) {
            // Same silence. A deactivated account is not a hint we owe anybody.
            log.debug("Password reset requested for a non-active account {}", user.getId());
            return;
        }

        UserProfile profile = profiles.findDefaultForUser(user.getId())
                .or(() -> profiles.findLiveForUser(user.getId()).stream().findFirst())
                .orElse(null);
        if (profile == null) {
            // No profile, no organisation policy to read and nothing to sign in to. Same silence.
            log.debug("Password reset requested for account {} with no active profile", user.getId());
            return;
        }

        TenantContext.runAs(profile.getTenantId(), profile.getTenantName(), () -> {
            tokens.spendOutstanding(user.getId(), OffsetDateTime.now());

            String code = freshCode();
            int ttlHours = Math.max(1, configs.getInt(ConfigKey.AUTH_PASSWORD_RESET_TTL_HOURS));
            tokens.save(PasswordResetToken.builder()
                    .userId(user.getId())
                    .codeHash(hash(code))
                    .expiresAt(OffsetDateTime.now().plusHours(ttlHours))
                    .requestedIp(ip)
                    .build());

            String link = baseUrl() + "/reset-password/" + code;
            String expiry = ttlHours + " hour" + (ttlHours == 1 ? "" : "s");

            if (byPhone) {
                // sendSensitiveSms, not sendSms: the SMS channel switch is a preference about being
                // notified, and a reset link is not a notification somebody can opt out of and still
                // recover their account. The body is masked in the logs.
                notify.sendSensitiveSms(user.getPhone(),
                        "Reset your Hodi password: " + link + " (valid " + expiry
                                + ", works once). If this was not you, ignore it.",
                        user.fullName());
            } else {
                notify.sendSensitiveEmail(user.getEmail(), "Reset your Hodi password",
                        "<p>Someone asked to reset the password for this account.</p>"
                                + "<p><a href=\"" + link + "\">Choose a new password</a></p>"
                                + "<p>The link works once and expires in " + expiry
                                + ". If this was not you, nothing has changed and you can ignore this.</p>",
                        user.fullName());
            }
            return null;
        });
    }

    /**
     * The one account with this number, or none.
     *
     * <p>Matched on the last nine digits, so "+254 712 345 678" and "0712345678" are not two different people
     * to us when they are one person to the network. Anything resolving to more than one account resolves to
     * none.
     */
    private User findByPhone(String phone) {
        String digits = digitsOf(phone);
        if (digits.length() < 9) return null;
        String local = digits.substring(digits.length() - 9);
        List<User> matches = users.findByPhoneLocal(local);
        if (matches.size() != 1) {
            if (matches.size() > 1) {
                log.warn("Password reset by phone matched {} accounts — refused as ambiguous",
                        matches.size());
            }
            return null;
        }
        return matches.get(0);
    }

    private static String digitsOf(String value) {
        return value.replaceAll("\\D", "");
    }

    @Transactional
    public void reset(String code, String newPassword) {
        PasswordResetToken token = tokens.findByCodeHash(hash(code == null ? "" : code.trim()))
                // Same message for an unknown code and a spent one on the request side of things, but here
                // the caller is holding something they believe is a link, so saying it is no longer usable is
                // more useful than a flat refusal — and it reveals nothing they did not already have.
                .orElseThrow(() -> new HodiException(
                        "That reset link is not valid. Request a new one.", HttpStatus.BAD_REQUEST));

        if (!token.isUsable()) {
            throw new HodiException("That reset link has expired or has already been used. "
                    + "Request a new one.", HttpStatus.BAD_REQUEST);
        }

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new HodiException("That reset link is not valid.",
                        HttpStatus.BAD_REQUEST));

        UserProfile profile = profiles.findDefaultForUser(user.getId())
                .or(() -> profiles.findLiveForUser(user.getId()).stream().findFirst())
                .orElse(null);
        Long policyTenantId = profile == null ? null : profile.getTenantId();
        String policyTenantName = profile == null ? null : profile.getTenantName();

        TenantContext.runAs(policyTenantId, policyTenantName, () -> {
            passwords.applyTo(user, newPassword);
            users.save(user);

            token.setUsedAt(OffsetDateTime.now());
            tokens.save(token);

            /*
             * Every session ends, access tokens included. Somebody resetting a forgotten password is, more
             * often than not, doing it because they suspect the account is compromised — and a reset that
             * leaves the intruder's current token alive for another twenty minutes addresses nothing.
             */
            user.setSessionsValidFrom(OffsetDateTime.now());
            users.save(user);
            refreshTokens.revokeAllForUser(user.getId());
            audit.record(AppConstant.AUDIT_PASSWORD_RESET, "User", user.getId(), null, "via reset link");
            return null;
        });
    }

    private String baseUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    /** URL-safe and long enough that guessing is not a strategy. */
    private static String freshCode() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
