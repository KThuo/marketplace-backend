package com.hodi.modules.agents;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A captured signature, and what it was captured against (FR161).
 *
 * <p><strong>Append-only.</strong> A trigger refuses UPDATE and DELETE, so there are no setters worth
 * calling after the insert and nothing in this class should acquire an update path. Superseding a signature
 * means writing another one.
 *
 * <p>{@link #termsSha256} is the field that makes this evidence rather than a tick box. It is computed on
 * the server over the terms text as it was served, so the row answers "what exactly did they accept" without
 * depending on the terms configuration still saying the same thing years later.
 */
@Entity
@Table(name = "signature_artifacts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SignatureArtifact {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "profile_id") private Long profileId;

    @Column(nullable = false, length = 32) private String purpose;

    @Column(name = "signature_kind", nullable = false, length = 16) private String signatureKind;
    @Column(name = "typed_name", length = 160) private String typedName;

    @Column(name = "storage_key", length = 512) private String storageKey;
    @Column(name = "content_type", length = 128) private String contentType;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(name = "checksum_sha256", length = 64) private String checksumSha256;
    @Column(length = 32) private String encryption;

    @Column(name = "terms_version", nullable = false, length = 32) private String termsVersion;
    @Column(name = "terms_sha256", nullable = false, length = 64) private String termsSha256;

    @Column(name = "captured_at", nullable = false) @Builder.Default
    private OffsetDateTime capturedAt = OffsetDateTime.now();
    @Column(name = "ip_address", length = 64) private String ipAddress;
    @Column(name = "user_agent", columnDefinition = "TEXT") private String userAgent;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 64) private String createdBy;

    public boolean isDrawn() {
        return AgentState.SIGNATURE_DRAWN.equals(signatureKind);
    }
}
