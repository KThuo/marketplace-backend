package com.hodi.modules.kyc;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One grant of sight over one document.
 *
 * <p>Exactly one of {@link #userId}, {@link #tenantId} and {@link #permission} is set — a person, an
 * organisation, or anybody holding a permission code. Three kinds because the grants genuinely differ: the
 * organisation a document is about always sees its own, Compliance sees what it is judging by virtue of a
 * permission, and one reviewer can be given one document without being given the module.
 */
@Entity
@Table(name = "vault_document_acl")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VaultDocumentAcl {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false) private Long documentId;

    @Column(name = "user_id") private Long userId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(length = 64) private String permission;

    @Column(name = "grant_type", nullable = false, length = 16)
    @Builder.Default private String grantType = "READ";

    @Column(name = "granted_at", nullable = false)
    @Builder.Default private OffsetDateTime grantedAt = OffsetDateTime.now();
    @Column(name = "granted_by", length = 64) private String grantedBy;
    @Column(name = "expires_at") private OffsetDateTime expiresAt;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;

    /** In force right now: not revoked, and not lapsed. */
    public boolean isLive() {
        OffsetDateTime now = OffsetDateTime.now();
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
