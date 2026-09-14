package com.hodi.modules.agents;

import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.vault.VaultStorage;
import com.hodi.modules.configurations.ConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

/**
 * Capturing a signature so that it means something afterwards (FR161).
 *
 * <h2>The hash is computed here, never accepted</h2>
 *
 * <p>The client is shown the terms and sends back a version and a signature. It does <em>not</em> send the
 * hash: a hash supplied by the party being bound is an assertion about what they agreed to, and the whole
 * point of the artifact is to be able to say what they agreed to without taking their word for it. So the
 * text is re-read from configuration here and hashed here.
 *
 * <p>The version <em>is</em> accepted, and then checked. If it does not match what is currently in force,
 * the signature is refused rather than recorded against the wrong text — somebody who left the page open
 * while the terms changed must read the new ones. That refusal is a real state, not a theoretical one: it is
 * what happens on the day the terms are edited.
 *
 * <h2>Where the image goes</h2>
 *
 * <p>The vault, not the media store. A signature is a specimen of somebody's hand — it belongs with KYC
 * documents behind encryption and a per-read audit, not on the path that serves photographs of houses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureService {

    /** What a browser canvas produces, and nothing else. A signature is not a PDF. */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    /** A drawn signature is a few kilobytes. Anything approaching a megabyte is not a signature. */
    private static final int MAX_IMAGE_BYTES = 512 * 1024;

    private final SignatureArtifactRepository signatures;
    private final ConfigurationService configs;
    private final VaultStorage vault;

    /** The terms as currently in force, with the hash the signature will be recorded against. */
    public record Terms(String version, String text, String sha256) {}

    /**
     * The terms as currently in force, with the platform's name filled in.
     *
     * <p>The stored text says {@code {{platformName}}} rather than naming the platform, because the name is
     * configuration and a re-brand should not leave the old one in a document somebody is being asked to
     * sign. It is substituted here, before the text is returned.
     *
     * <p>The hash is taken <strong>after</strong> substitution, and that is the part that matters: the hash
     * is the record of what a person read and agreed to, so it has to be a hash of the words they actually
     * saw. Hashing the template would produce a signature against text nobody was shown.
     *
     * <p>One consequence worth being clear about: renaming the platform changes the hash of the terms in
     * force, so the next signature records a different one. Signatures already captured keep the hash they
     * were taken against, which is what makes the old agreements still verifiable.
     */
    public Terms currentTerms() {
        String version = configs.getString(ConfigKey.AGENT_TERMS_VERSION);
        String text = configs.getString(ConfigKey.AGENT_TERMS_TEXT);
        if (text == null || text.isBlank()) {
            throw new HodiException("The agent terms have not been configured yet.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        String shown = text.replace("{{platformName}}", configs.getString(ConfigKey.COMPANY_NAME));
        return new Terms(version, shown, sha256(shown));
    }

    /**
     * Records a signature against the terms currently in force.
     *
     * @param acceptedVersion the version the signer says they were shown
     * @param kind            {@code DRAWN} or {@code TYPED}
     * @param imageDataUrl    a {@code data:image/png;base64,…} URL for a drawn signature; ignored otherwise
     * @param typedName       what a typed signer keyed in; ignored for a drawn one
     */
    @Transactional
    public SignatureArtifact capture(Long userId, Long profileId, String purpose,
                                     String acceptedVersion, String kind,
                                     String imageDataUrl, String typedName,
                                     HttpServletRequest request) {
        Terms terms = currentTerms();
        if (acceptedVersion != null && !acceptedVersion.isBlank()
                && !acceptedVersion.equals(terms.version())) {
            throw new HodiException(
                    "The terms have changed since this page was opened. Reload it and read them again "
                            + "before signing.", HttpStatus.CONFLICT);
        }

        SignatureArtifact.SignatureArtifactBuilder builder = SignatureArtifact.builder()
                .reference(RrnGenerator.generate("SG"))
                .userId(userId)
                .profileId(profileId)
                .purpose(purpose)
                .termsVersion(terms.version())
                .termsSha256(terms.sha256())
                .ipAddress(clientAddress(request))
                .userAgent(header(request, "User-Agent"))
                .createdBy("self");

        if (AgentState.SIGNATURE_TYPED.equals(kind)) {
            if (typedName == null || typedName.isBlank()) {
                throw new HodiException("Type your full name to sign.", HttpStatus.BAD_REQUEST);
            }
            builder.signatureKind(AgentState.SIGNATURE_TYPED).typedName(typedName.trim());
        } else {
            Decoded image = decode(imageDataUrl);
            VaultStorage.Stored stored = vault.store(
                    image.bytes(), "signature." + extensionFor(image.contentType()),
                    image.contentType(), "signatures");
            builder.signatureKind(AgentState.SIGNATURE_DRAWN)
                    .storageKey(stored.key())
                    .contentType(stored.contentType())
                    .sizeBytes(stored.sizeBytes())
                    .checksumSha256(stored.checksumSha256())
                    .encryption(stored.encryption());
        }

        SignatureArtifact saved = signatures.save(builder.build());
        log.info("Signature {} captured for user {} against terms {} ({})",
                saved.getReference(), userId, terms.version(), saved.getSignatureKind());
        return saved;
    }

    /** The stored image, for the platform screen that has to look at it. */
    public byte[] imageOf(SignatureArtifact artifact) {
        if (!artifact.isDrawn() || artifact.getStorageKey() == null) {
            throw new HodiException("That signature was typed, not drawn — there is no image.",
                    HttpStatus.NOT_FOUND);
        }
        return vault.read(artifact.getStorageKey());
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private record Decoded(byte[] bytes, String contentType) {}

    /**
     * A {@code data:} URL, taken apart carefully.
     *
     * <p>The content type is read from the URL and then checked against an allowlist rather than trusted:
     * the string arrives from a browser and names what the bytes will be stored and later served as.
     */
    private Decoded decode(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw new HodiException("Draw your signature before continuing.", HttpStatus.BAD_REQUEST);
        }
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0) {
            throw new HodiException("That signature could not be read.", HttpStatus.BAD_REQUEST);
        }
        String header = dataUrl.substring(5, comma).toLowerCase();
        if (!header.contains(";base64")) {
            throw new HodiException("That signature could not be read.", HttpStatus.BAD_REQUEST);
        }
        String contentType = header.substring(0, header.indexOf(';')).trim();
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new HodiException("A signature must be an image.", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new HodiException("That signature could not be read.", HttpStatus.BAD_REQUEST, e);
        }
        if (bytes.length == 0) {
            throw new HodiException("Draw your signature before continuing.", HttpStatus.BAD_REQUEST);
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new HodiException("That signature image is too large.", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return new Decoded(bytes, contentType);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    /**
     * The address the signature came from.
     *
     * <p>{@code X-Forwarded-For} is read because the application sits behind a proxy in every deployment
     * that matters — and only its first entry, because the rest are whatever the client chose to send.
     */
    private static String clientAddress(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return request.getRemoteAddr();
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }
}
