package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Paid placement (M13).
 *
 * <h2>The platform does not take the money here</h2>
 *
 * <p>There is no payments integration, and a "buy now" button that quietly did nothing would be the worst
 * kind of half-feature. So a seller <em>requests</em> a promotion and the platform activates it once they
 * have been paid, however they were paid. The screens say so.
 *
 * <h2>The boost is copied twice, deliberately</h2>
 *
 * <p>From the package onto the promotion, so repricing a package next month does not restate what somebody
 * bought last month. And from the promotion onto the listing, so marketplace search sorts on a column
 * instead of joining — search is the hottest read path on the platform, and a join per result to find out
 * whether somebody paid is a join per result.
 *
 * <p>Both copies have exactly one writer, which is this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionPackageRepository packages;
    private final ListingPromotionRepository promotions;
    private final PropertyRepository properties;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record PackageResponse(
            String reference,
            String code,
            String name,
            String description,
            String placement,
            int durationDays,
            BigDecimal price,
            String currency,
            int boost,
            Integer status,
            String statusFlag) {}

    public record SavePackageRequest(
            @NotBlank(message = "A code is required") @Size(max = 32) String code,
            @NotBlank(message = "A name is required") @Size(max = 120) String name,
            String description,
            @NotBlank(message = "What does it do?") String placement,
            @NotNull(message = "How many days?") Integer durationDays,
            @NotNull(message = "What does it cost?") BigDecimal price,
            Integer boost) {}

    public record PromotionResponse(
            String reference,
            String propertyRef,
            String propertyTitle,
            String tenantName,
            String packageName,
            String placement,
            BigDecimal price,
            String currency,
            int durationDays,
            String state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String cancelledReason,
            String note,
            OffsetDateTime createdAt) {}

    public record RequestPromotionRequest(
            @NotBlank(message = "Which listing?") String propertyReference,
            @NotBlank(message = "Which package?") String packageReference,
            String note) {}

    public record CancelRequest(@NotBlank(message = "Say why") String reason) {}

    @Getter
    @Setter
    public static class PromotionListRequest extends PagedDataRequest {
        private String state;
    }

    // ── the catalogue of packages ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PackageResponse> allPackages() {
        return packages.findAllLive().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PackageResponse> activePackages() {
        return packages.findAllActive().stream().map(this::toResponse).toList();
    }

    @Transactional
    public PackageResponse createPackage(SavePackageRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (packages.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A package with that code already exists");
        }
        PromotionPackage pack = PromotionPackage.builder()
                .reference(RrnGenerator.generate("PK"))
                .code(code)
                .createdBy(AuthContext.username())
                .build();
        applyPackage(pack, request);
        PromotionPackage saved = packages.save(pack);
        audit.record(AppConstant.ACTION_CREATE, "PromotionPackage", saved.getId(), null,
                saved.getCode() + " at " + saved.getPrice());
        return toResponse(saved);
    }

    @Transactional
    public PackageResponse updatePackage(String reference, SavePackageRequest request) {
        PromotionPackage pack = packages.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Package", reference));
        applyPackage(pack, request);
        pack.setStatus(AppConstant.STATUS_EDITED);
        pack.setStatusFlag(AppConstant.FLAG_EDITED);
        pack.setUpdatedBy(AuthContext.username());
        PromotionPackage saved = packages.save(pack);
        // Nothing already sold changes: a promotion copied the price and the boost when it was requested.
        audit.record(AppConstant.ACTION_UPDATE, "PromotionPackage", saved.getId(), null,
                saved.getCode() + " now " + saved.getPrice());
        return toResponse(saved);
    }

    @Transactional
    public PackageResponse setPackageActive(String reference, boolean active) {
        PromotionPackage pack = packages.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Package", reference));
        pack.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        pack.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        pack.setUpdatedBy(AuthContext.username());
        return toResponse(packages.save(pack));
    }

    // ── promotions on listings ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<PromotionResponse> list(PromotionListRequest request) {
        Specification<ListingPromotion> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                TenantScope.restrict("tenantId"));
        var page = promotions.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * A seller asks for a placement.
     *
     * <p>Only for a live listing: promoting a draft would sell somebody a placement for something the
     * marketplace is not showing.
     */
    @Transactional
    public PromotionResponse request(RequestPromotionRequest request) {
        Property property = properties.findByReference(request.propertyReference())
                .orElseThrow(() -> new ResourceNotFoundException("Listing",
                        request.propertyReference()));
        TenantScope.assertAllowed(property.getTenantId());
        if (!AppConstant.LISTING_LIVE.equals(property.getListingState())) {
            throw new HodiException(
                    "Only a live listing can be promoted — a placement for something the marketplace is not "
                            + "showing is a placement for nothing.", HttpStatus.CONFLICT);
        }
        promotions.findLiveForProperty(property.getId()).ifPresent(existing -> {
            throw new HodiException("That listing already has a placement " + (
                    SellerOpsConstants.PROMO_ACTIVE.equals(existing.getState())
                            ? "running." : "waiting to start."), HttpStatus.CONFLICT);
        });

        PromotionPackage pack = packages.findByReference(request.packageReference())
                .orElseThrow(() -> new ResourceNotFoundException("Package",
                        request.packageReference()));
        if (!AppConstant.isLive(pack.getStatus())) {
            throw new HodiException("That package is no longer offered.", HttpStatus.CONFLICT);
        }

        ListingPromotion promotion = promotions.save(ListingPromotion.builder()
                .reference(RrnGenerator.generate("PM"))
                .propertyId(property.getId())
                .propertyRef(property.getReference())
                .propertyTitle(property.getTitle())
                .tenantId(property.getTenantId())
                .tenantName(property.getTenantName())
                .packageId(pack.getId())
                .packageName(pack.getName())
                // Copied, not referenced — see the class comment.
                .placement(pack.getPlacement())
                .boost(pack.getBoost())
                .price(pack.getPrice())
                .currency(pack.getCurrency())
                .durationDays(pack.getDurationDays())
                .state(SellerOpsConstants.PROMO_REQUESTED)
                .note(blankToNull(request.note()))
                .createdBy(AuthContext.username())
                .build());

        audit.record(AppConstant.ACTION_REQUEST, "ListingPromotion", promotion.getId(), null,
                promotion.getPropertyRef() + " asked for " + pack.getName());
        return toResponse(promotion);
    }

    /** The platform starts it, once they have been paid however they were paid. */
    @Transactional
    public PromotionResponse activate(String reference) {
        ListingPromotion promotion = require(reference);
        if (!SellerOpsConstants.PROMO_REQUESTED.equals(promotion.getState())) {
            throw new HodiException("Only a requested placement can be started.", HttpStatus.CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now();
        promotion.setState(SellerOpsConstants.PROMO_ACTIVE);
        promotion.setStartsAt(now);
        promotion.setEndsAt(now.plusDays(promotion.getDurationDays()));
        promotion.setActivatedByUserId(AuthContext.userId());
        promotion.setUpdatedBy(AuthContext.username());
        ListingPromotion saved = promotions.save(promotion);
        applyBoost(saved);
        audit.record(AppConstant.ACTION_ACTIVATE, "ListingPromotion", saved.getId(), null,
                saved.getPropertyRef() + " promoted until " + saved.getEndsAt());
        return toResponse(saved);
    }

    @Transactional
    public PromotionResponse cancel(String reference, CancelRequest request) {
        ListingPromotion promotion = require(reference);
        if (SellerOpsConstants.PROMO_EXPIRED.equals(promotion.getState())) {
            throw new HodiException("That placement has already run its course.", HttpStatus.CONFLICT);
        }
        promotion.setState(SellerOpsConstants.PROMO_CANCELLED);
        promotion.setCancelledReason(request.reason().trim());
        promotion.setUpdatedBy(AuthContext.username());
        ListingPromotion saved = promotions.save(promotion);
        clearBoost(saved.getPropertyId());
        audit.record(AppConstant.ACTION_UPDATE, "ListingPromotion", saved.getId(), null,
                "cancelled: " + request.reason());
        return toResponse(saved);
    }

    /**
     * Expires whatever has run its course.
     *
     * <p>Swept rather than computed at read time: search reads the boost column on every result, and a
     * "…and the end date has not passed" clause on the hottest query on the platform is a clause evaluated
     * per row per search. A promotion that ends at 14:00 and stops being shown at 14:05 is fine; one that
     * costs every buyer's search a comparison is not.
     *
     * @return how many were expired
     */
    @Transactional
    public int expireFinished() {
        List<ListingPromotion> finished = promotions.findFinished(OffsetDateTime.now());
        for (ListingPromotion promotion : finished) {
            promotion.setState(SellerOpsConstants.PROMO_EXPIRED);
            promotions.save(promotion);
            clearBoost(promotion.getPropertyId());
        }
        if (!finished.isEmpty()) log.info("Expired {} promotion(s)", finished.size());
        return finished.size();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void applyBoost(ListingPromotion promotion) {
        properties.findById(promotion.getPropertyId()).ifPresent(property -> {
            property.setPromotionBoost(promotion.getBoost());
            property.setPromotedUntil(promotion.getEndsAt());
            properties.save(property);
        });
    }

    private void clearBoost(Long propertyId) {
        properties.findById(propertyId).ifPresent(property -> {
            property.setPromotionBoost(0);
            property.setPromotedUntil(null);
            properties.save(property);
        });
    }

    private ListingPromotion require(String reference) {
        ListingPromotion promotion = promotions.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Placement", reference));
        TenantScope.assertAllowed(promotion.getTenantId());
        return promotion;
    }

    private void applyPackage(PromotionPackage pack, SavePackageRequest request) {
        pack.setName(request.name().trim());
        pack.setDescription(blankToNull(request.description()));
        pack.setPlacement(request.placement().trim().toUpperCase(Locale.ROOT));
        pack.setDurationDays(request.durationDays());
        pack.setPrice(request.price());
        if (request.boost() != null) pack.setBoost(request.boost());
    }

    private PackageResponse toResponse(PromotionPackage p) {
        return new PackageResponse(p.getReference(), p.getCode(), p.getName(), p.getDescription(),
                p.getPlacement(), p.getDurationDays(), p.getPrice(), p.getCurrency(), p.getBoost(),
                p.getStatus(), p.getStatusFlag());
    }

    private PromotionResponse toResponse(ListingPromotion p) {
        return new PromotionResponse(p.getReference(), p.getPropertyRef(), p.getPropertyTitle(),
                p.getTenantName(), p.getPackageName(), p.getPlacement(), p.getPrice(), p.getCurrency(),
                p.getDurationDays(), p.getState(), p.getStartsAt(), p.getEndsAt(),
                p.getCancelledReason(), p.getNote(), p.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
