package com.hodi.modules.agents;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SignatureArtifactRepository extends JpaRepository<SignatureArtifact, Long> {

    Optional<SignatureArtifact> findByReference(String reference);

    List<SignatureArtifact> findByUserIdOrderByCapturedAtDesc(Long userId);
}
