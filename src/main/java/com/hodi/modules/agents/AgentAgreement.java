package com.hodi.modules.agents;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The agreement generated when an agent is approved.
 *
 * <p>Append-only, like the signature it references, and for the same reason. Plan §3.4 called this
 * {@code listing_agreement}; it is named for what it turned out to be — one agreement between the platform
 * and the agent covering everything they list, not a mandate per property. The per-listing fact FR161 asks
 * for is the ownership column on {@code properties}.
 *
 * <p>The body is kept as text rather than as a rendered document: the agreement <em>is</em> text, and
 * {@link #bodySha256} pins exactly what was generated. Turning it into a PDF is presentation and can be
 * added without changing what is recorded.
 */
@Entity
@Table(name = "agent_agreements")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentAgreement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "agent_profile_id", nullable = false) private Long agentProfileId;
    @Column(name = "signature_id") private Long signatureId;

    @Column(name = "terms_version", nullable = false, length = 32) private String termsVersion;
    @Column(name = "terms_sha256", nullable = false, length = 64) private String termsSha256;

    @Column(nullable = false, columnDefinition = "TEXT") private String body;
    @Column(name = "body_sha256", nullable = false, length = 64) private String bodySha256;

    @Column(name = "effective_from", nullable = false) @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();
    @Column(name = "generated_at", nullable = false) @Builder.Default
    private OffsetDateTime generatedAt = OffsetDateTime.now();
    @Column(name = "generated_by", length = 64) private String generatedBy;
}
