package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A licensed auctioneer (M6).
 *
 * <p>A record, not an actor: auctioneers do not sign in here. They conduct the sale in a room, and what the
 * platform needs is a licence number it can put on a public notice and check has not lapsed.
 */
@Entity
@Table(name = "auctioneers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Auctioneer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(nullable = false, length = 255) private String name;
    @Column(name = "firm_name", length = 255) private String firmName;

    @Column(name = "licence_number", length = 64) private String licenceNumber;
    @Column(name = "licence_expires_on") private LocalDate licenceExpiresOn;

    @Column(name = "contact_name", length = 160) private String contactName;
    @Column(name = "contact_email", length = 128) private String contactEmail;
    @Column(name = "contact_phone", length = 32) private String contactPhone;
    @Column(columnDefinition = "TEXT") private String counties;

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

    /**
     * Licensed today.
     *
     * <p>A sale conducted by somebody whose licence has lapsed can be set aside, so this is checked when a
     * lot is published rather than only when the auctioneer is added — the licence expires on its own.
     */
    public boolean isLicensed() {
        return AppConstant.isLive(status)
                && (licenceExpiresOn == null || !licenceExpiresOn.isBefore(LocalDate.now()));
    }
}
