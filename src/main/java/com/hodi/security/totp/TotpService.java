package com.hodi.security.totp;

import com.hodi.common.EncryptionUtil;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.users.User;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * TOTP enrollment and verification. The master switch is the
 * {@code auth.totp.enabled} configuration key — when false, {@link #enabled()} returns
 * false and the auth flow skips the OTP step entirely.
 */
@Service
@RequiredArgsConstructor
public class TotpService {

    private static final String ISSUER = "Hodi Market Place";

    private final ConfigurationService configs;
    private final EncryptionUtil encryptionUtil;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();

    public boolean enabled() {
        return configs.getBoolean(ConfigKey.AUTH_TOTP_ENABLED);
    }

    public boolean requiredForAllUsers() {
        return configs.getBoolean(ConfigKey.AUTH_TOTP_REQUIRED);
    }

    /** Generate a fresh base32 secret (not yet associated with a user — caller persists). */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /** Encrypt a secret before persisting on the user row. */
    public String encryptSecret(String plainSecret) {
        return encryptionUtil.encryptString(plainSecret);
    }

    public String decryptSecret(String encryptedSecret) {
        return encryptionUtil.decryptString(encryptedSecret);
    }

    /** Build a {@code otpauth://} provisioning URI plus a base64 PNG QR payload. */
    public String generateQrDataUri(User user, String plainSecret) {
        QrData data = new QrData.Builder()
                .label(ISSUER + ":" + user.getUsername())
                .secret(plainSecret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            byte[] png = qrGenerator.generate(data);
            return Utils.getDataUriForImage(png, qrGenerator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new HodiException("Failed to generate TOTP QR", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    public boolean verifyCode(String plainSecret, String code) {
        if (plainSecret == null || code == null) return false;
        return codeVerifier.isValidCode(plainSecret, code);
    }
}
