package com.hodi.infra.vault;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * Storage for documents nobody but Compliance should see (plan §3.9).
 *
 * <h2>Why this is not StorageService</h2>
 *
 * <p>{@code StorageService} exists to produce URLs a browser fetches directly — that is what a listing
 * photograph needs and what makes it fast. A CR12 needs the opposite: no URL form at all, so that the only
 * way to the bytes is an endpoint that checks an ACL and writes an audit row. Adding a "private" flag to the
 * media store would have left the URL-producing method one call away from a KYC document, and a flag is not
 * a boundary. Two classes is.
 *
 * <h2>What it does that the media store does not</h2>
 *
 * <ul>
 *   <li>Writes under its own {@code vault/} prefix, so a bucket policy can differ between the two.</li>
 *   <li>Declares server-side encryption on write and reports what was declared, so the row can record it.</li>
 *   <li>Computes a SHA-256 of the bytes, so a later dispute can be settled on what was received.</li>
 *   <li>Reads bytes back into memory rather than handing out a link.</li>
 *   <li>Has no {@code urlFor}. Deliberately. Do not add one.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultStorage {

    /** Documents, not pictures of houses — but a photographed ID is a real thing people upload. */
    private static final Set<String> ALLOWED = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");

    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final String PREFIX = "vault";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ConfigurationService configs;

    /**
     * @param encryption what the storage layer declared. {@code NONE} on local disk, which is why local disk
     *                   is a development arrangement and the row says so rather than the deployment implying it
     */
    public record Stored(String key, String contentType, long sizeBytes, String fileName,
                         String checksumSha256, String encryption) {}

    public Stored store(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new HodiException("No file was uploaded", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new HodiException("That file is larger than the 15 MB limit.",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }

        String contentType = normalise(file.getContentType());
        if (!ALLOWED.contains(contentType)) {
            throw new HodiException(
                    "Upload a PDF or a photograph. " + contentType + " is not accepted.",
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new HodiException("That file could not be read.", HttpStatus.BAD_REQUEST, e);
        }

        return store(bytes, file.getOriginalFilename(), contentType, folder);
    }

    /**
     * The same, for bytes that never arrived as an upload.
     *
     * <p>A drawn signature reaches the server inside a JSON registration payload as a data URL, not as a
     * multipart part — and a signature belongs in the vault for exactly the reasons a KYC document does. The
     * alternative was a second endpoint and a half-finished registration waiting for it, which is a worse
     * shape than an overload: the signature and the application it is evidence for should be written in one
     * transaction or not at all.
     *
     * <p>Same ceiling, same allowlist, same checksum. The caller states the content type because there is no
     * file to sniff one from; anything outside the allowlist is refused here as it would be there.
     */
    public Stored store(byte[] bytes, String fileName, String declaredType, String folder) {
        if (bytes == null || bytes.length == 0) {
            throw new HodiException("There was nothing to store", HttpStatus.BAD_REQUEST);
        }
        if (bytes.length > MAX_BYTES) {
            throw new HodiException("That file is larger than the 15 MB limit.",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
        String contentType = normalise(declaredType);
        if (!ALLOWED.contains(contentType)) {
            throw new HodiException("Upload a PDF or a photograph. " + contentType + " is not accepted.",
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        String key = buildKey(folder, fileName);
        String checksum = sha256(bytes);
        String encryption = bucket().isBlank() ? AppConstant.ENCRYPTION_NONE : writeToS3(key, bytes, contentType);
        if (bucket().isBlank()) writeLocally(key, bytes);

        log.info("Vault stored {} ({} bytes, {}) sha256={}", key, bytes.length, encryption, checksum);
        return new Stored(key, contentType, bytes.length, safeName(fileName), checksum, encryption);
    }

    /**
     * The bytes back.
     *
     * <p>Into memory, not a stream to the client: the caller has already checked an ACL and is about to write
     * an audit row, and both of those want to happen before a single byte leaves. The 15 MB ceiling is what
     * makes that affordable.
     */
    public byte[] read(String key) {
        if (key == null || key.isBlank()) {
            throw new HodiException("That document has no stored file.", HttpStatus.NOT_FOUND);
        }
        try {
            if (bucket().isBlank()) {
                Path path = localRoot().resolve(key).normalize();
                if (!path.startsWith(localRoot())) {
                    // The key comes from our own row, but a traversal here would read anything on the disk.
                    throw new HodiException("That document could not be read.", HttpStatus.FORBIDDEN);
                }
                return Files.readAllBytes(path);
            }
            try (var client = s3Client()) {
                return client.getObjectAsBytes(
                        software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                                .bucket(bucket()).key(key).build()).asByteArray();
            }
        } catch (IOException e) {
            throw new HodiException("That document could not be read.",
                    HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    public void delete(String key) {
        if (key == null || key.isBlank()) return;
        try {
            if (bucket().isBlank()) {
                Files.deleteIfExists(localRoot().resolve(key).normalize());
                return;
            }
            try (var client = s3Client()) {
                client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                        .bucket(bucket()).key(key).build());
            }
        } catch (IOException e) {
            log.warn("Could not delete vault object {}: {}", key, e.getMessage());
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * @return the encryption actually declared, so the row records what happened rather than what was wanted
     */
    private String writeToS3(String key, byte[] bytes, String contentType) {
        String kmsKey = configs.getString(ConfigKey.VAULT_KMS_KEY_ID);
        var builder = software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(contentType);

        String declared;
        if (kmsKey != null && !kmsKey.isBlank()) {
            builder.serverSideEncryption(software.amazon.awssdk.services.s3.model.ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(kmsKey);
            declared = AppConstant.ENCRYPTION_SSE_KMS;
        } else {
            builder.serverSideEncryption(software.amazon.awssdk.services.s3.model.ServerSideEncryption.AES256);
            declared = AppConstant.ENCRYPTION_SSE_S3;
        }

        try (var client = s3Client()) {
            client.putObject(builder.build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
        }
        return declared;
    }

    private void writeLocally(String key, byte[] bytes) {
        try {
            Path target = localRoot().resolve(key).normalize();
            Files.createDirectories(target.getParent());
            Files.copy(new java.io.ByteArrayInputStream(bytes), target,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new HodiException("That file could not be stored.",
                    HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * {@code vault/<folder>/yyyy/MM/<uuid>.<ext>}.
     *
     * <p>No part of the original filename survives into the key. A KYC upload is often named
     * "john_doe_id.pdf", and a key is the one part of an object store that tends to leak into logs, listings
     * and error messages.
     */
    private String buildKey(String folder, String originalName) {
        String safeFolder = folder == null ? "misc" : folder.replaceAll("[^a-zA-Z0-9._-]", "").toLowerCase();
        if (safeFolder.isBlank()) safeFolder = "misc";
        String extension = extensionOf(originalName);
        return PREFIX + '/' + safeFolder + '/' + MONTH.format(LocalDate.now()) + '/'
                + UUID.randomUUID().toString().replace("-", "") + extension;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private software.amazon.awssdk.services.s3.S3Client s3Client() {
        var builder = software.amazon.awssdk.services.s3.S3Client.builder()
                .region(software.amazon.awssdk.regions.Region.of(
                        configs.getString(ConfigKey.STORAGE_S3_REGION)));
        String accessKey = configs.getString(ConfigKey.STORAGE_S3_ACCESS_KEY);
        String secretKey = configs.getString(ConfigKey.STORAGE_S3_SECRET_KEY);
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(
                    software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                    accessKey, secretKey)));
        }
        return builder.build();
    }

    private String bucket() {
        String bucket = configs.getString(ConfigKey.VAULT_S3_BUCKET);
        if (bucket == null || bucket.isBlank()) bucket = configs.getString(ConfigKey.STORAGE_S3_BUCKET);
        return bucket == null ? "" : bucket.trim();
    }

    private Path localRoot() {
        return Path.of(configs.getString(ConfigKey.STORAGE_LOCAL_DIR)).toAbsolutePath().normalize();
    }

    private static String normalise(String contentType) {
        if (contentType == null) return "application/octet-stream";
        int semi = contentType.indexOf(';');
        return (semi > 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase();
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot).toLowerCase();
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "document";
        String base = name.replaceAll("[\\\\/]", "_").trim();
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
