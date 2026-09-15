package com.hodi.modules.sellers;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One line of the checklist, answered.
 *
 * <p>The file itself is a {@code VaultDocument} — this is only the join saying which requirement it
 * answers. Keeping the bytes in the vault is what preserves the checksum, the per-document ACL and the
 * audited read; this table would lose all three if it held a storage key of its own.
 */
@Entity
@Table(name = "seller_application_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellerApplicationDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false) private Long applicationId;
    @Column(name = "document_id", nullable = false)    private Long documentId;
    @Column(name = "document_code", nullable = false, length = 64) private String documentCode;
    @Column(name = "original_name", length = 255) private String originalName;
    @Column(name = "uploaded_at", nullable = false) @Builder.Default
    private OffsetDateTime uploadedAt = OffsetDateTime.now();

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
