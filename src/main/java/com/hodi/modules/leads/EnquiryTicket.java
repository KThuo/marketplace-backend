package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A conversation between a buyer and a seller about one listing (M4, BRD FR035–FR040).
 *
 * <p>The one row in this schema that carries both visibility rules at once: {@code userId} is the buyer,
 * read through {@code /api/v1/me}, and {@code tenantId} is the seller, read through {@code TenantScope}. A
 * lead <em>is</em> the meeting of the two, so the row belongs to both and neither service reaches across.
 *
 * <p>The contact fields are snapshots, not label caches — the details this buyer gave for this enquiry. A
 * seller ringing back a lead from March should find the number that was on it in March.
 */
@Entity
@Table(name = "enquiry_tickets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EnquiryTicket {

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

    @Column(length = 180) private String subject;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.ENQUIRY_OPEN;

    /** The agent who brought this buyer, when one is named this early. Carried onto their offer and booking. */
    @Column(name = "introduced_by_agent_id") private Long introducedByAgentId;

    @Column(name = "assigned_to_user_id") private Long assignedToUserId;
    @Column(name = "assigned_to_name", length = 160) private String assignedToName;

    @Column(name = "message_count", nullable = false) @Builder.Default private Integer messageCount = 0;
    @Column(name = "last_message_at") private OffsetDateTime lastMessageAt;
    @Column(name = "last_message_side", length = 16) private String lastMessageSide;
    @Column(name = "awaiting_seller", nullable = false) @Builder.Default private boolean awaitingSeller = true;

    @Column(name = "closed_at") private OffsetDateTime closedAt;
    @Column(name = "closed_by_user_id") private Long closedByUserId;
    @Column(name = "close_reason", columnDefinition = "TEXT") private String closeReason;

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

    public boolean isClosed() {
        return AppConstant.ENQUIRY_CLOSED.equals(state);
    }
}
