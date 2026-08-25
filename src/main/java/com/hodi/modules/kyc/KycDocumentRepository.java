package com.hodi.modules.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycDocumentRepository extends JpaRepository<KycDocument, Long> {

    List<KycDocument> findBySubmissionId(Long submissionId);

    Optional<KycDocument> findBySubmissionIdAndDocumentCode(Long submissionId, String documentCode);
}
