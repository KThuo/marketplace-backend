package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * Somebody asking to be allowed to bid (M6).
 *
 * <p>Not a bid. The bidding happens in a room with an auctioneer, and a platform that implied otherwise
 * would be making a promise about a legal process it does not conduct. What this records is the request and
 * the auctioneer's answer to it.
 *
 * <p>{@link #depositConfirmed} is deliberately a claim until somebody says otherwise: the platform does not
 * take the deposit — the auctioneer does — so a bidder's word about having lodged one is exactly that.
 */
@Entity
@Table(name = "auction_registrations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuctionRegistration {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "lot_id", nullable = false) private Long lotId;
    @Column(name = "lot_reference", nullable = false, length = 16) private String lotReference;
    @Column(name = "lot_title", length = 255) private String lotTitle;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "bidder_name", length = 160) private String bidderName;
    @Column(name = "bidder_email", length = 128) private String bidderEmail;
    @Column(name = "bidder_phone", length = 32) private String bidderPhone;
    @Column(name = "id_number", length = 64) private String idNumber;

    @Column(name = "deposit_reference", length = 64) private String depositReference;
    @Column(name = "deposit_confirmed", nullable = false) @Builder.Default private boolean depositConfirmed = false;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.BIDDER_REGISTERED;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;

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

    public boolean isLive() {
        return AppConstant.BIDDER_REGISTERED.equals(state) || AppConstant.BIDDER_APPROVED.equals(state);
    }
}
