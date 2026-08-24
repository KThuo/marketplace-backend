package com.hodi.modules.institutions;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A lending institution — a bank, SACCO, MFI or insurer whose officers finance property purchases.
 *
 * <p><strong>Not a tenant</strong>, and that is the central design decision of this access model. An
 * institution's staff are ordinary {@code users} rows carrying {@code institution_id}, and their whole job is
 * reading <em>other</em> organisations' portfolios. A tenant is a boundary you are inside; an institution is
 * a party that reaches across boundaries by agreement. Modelling lenders as tenants would have meant either
 * giving them no cross-organisation visibility (useless) or a blanket exemption from tenant scoping (a hole
 * shaped exactly like the thing scoping exists to prevent). The agreement is
 * {@code tenant_lender_partnerships}, and it is the only thing that widens what a lender's staff can see.
 *
 * <p>Institutions have no per-organisation module gating in this phase — their staff are gated by user type
 * alone (plan section 12, question 2). The symmetry with {@code tenant_modules} is deliberately left
 * unbuilt rather than half-built.
 */
@Entity
@Table(name = "lending_institutions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LendingInstitution {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255) private String name;

    @Column(nullable = false, unique = true, length = 128) private String slug;

    @Column(name = "institution_ref", nullable = false, unique = true, length = 16)
    private String institutionRef;

    /** {@code BANK}, {@code SACCO}, {@code MFI}, {@code INSURER} or {@code OTHER}. */
    @Column(name = "institution_type", nullable = false, length = 16) private String institutionType;

    /** Regulator's licence number, where the institution type has one. */
    @Column(name = "licence_number", length = 64) private String licenceNumber;

    @Column(name = "contact_name", length = 128) private String contactName;
    @Column(name = "contact_email", length = 128) private String contactEmail;
    @Column(name = "contact_phone", length = 32) private String contactPhone;

    @Column(length = 2) private String country;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;
}
