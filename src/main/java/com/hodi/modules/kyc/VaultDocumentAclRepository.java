package com.hodi.modules.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VaultDocumentAclRepository extends JpaRepository<VaultDocumentAcl, Long> {

    List<VaultDocumentAcl> findByDocumentId(Long documentId);
}
