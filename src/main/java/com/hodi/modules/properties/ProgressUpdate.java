package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A post about how a build is going (M8, BRD FR087–FR089).
 *
 * <p>For off-plan and under-construction property, where the gap between listing and completion is measured
 * in years and a buyer who has paid a deposit wants to see the slab go down.
 *
 * <p>{@link #reportedOn} is when the work happened, not when the post was written — a developer catching up
 * on three months of photographs in one sitting should produce a timeline in the order of the work.
 *
 * <p>The photograph goes through the ordinary media store, not the vault. This is marketing, and the
 * distinction between the two stores is the point of having two.
 *
 * <h2>Two kinds of subject</h2>
 *
 * <p>Originally a listing's diary; now also a development's. The table is one because the timeline is one —
 * see {@link #propertyId}. The class name still says listing, which is now half the truth, and renaming it
 * would touch every import for no behavioural gain; the column comments carry the rest.
 */
@Entity
@Table(name = "listing_progress_updates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgressUpdate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Exactly one subject: a listing or a development. The database says so — ck_progress_subject — because a
     * row with both, or with neither, is a row no timeline knows where to show.
     *
     * The columns stayed on this table rather than moving to a second one. A development's build is the thing
     * buyers actually follow, and a separate table would have made the timeline component a client-side merge
     * of two arrays sorted by date, with every screen having to know which table its subject lives in.
     */
    @Column(name = "property_id") private Long propertyId;
    @Column(name = "development_id") private Long developmentId;

    /** Narrows a development update to part of the build — "Block B roofed". Usually null. */
    @Column(name = "phase_id") private Long phaseId;
    @Column(name = "unit_id") private Long unitId;

    /*
     * Who may see it, cached from the subject so the access check is one read rather than a walk up the tree.
     * Not exactly-one: a listing update has a tenant, a bank's own project has an institution, and a
     * platform-owned project has neither.
     */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(nullable = false, length = 180) private String title;
    @Column(columnDefinition = "TEXT") private String body;

    @Column(name = "percent_complete") private Short percentComplete;
    /*
     * The stage, twice over, and both are wanted.
     *
     * milestone_code is the stable identifier from progress_milestone_configs, which is what lets a screen
     * group by stage — free text made "Slab", "slab" and "Slab poured" three different milestones. The free
     * text stays because eight names chosen by us are not the eight every developer uses, and refusing an
     * unlisted stage would make the field worse than it is today.
     */
    @Column(name = "milestone_code", length = 32) private String milestoneCode;
    @Column(length = 64) private String milestone;

    @Column(name = "reported_on", nullable = false)
    @Builder.Default private LocalDate reportedOn = LocalDate.now();

    /** Cover photograph: a cache over media_assets, so rows written before that table existed still render. */
    @Column(name = "image_key", length = 512) private String imageKey;
    /** What lets a feed card say "4 photos" without a query per row. */
    @Column(name = "image_count", nullable = false) @Builder.Default private Integer imageCount = 0;

    /**
     * Who this was written for.
     *
     * <p>PUBLIC goes on the project's page and the site's feed: words and photographs, meant to interest
     * somebody in the build. STAKEHOLDERS stays in the workspace: the percentage, the stage, the phase.
     *
     * <p>Defaults to PUBLIC because every row that existed before this column was a listing's progress update,
     * already published on the listing's own page. Defaulting the other way would have withdrawn content
     * sellers had published, silently.
     */
    @Column(nullable = false, length = 24)
    @Builder.Default private String audience = AppConstant.AUDIENCE_PUBLIC;

    @Column(nullable = false) @Builder.Default private boolean published = false;
    @Column(name = "published_at") private OffsetDateTime publishedAt;

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
