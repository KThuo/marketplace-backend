package com.hodi.modules.valuations;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The valuer's answer (M5).
 *
 * <p>Two figures, because a Kenyan valuation carries two: market value, and the forced-sale value the bank
 * actually lends against. The CHECK keeps the second at or below the first — a forced sale that fetched more
 * than the market would be a typo, and it is the figure a mortgage is sized from.
 *
 * <p>The signed report is a vault document, referenced rather than joined: it is evidence the bank relies
 * on, so it lives behind an ACL rather than on the media path.
 */
@Entity
@Table(name = "valuation_reports")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ValuationReport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false) private Long requestId;

    @Column(name = "market_value", nullable = false, precision = 15, scale = 2) private BigDecimal marketValue;
    @Column(name = "forced_sale_value", precision = 15, scale = 2) private BigDecimal forcedSaleValue;
    @Column(name = "insurance_value", precision = 15, scale = 2) private BigDecimal insuranceValue;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(length = 32) private String methodology;
    @Column(name = "inspected_on") private LocalDate inspectedOn;
    @Column(name = "condition_note", columnDefinition = "TEXT") private String conditionNote;
    @Column(columnDefinition = "TEXT") private String assumptions;
    @Column(columnDefinition = "TEXT") private String comparables;

    @Column(name = "document_reference", length = 16) private String documentReference;

    @Column(name = "submitted_at", nullable = false)
    @Builder.Default private OffsetDateTime submittedAt = OffsetDateTime.now();
    @Column(name = "submitted_by_user_id") private Long submittedByUserId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at", nullable = false)
    @Builder.Default private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
