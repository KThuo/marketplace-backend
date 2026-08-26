package com.hodi.modules.operations;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Where a new lead goes (M12).
 *
 * <p>Ordered, first match wins. Deliberately not scored: a scoring system is one nobody can predict the
 * behaviour of by reading the list, and routing is a thing people need to predict.
 */
@Entity
@Table(name = "ticket_assignment_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(nullable = false, length = 160) private String name;

    /** Null means the platform's own rules. */
    @Column(name = "tenant_id") private Long tenantId;

    @Column(name = "work_type", nullable = false, length = 24) private String workType;

    @Column(length = 64) private String county;
    @Column(name = "property_type", length = 32) private String propertyType;
    @Column(name = "match_tenant_id") private Long matchTenantId;

    @Column(name = "assignee_user_id") private Long assigneeUserId;
    @Column(name = "assignee_name", length = 160) private String assigneeName;
    @Column(name = "assignee_group_id") private Long assigneeGroupId;
    @Column(name = "assignee_group_name", length = 160) private String assigneeGroupName;
    @Column(name = "last_assigned_user_id") private Long lastAssignedUserId;

    @Column(nullable = false) @Builder.Default private Integer priority = 100;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    /**
     * Whether this rule covers a lead with these attributes.
     *
     * <p>A null criterion matches anything — that is what makes a catch-all rule at the bottom of the list
     * possible without a special "catch all" flag.
     */
    public boolean matches(String leadCounty, String leadPropertyType, Long leadTenantId) {
        return same(county, leadCounty)
                && same(propertyType, leadPropertyType)
                && (matchTenantId == null || matchTenantId.equals(leadTenantId));
    }

    private static boolean same(String criterion, String value) {
        if (criterion == null || criterion.isBlank()) return true;
        return value != null && criterion.trim().equalsIgnoreCase(value.trim());
    }

    public boolean isRoundRobin() {
        return assigneeGroupId != null;
    }

    /** How it reads on a list, without the reader reconstructing it from four columns. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        if (county != null) out.append(county).append(' ');
        if (propertyType != null) out.append(propertyType.toLowerCase(Locale.ROOT)).append(' ');
        if (out.isEmpty()) out.append("anything ");
        out.append("→ ").append(isRoundRobin() ? assigneeGroupName + " (in turn)" : assigneeName);
        return out.toString();
    }
}
