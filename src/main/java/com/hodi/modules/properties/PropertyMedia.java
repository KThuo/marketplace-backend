package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One photograph of one property.
 *
 * <p>Holds the storage <em>key</em>, never a URL: a URL expires, a CDN appears, a bucket moves, and a row
 * holding one freezes today's deployment into the data. {@code StorageService.urlFor} recomputes the address
 * on read, which is the same rule avatars and brand logos already follow.
 *
 * <p>{@code tenantId} is copied here as well as onto the property. It is not redundancy for its own sake: the
 * storage key is tenant-prefixed, and a media row that could not be scoped without joining its property would
 * make "export or delete everything belonging to this seller" a two-table problem.
 */
@Entity
@Table(name = "property_media")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PropertyMedia {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;

    @Column(name = "storage_key", nullable = false, length = 512) private String storageKey;
    @Column(name = "content_type", length = 64) private String contentType;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(length = 255) private String caption;

    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 0;

    /** The card's photograph. Exactly one per property, enforced by a partial unique index. */
    @Column(name = "is_primary", nullable = false) @Builder.Default private boolean primary = false;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
