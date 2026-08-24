package com.hodi.modules.buyerportal;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One listing on one person's shortlist.
 *
 * <p>Keyed on the person, not the profile: see {@code SavedListingService} and the migration header. There
 * is no {@code status} column here and that is deliberate — un-saving deletes the row. Everything else in
 * this schema carries the soft lifecycle because somebody later asks "what happened to that record"; nobody
 * asks that about a bookmark, and an archived row would keep colliding with the unique index the next time
 * the same buyer saved the same house.
 *
 * <p>The {@code *Snapshot} fields are a snapshot in the strict sense: what the listing said when it was
 * saved. The card renders live figures by joining to {@code properties}; these are the fallback for a
 * listing that has since gone off the market, so the shortlist can say which one it was rather than showing
 * an empty row.
 */
@Entity
@Table(name = "saved_listings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SavedListing {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "title_snapshot", length = 255) private String titleSnapshot;
    @Column(name = "price_snapshot", precision = 15, scale = 2) private BigDecimal priceSnapshot;
    @Column(name = "currency_snapshot", length = 3) private String currencySnapshot;
    @Column(name = "town_snapshot", length = 64) private String townSnapshot;
    @Column(name = "image_snapshot", length = 512) private String imageSnapshot;

    @Column(columnDefinition = "TEXT") private String note;

    @Column(name = "saved_at", nullable = false)
    @Builder.Default private OffsetDateTime savedAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 64) private String createdBy;
}
