package com.hodi.infra.storage;

import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploaded files and hands back a key and a URL.
 *
 * <p>Two backends, chosen by whether {@code storage.s3.bucket} is configured: S3-compatible object storage in a
 * deployed environment, the local filesystem otherwise. The choice is per call rather than per startup because the
 * bucket is runtime configuration — an environment that gains a bucket should start using it without a restart.
 *
 * <p><strong>Keys are tenant-prefixed.</strong> Every key begins {@code t<tenantId>/}, so one merchant's media
 * cannot be addressed with another's key even by accident, and a tenant's whole media set can be listed, exported or
 * deleted by prefix. That matters more than it looks: a bucket is the one place in this system where tenant
 * isolation is not enforced by a schema.
 *
 * <p><strong>The key is authoritative, the URL is a cache.</strong> A URL expires, a CDN changes, a bucket moves;
 * the key does not. Callers store both and re-derive the URL from the key when it stops resolving.
 *
 * <p>Content type is taken from the file's own bytes-adjacent metadata and then checked against an allowlist, not
 * trusted from the name. An {@code .png} that is actually an HTML document served back from our own domain is a
 * cross-site scripting vector, which is why the allowlist is on the type rather than the extension.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    /** What a product gallery may hold. Deliberately narrow — this is not a general file store. */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif");
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of("application/pdf");

    /** 10 MB. Large enough for a product photograph, small enough that a stray upload cannot fill a disk. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ConfigurationService configs;

    /** Where a stored object lives, and how to reach it now. */
    public record Stored(String key, String url, String contentType, long sizeBytes, String fileName) {}

    /**
     * Stores a file under a tenant-prefixed key.
     *
     * @param folder a logical grouping, e.g. {@code products}. Sanitised, never trusted
     */
    public Stored store(MultipartFile file, String folder) {
        return storeAt(file, file == null ? null : buildKey(folder, file.getOriginalFilename()));
    }

    /** The common half: validate, write, and report. The key is the caller's decision. */
    private Stored storeAt(MultipartFile file, String key) {
        if (file == null || file.isEmpty()) {
            throw new HodiException("No file was uploaded", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new HodiException(
                    "That file is " + humanBytes(file.getSize()) + ". The limit is " + humanBytes(MAX_BYTES) + ".",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }

        String contentType = normaliseType(file.getContentType());
        assertAllowed(contentType);

        try (InputStream in = file.getInputStream()) {
            String url = bucket().isBlank() ? storeLocally(key, in) : storeToS3(key, in, file.getSize(), contentType);
            log.info("Stored {} ({}) for tenant {} at {}",
                    key, humanBytes(file.getSize()), TenantContext.getTenantId(),
                    bucket().isBlank() ? "local disk" : bucket());
            return new Stored(key, url, contentType, file.getSize(), safeFileName(file.getOriginalFilename()));
        } catch (IOException e) {
            throw new HodiException("That file could not be stored: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * Stores a file every tenant reads, under {@code shared/} rather than a merchant's prefix.
     *
     * <p>For the handful of things the platform owns and all its merchants render — a payment provider's logo
     * being the first. Storing one of those against whichever tenant the superadmin happened to be looking at
     * would put a file every shop displays inside one shop's prefix, where a tenant export would carry it away
     * and a tenant deletion would take it with them.
     *
     * <p>Separate from {@link #store} rather than a null-tenant branch inside it: that guard is what stops
     * merchant media being written with no owner, and it should stay a refusal rather than become a fallback.
     * Callers of this one are expected to hold a platform authority.
     */
    public Stored storeShared(MultipartFile file, String folder) {
        return storeAt(file, sharedKey(folder, file == null ? null : file.getOriginalFilename()));
    }

    /**
     * The URL a key currently resolves to.
     *
     * <p>Recomputed rather than remembered, so moving a bucket or putting a CDN in front of one is a configuration
     * change instead of a data migration across every media row.
     */
    public String urlFor(String key) {
        if (key == null || key.isBlank()) return null;
        String bucket = bucket();
        if (bucket.isBlank()) {
            return trimTrailingSlash(configs.getString(ConfigKey.STORAGE_LOCAL_BASE_URL)) + "/media/" + key;
        }
        String endpoint = configs.getString(ConfigKey.STORAGE_S3_ENDPOINT);
        if (endpoint != null && !endpoint.isBlank()) {
            return trimTrailingSlash(endpoint) + "/" + bucket + "/" + key;
        }
        return "https://" + bucket + ".s3.amazonaws.com/" + key;
    }

    /**
     * Removes a stored object.
     *
     * <p>Best effort by design: a media row is deleted whether or not the object goes with it. An orphaned object
     * costs storage; a row pointing at nothing breaks a page. Failures are logged loudly so a sweep can reconcile.
     */
    public void delete(String key) {
        if (key == null || key.isBlank()) return;
        try {
            if (bucket().isBlank()) {
                Files.deleteIfExists(localRoot().resolve(key));
            } else {
                deleteFromS3(key);
            }
        } catch (Exception e) {
            log.warn("Could not delete stored object {} — it may be orphaned", key, e);
        }
    }

    // ── backends ──────────────────────────────────────────────────────────────

    private String storeLocally(String key, InputStream in) throws IOException {
        Path target = localRoot().resolve(key).normalize();
        // Defence against a key that escapes the root. Keys are generated here, not supplied, but a traversal is
        // cheap to prevent and expensive to discover.
        if (!target.startsWith(localRoot())) {
            throw new HodiException("Invalid storage key", HttpStatus.BAD_REQUEST);
        }
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return urlFor(key);
    }

    /**
     * Uploads to S3.
     *
     * <p>The client is built per call from configuration, for the same reason {@code NotifyClient}'s is: the bucket,
     * endpoint and credentials are tenant-overridable runtime config, and a client captured at startup would send one
     * merchant's media to another's bucket.
     */
    private String storeToS3(String key, InputStream in, long size, String contentType) throws IOException {
        try (var client = s3Client()) {
            client.putObject(
                    software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                            .bucket(bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromInputStream(in, size));
        }
        return urlFor(key);
    }

    private void deleteFromS3(String key) {
        try (var client = s3Client()) {
            client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .build());
        }
    }

    private software.amazon.awssdk.services.s3.S3Client s3Client() {
        var builder = software.amazon.awssdk.services.s3.S3Client.builder()
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                configs.getString(ConfigKey.STORAGE_S3_ACCESS_KEY),
                                configs.getString(ConfigKey.STORAGE_S3_SECRET_KEY))));

        String endpoint = configs.getString(ConfigKey.STORAGE_S3_ENDPOINT);
        if (endpoint != null && !endpoint.isBlank()) {
            // An S3-compatible provider (MinIO, Spaces, R2). Path style, because those rarely support virtual-host
            // addressing and a bucket name with a dot breaks TLS validation under it.
            builder = builder.endpointOverride(java.net.URI.create(endpoint))
                    .forcePathStyle(true)
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1);
        } else {
            builder = builder.region(software.amazon.awssdk.regions.Region.US_EAST_1);
        }
        return builder.build();
    }

    // ── keys and validation ───────────────────────────────────────────────────

    /**
     * {@code t42/products/2026/07/a1b2c3d4.png}.
     *
     * <p>Tenant-prefixed so one merchant's media cannot be addressed with another's key, and date-partitioned so a
     * bucket listing stays navigable once there are a hundred thousand objects. The name is a fresh UUID rather than
     * the uploaded filename: two merchants uploading {@code logo.png} must not collide, and an uploaded name is
     * attacker-controlled.
     */
    private String buildKey(String folder, String originalName) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new HodiException(
                    "Media belongs to a merchant. Sign in to a merchant account to upload it.",
                    HttpStatus.CONFLICT);
        }
        LocalDate today = LocalDate.now();
        return "t" + tenantId + "/" + sanitiseFolder(folder)
                + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
                + "/" + UUID.randomUUID().toString().replace("-", "") + extensionOf(originalName);
    }

    /** The same shape under {@code shared/}, for what the platform owns and every tenant reads. */
    private String sharedKey(String folder, String originalName) {
        LocalDate today = LocalDate.now();
        return "shared/" + sanitiseFolder(folder)
                + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
                + "/" + UUID.randomUUID().toString().replace("-", "") + extensionOf(originalName);
    }

    private static void assertAllowed(String contentType) {
        if (ALLOWED_IMAGE_TYPES.contains(contentType) || ALLOWED_DOCUMENT_TYPES.contains(contentType)) return;
        // The allowlist is on the type, not the extension: a file named .png that is actually HTML, served back from
        // our own domain, is a cross-site scripting vector.
        throw new HodiException(
                "That file type is not accepted here. Images (JPEG, PNG, WebP, GIF, AVIF) and PDFs only.",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    static String normaliseType(String raw) {
        if (raw == null || raw.isBlank()) return "application/octet-stream";
        // Strip any parameters — "image/png; charset=binary" is the same type.
        int semicolon = raw.indexOf(';');
        return (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitiseFolder(String folder) {
        if (folder == null || folder.isBlank()) return "misc";
        String cleaned = folder.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/_-]", "");
        cleaned = cleaned.replaceAll("/{2,}", "/").replaceAll("(^/)|(/$)", "");
        return cleaned.isBlank() ? "misc" : cleaned;
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return extension.isBlank() || extension.length() > 8 ? "" : "." + extension;
    }

    /** The original name, kept for display only — never used to build a path. */
    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) return null;
        String base = name.replace("\\", "/");
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    private String bucket() {
        String bucket = configs.getString(ConfigKey.STORAGE_S3_BUCKET);
        return bucket == null ? "" : bucket.trim();
    }

    private Path localRoot() {
        return Path.of(configs.getString(ConfigKey.STORAGE_LOCAL_DIR)).toAbsolutePath().normalize();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
