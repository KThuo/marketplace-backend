package com.hodi.common;

import com.hodi.common.exception.HodiException;
import com.hodi.logging.HodiLogger;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for field-level sensitive payloads: integration credentials
 * (payment gateway, mobile money, courier, SMS/email provider), TOTP secrets, and anything else
 * stored encrypted at rest per BRD section 16.2.
 *
 * <p>Key format: base64-encoded 256-bit (32 byte) key from {@code hodi.encryption.key}.
 * Generate one locally with: {@code openssl rand -base64 32}.
 *
 * <p>Storage format: {@code IV || ciphertext} — the 12-byte IV is prepended to the ciphertext
 * so decryption is self-contained from the stored byte array.
 */
@Component
public class EncryptionUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    @Value("${hodi.encryption.key:}")
    private String base64Key;

    private SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void init() {
        if (base64Key == null || base64Key.isBlank()) {
            HodiLogger.warn("hodi.encryption.key is not set — encryption/decryption will fail at runtime");
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new HodiException("Invalid hodi.encryption.key — must be base64", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (keyBytes.length != 32) {
            throw new HodiException(
                    "hodi.encryption.key must decode to 32 bytes (256 bits); got " + keyBytes.length,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
        HodiLogger.info("EncryptionUtil initialized with 256-bit AES key");
    }

    public byte[] encrypt(byte[] data) {
        if (data == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(data);
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new HodiException("Encryption failed", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    public byte[] decrypt(byte[] data) {
        if (data == null) return null;
        if (data.length <= IV_LENGTH) {
            throw new HodiException("Ciphertext shorter than IV length", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(data, IV_LENGTH, data.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new HodiException("Decryption failed", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    public String encryptString(String plaintext) {
        if (plaintext == null) return null;
        return Base64.getEncoder().encodeToString(encrypt(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptString(String base64Ciphertext) {
        if (base64Ciphertext == null) return null;
        byte[] data = Base64.getDecoder().decode(base64Ciphertext);
        return new String(decrypt(data), StandardCharsets.UTF_8);
    }

    private SecretKeySpec requireKey() {
        if (keySpec == null) {
            throw new HodiException(
                    "Encryption key not configured (hodi.encryption.key)",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return keySpec;
    }
}
