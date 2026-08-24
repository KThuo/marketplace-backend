package com.hodi.modules.appmodules;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A feature module, and access axis (1) of plan section 4.
 *
 * <p>{@link #allowedUserTypes} is a CSV of {@code user_types.code} and is <strong>enforced</strong>: a user
 * type absent from it cannot reach this module no matter what permissions an organisation grants.
 */
@Entity
@Table(name = "app_modules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppModule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** CSV of user-type codes. Matched by exact token, never with SQL LIKE — see {@link #allows}. */
    @Column(name = "allowed_user_types", length = 512)
    private String allowedUserTypes;

    /** Core modules are on for every seller organisation and cannot be switched off. */
    @Column(name = "is_core", nullable = false)
    @Builder.Default
    private boolean core = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer status = 1;

    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default
    private String statusFlag = "Active";

    @Column(name = "deactivation_reason", columnDefinition = "TEXT")
    private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /**
     * Whether a user type may reach this module.
     *
     * <p>Splits and compares exact tokens. A substring test would be wrong in the direction that grants
     * rather than denies: {@code allowed_user_types LIKE '%ADMIN%'} also matches {@code SUPER_ADMIN} and
     * {@code LENDER_ADMIN}, and all three codes exist in {@code UserTypeEnum}. That is the whole reason this
     * is a method over an in-memory set rather than a SQL predicate.
     */
    public boolean allows(String userTypeCode) {
        if (allowedUserTypes == null || allowedUserTypes.isBlank() || userTypeCode == null) return false;
        return allowedTypeSet().contains(userTypeCode.trim().toUpperCase());
    }

    public Set<String> allowedTypeSet() {
        if (allowedUserTypes == null || allowedUserTypes.isBlank()) return Set.of();
        return Arrays.stream(allowedUserTypes.split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
