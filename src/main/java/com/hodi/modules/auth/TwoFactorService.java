package com.hodi.modules.auth;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.password.PasswordService;
import com.hodi.security.totp.TotpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * TOTP enrolment and the SMS-OTP toggle.
 *
 * <p>Enrolment is two steps on purpose. {@link #setup} generates a secret and hands back the provisioning URI
 * for a QR code, but leaves {@code totp_confirmed_at} null; only {@link #confirm}, which requires a code the
 * authenticator app actually produced, marks it usable. Without that split, somebody who scanned the QR into
 * an app that then failed — or who never scanned it at all — would be locked out of their own account by a
 * second factor they cannot produce.
 *
 * <p>The secret is encrypted at rest by {@code TotpService}. It is a bearer credential: anybody holding
 * it can generate this user's codes forever, so it is worth more than the password hash beside it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final TotpService totp;
    private final ConfigurationService configs;
    private final PasswordService passwords;
    private final AuditService audit;

    /**
     * What the client needs to render the enrolment screen: the base32 secret for manual entry, and a
     * data-URI PNG of the QR code so the page needs no second request and no external image host.
     */
    public record TotpSetup(String secret, String qrDataUri) {}

    @Transactional
    public TotpSetup setup(Long userId) {
        assertTotpAvailable();
        User user = users.findById(userId).orElseThrow(this::notFound);

        String secret = totp.generateSecret();
        user.setTotpSecret(totp.encryptSecret(secret));
        // Deliberately NOT confirmed and NOT enabled yet. A secret nobody has proved they can use is not a
        // second factor, it is a lockout waiting to happen.
        user.setTotpConfirmedAt(null);
        user.setTotpEnabled(false);
        users.save(user);

        return new TotpSetup(secret, totp.generateQrDataUri(user, secret));
    }

    @Transactional
    public void confirm(Long userId, String code) {
        assertTotpAvailable();
        User user = users.findById(userId).orElseThrow(this::notFound);
        if (user.getTotpSecret() == null) {
            throw new HodiException("Start the setup again — there is nothing to confirm.",
                    HttpStatus.BAD_REQUEST);
        }
        // Decrypted here rather than inside verify(): the secret is stored encrypted, and a verify that
        // silently accepted either form would hide the day somebody stored a plaintext one.
        if (!totp.verifyCode(totp.decryptSecret(user.getTotpSecret()), code)) {
            throw new HodiException("That code is not right. Check the app and try again.",
                    HttpStatus.BAD_REQUEST);
        }
        user.setTotpEnabled(true);
        user.setTotpConfirmedAt(OffsetDateTime.now());
        users.save(user);
        audit.record(AppConstant.AUDIT_TOTP_SETUP, "User", userId, null, "TOTP confirmed");
    }

    /**
     * Turning the second factor off requires the password again.
     *
     * <p>Otherwise a stolen access token is enough to remove the very control that was supposed to make a
     * stolen access token insufficient. This is the one profile action where re-authentication is worth the
     * friction.
     */
    @Transactional
    public void disable(Long userId, String currentPassword) {
        User user = users.findById(userId).orElseThrow(this::notFound);
        if (!passwords.matches(currentPassword, user.getPassword())) {
            throw new HodiException("That is not your current password", HttpStatus.BAD_REQUEST);
        }
        // Across every profile: somebody who is a buyer *and* a seller's owner is staff, and letting them
        // turn off the second factor from their buyer profile would be a way around the staff rule.
        boolean staffAnywhere = profiles.findLiveForUser(userId).stream()
                .anyMatch(profile -> !profile.isBuyerActor());
        if (configs.getBoolean(ConfigKey.AUTH_TOTP_REQUIRED) && staffAnywhere) {
            throw new HodiException(
                    "Two-factor authentication is required for staff accounts and cannot be turned off.",
                    HttpStatus.CONFLICT);
        }
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpConfirmedAt(null);
        users.save(user);
        audit.record(AppConstant.AUDIT_TOTP_DISABLE, "User", userId, "enabled", "disabled");
    }

    /** The SMS fallback, for staff without an authenticator app. */
    @Transactional
    public void setSmsOtp(Long userId, boolean enabled) {
        User user = users.findById(userId).orElseThrow(this::notFound);
        if (enabled && (user.getPhone() == null || user.getPhone().isBlank())) {
            throw new HodiException("Add a phone number before turning on SMS codes.",
                    HttpStatus.BAD_REQUEST);
        }
        user.setSmsOtpEnabled(enabled);
        users.save(user);
        audit.record(AppConstant.ACTION_UPDATE, "User", userId,
                "smsOtp=" + !enabled, "smsOtp=" + enabled);
    }

    private void assertTotpAvailable() {
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_ENABLED)) {
            throw new HodiException("Two-factor authentication is switched off on this platform.",
                    HttpStatus.CONFLICT);
        }
    }

    private HodiException notFound() {
        return new HodiException("Account not found", HttpStatus.NOT_FOUND);
    }
}
