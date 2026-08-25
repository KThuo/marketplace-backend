package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A request to see a property, and what was agreed (M4, BRD FR041–FR044).
 *
 * <p>Two times, not one. {@link #requestedAt} is what the buyer asked for and never changes;
 * {@link #slotAt} is what the two sides settled on. Overwriting the first with the second would lose
 * exactly the thing a seller needs when a buyer says "but I asked for Saturday".
 */
@Entity
@Table(name = "site_visits")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SiteVisit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;
    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "property_reference", nullable = false, length = 16) private String propertyReference;
    @Column(name = "property_title", length = 255) private String propertyTitle;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "buyer_name", length = 160) private String buyerName;
    @Column(name = "buyer_email", length = 128) private String buyerEmail;
    @Column(name = "buyer_phone", length = 32) private String buyerPhone;

    @Column(name = "requested_at", nullable = false) private OffsetDateTime requestedAt;
    @Column(name = "slot_at") private OffsetDateTime slotAt;
    @Column(name = "party_size") private Short partySize;
    @Column(name = "buyer_note", columnDefinition = "TEXT") private String buyerNote;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.VISIT_REQUESTED;
    @Column(name = "seller_note", columnDefinition = "TEXT") private String sellerNote;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @Column(name = "outcome_note", columnDefinition = "TEXT") private String outcomeNote;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** Still to happen: agreed, and the agreed time has not passed. */
    public boolean isUpcoming() {
        return AppConstant.VISIT_CONFIRMED.equals(state)
                && slotAt != null && slotAt.isAfter(OffsetDateTime.now());
    }

    /** Whether the buyer can still call it off — anything not already decided against or done. */
    public boolean isCancellable() {
        return AppConstant.VISIT_REQUESTED.equals(state) || AppConstant.VISIT_CONFIRMED.equals(state);
    }
}
