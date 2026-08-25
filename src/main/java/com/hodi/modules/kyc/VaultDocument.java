package com.hodi.modules.kyc;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A document in the vault (plan §3.9).
 *
 * <p><strong>There is no {@code url} field and there must not be one.</strong> The whole point of the vault
 * is that the only route to the bytes is an endpoint that checks an ACL and writes an audit row; a URL on
 * this row would be a second route with neither. The table carries the same warning as a comment.
 */
@Entity
@Table(name = "vault_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VaultDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    /** Who the document is about. One of the two is set — the table's CHECK requires it. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "user_id") private Long userId;

    @Column(name = "document_code", nullable = false, length = 48) private String documentCode;
    @Column(name = "document_name", length = 160) private String documentName;

    @Column(name = "storage_key", nullable = false, length = 512) private String storageKey;
    @Column(name = "content_type", length = 64) private String contentType;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(name = "original_name", length = 255) private String originalName;
    @Column(name = "checksum_sha256", length = 64) private String checksumSha256;

    @Column(nullable = false, length = 24)
    @Builder.Default private String encryption = AppConstant.ENCRYPTION_NONE;

    @Column(name = "issued_on") private LocalDate issuedOn;
    @Column(name = "expires_on") private LocalDate expiresOn;

    @Column(name = "uploaded_by_user_id") private Long uploadedByUserId;
    @Column(name = "uploaded_by_name", length = 160) private String uploadedByName;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Past its stated validity. A lapsed document is not evidence of anything. */
    public boolean isExpired() {
        return expiresOn != null && expiresOn.isBefore(LocalDate.now());
    }
}
