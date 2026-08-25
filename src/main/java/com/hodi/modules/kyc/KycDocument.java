package com.hodi.modules.kyc;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One document's place in one pack, and Compliance's verdict on it.
 *
 * <p>A join row rather than a column on the vault document, because the same file can be produced for two
 * submissions — a renewal reuses the certificate of incorporation — and each submission's verdict on it is
 * its own.
 */
@Entity
@Table(name = "kyc_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false) private Long submissionId;
    @Column(name = "document_id", nullable = false) private Long documentId;
    @Column(name = "document_code", nullable = false, length = 48) private String documentCode;

    @Column(nullable = false, length = 16)
    @Builder.Default private String verdict = AppConstant.VERDICT_PENDING;
    @Column(name = "verdict_note", columnDefinition = "TEXT") private String verdictNote;
    @Column(name = "verified_at") private OffsetDateTime verifiedAt;
    @Column(name = "verified_by_user_id") private Long verifiedByUserId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at", nullable = false)
    @Builder.Default private OffsetDateTime updatedAt = OffsetDateTime.now();
}
