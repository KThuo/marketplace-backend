package com.hodi.modules.tours;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One walkthrough video of a house, a kind of home, or a whole project.
 *
 * <p>Holds the provider's id, never the pasted link — see {@link YouTubeLinks} for why. The iframe and the
 * thumbnail addresses are built from it on read, on hosts the code chooses.
 *
 * <p>Owned polymorphically like {@code media_assets}, with the same stand-ins for a foreign key: a CHECK on
 * the owner types and the fact that nothing in this schema hard-deletes.
 */
@Entity
@Table(name = "virtual_tours")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VirtualTour {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_type", nullable = false, length = 32) private String ownerType;
    @Column(name = "owner_id", nullable = false) private Long ownerId;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(nullable = false, length = 16)
    @Builder.Default private String provider = VirtualTourService.PROVIDER_YOUTUBE;
    @Column(name = "video_id", nullable = false, length = 32) private String videoId;
    @Column(length = 160) private String title;
    @Column(name = "start_seconds", nullable = false) @Builder.Default private int startSeconds = 0;

    /** The rooms, in the order the video reaches them. Never null; an untagged tour is an empty list. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default private List<TourChapters.Chapter> chapters = new ArrayList<>();

    @Column(name = "sort_order", nullable = false) @Builder.Default private int sortOrder = 0;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
