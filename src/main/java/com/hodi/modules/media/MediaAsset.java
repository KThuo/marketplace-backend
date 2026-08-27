package com.hodi.modules.media;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One photograph, floor plan or brochure belonging to something that is not a listing.
 *
 * <p>{@code property_media} is the only many-images-to-one-thing table this schema had, and it is welded to
 * {@code properties} — a non-null foreign key, a partial unique index keyed on it, a cover-image cache on the
 * parent, and a publish gate that counts it. Generalising it would have meant changing all four in a change
 * about none of them.
 *
 * <p>So {@code PROPERTY} is deliberately not an allowed {@link #ownerType}. One table per question: a
 * listing's gallery is in {@code property_media}, everything else is here, and nobody has to work out which to
 * read. The cost is two media tables, and it is the honest price of not rewriting a working one.
 *
 * <h2>A polymorphic owner has no foreign key</h2>
 *
 * <p>What stands in for it: a CHECK on the owner types, and the fact that nothing in this schema hard-deletes.
 * Every archive is a status, so an asset whose owner has gone is an asset nothing queries rather than a
 * dangling row pointing at a vanished id.
 */
@Entity
@Table(name = "media_assets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaAsset {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_type", nullable = false, length = 32) private String ownerType;
    @Column(name = "owner_id", nullable = false) private Long ownerId;

    /** Cached from the owner so an access check is one read rather than a walk up the tree. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "media_kind", nullable = false, length = 24)
    @Builder.Default private String mediaKind = AppConstant.MEDIA_KIND_PHOTO;

    /** A storage key, never a URL. StorageService.urlFor recomputes the address on every read. */
    @Column(name = "storage_key", nullable = false, length = 512) private String storageKey;
    @Column(name = "content_type", length = 64) private String contentType;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(length = 255) private String caption;
    @Column(name = "sort_order", nullable = false) @Builder.Default private int sortOrder = 0;
    @Column(name = "is_primary", nullable = false) @Builder.Default private boolean primary = false;

    /**
     * Whether a buyer may see it. Marketing photographs are public; a site photograph a lender keeps for its
     * own file is not. Never a title deed either way — those are vault documents, encrypted and ACL-gated.
     */
    @Column(name = "public_visible", nullable = false) @Builder.Default private boolean publicVisible = true;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
