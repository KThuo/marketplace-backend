package com.hodi.modules.auth;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
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
import java.util.Base64;
import java.util.HexFormat;

/**
 * Forgotten-password reset by emailed link.
 *
 * <h2>The request path tells the caller nothing</h2>
 *
 * <p>{@link #request} answers the same way whether or not the address belongs to an account. An endpoint that
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
    private final PasswordResetTokenRepository tokens;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final ConfigurationService configs;
    private final NotifyClient notify;
    private final AuditService audit;

    @Transactional
    public void request(String email, String ip) {
        String normalised = email == null ? "" : email.trim().toLowerCase();
        User user = users.findByEmail(normalised).orElse(null);
        if (user == null) {
            log.debug("Password reset requested for an address with no account");
            return;
        }
        if (!AppConstant.isLive(user.getStatus()) || !user.isEnabled()) {
            // Same silence. A deactivated account is not a hint we owe anybody.
            log.debug("Password reset requested for a non-active account {}", user.getId());
            return;
        }

        TenantContext.runAs(user.getTenantId(), user.getTenantName(), () -> {
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
            notify.sendSensitiveEmail(user.getEmail(), "Reset your Hodi password",
                    "<p>Someone asked to reset the password for this account.</p>"
                            + "<p><a href=\"" + link + "\">Choose a new password</a></p>"
                            + "<p>The link works once and expires in " + ttlHours + " hour"
                            + (ttlHours == 1 ? "" : "s")
                            + ". If this was not you, nothing has changed and you can ignore this.</p>",
                    user.fullName());
            return null;
        });
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

        TenantContext.runAs(user.getTenantId(), user.getTenantName(), () -> {
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
