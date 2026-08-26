package com.hodi.modules.vendors;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What a vendor offers, and how it reaches the public (M10).
 *
 * <h2>Publication goes through Maker/Checker</h2>
 *
 * <p>A catalogue item is a public price from a third party, shown to somebody in the middle of the largest
 * transaction of their life. A seller's listing is checked before it goes live; there is no argument for
 * holding a conveyancer's quotation to a lower standard.
 *
 * <p>So {@code submit} moves the item to PENDING and raises an approval request, and only
 * {@link CatalogueApprovalHandler} moves it to LIVE. Editing a live item takes it back through the queue,
 * exactly as editing a live listing does — for the same reason: somebody may be acting on the price that
 * was on screen a minute ago.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogueService {

    private final CatalogueItemRepository repository;
    private final VendorService vendors;
    private final VendorCategoryService categories;
    private final ApprovalService approvals;
    private final StorageService storage;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ItemResponse(
            String reference,
            String vendorReference,
            String vendorName,
            String categoryCode,
            String categoryName,
            String title,
            String description,
            BigDecimal price,
            boolean priceFrom,
            String currency,
            String unit,
            String priceNote,
            Short leadTimeDays,
            String counties,
            String imageUrl,
            String state,
            OffsetDateTime publishedAt,
            OffsetDateTime withdrawnAt,
            String withdrawnReason,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt) {}

    public record SaveItemRequest(
            @NotBlank(message = "Give it a name") @Size(max = 255) String title,
            String description,
            @NotBlank(message = "Which category is it?") String categoryCode,
            BigDecimal price,
            Boolean priceFrom,
            @Size(max = 32) String unit,
            String priceNote,
            Short leadTimeDays,
            String counties) {}

    public record WithdrawRequest(
            @NotBlank(message = "Say why it is coming down") String reason) {}

    @Getter
    @Setter
    public static class ItemListRequest extends PagedDataRequest {
        private String state;
        private String categoryCode;
    }

    // ── the vendor's own catalogue ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ItemResponse> mine(ItemListRequest request) {
        VendorProfile vendor = vendors.requireMine();
        Long categoryId = request.getCategoryCode() == null || request.getCategoryCode().isBlank()
                ? null : categories.require(request.getCategoryCode()).getId();
        Specification<CatalogueItem> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("vendorId", vendor.getId()),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("categoryId", categoryId));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional
    public ItemResponse create(SaveItemRequest request) {
        VendorProfile vendor = requireTradingVendor();
        VendorCategory category = categories.requireUsable(request.categoryCode());

        CatalogueItem item = new CatalogueItem();
        item.setReference(freshReference());
        item.setVendorId(vendor.getId());
        item.setTenantId(vendor.getTenantId());
        item.setVendorName(vendor.getBusinessName());
        item.setState(VendorState.ITEM_DRAFT);
        item.setStatus(AppConstant.STATUS_ACTIVE);
        item.setStatusFlag(AppConstant.FLAG_ACTIVE);
        item.setCreatedBy(AuthContext.username());
        apply(item, request, category);

        CatalogueItem saved = repository.save(item);
        audit.record(AppConstant.ACTION_CREATE, "CatalogueItem", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public ItemResponse update(String reference, SaveItemRequest request) {
        CatalogueItem item = requireOwn(reference);
        String before = snapshot(item);
        VendorCategory category = categories.requireUsable(request.categoryCode());
        apply(item, request, category);
        item.setStatus(AppConstant.STATUS_EDITED);
        item.setStatusFlag(AppConstant.FLAG_EDITED);
        item.setUpdatedBy(AuthContext.username());

        /*
         * A live item that is edited comes off the directory until it is approved again.
         *
         * The listing rule, for the listing reason: somebody may be acting on the price that was on screen a
         * minute ago, and deciding that this particular edit is harmless would be trusting the editor's
         * judgement about their own edit.
         */
        boolean wasLive = item.isLive();
        if (wasLive) {
            item.setState(VendorState.ITEM_PENDING);
            item.setPublishedAt(null);
        }

        CatalogueItem saved = repository.save(item);
        if (wasLive) {
            approvals.submit(VendorState.APPROVAL_ENTITY_CATALOGUE_ITEM, saved.getId(),
                    VendorState.APPROVAL_ACTION_PUBLISH, saved.getTenantId(), null,
                    saved.getTitle(), null);
        }
        audit.record(AppConstant.ACTION_UPDATE, "CatalogueItem", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public ItemResponse submit(String reference) {
        CatalogueItem item = requireOwn(reference);
        requireTradingVendor();
        if (item.isLive()) {
            throw new HodiException("That is already published.", HttpStatus.CONFLICT);
        }
        if (!item.hasPricing()) {
            // The database refuses this at publication; saying so here means the vendor finds out while they
            // are looking at the form rather than after somebody has reviewed it.
            throw new HodiException(
                    "Say what it costs — a figure, or a note explaining how it is priced.",
                    HttpStatus.BAD_REQUEST);
        }
        item.setState(VendorState.ITEM_PENDING);
        item.setUpdatedBy(AuthContext.username());
        CatalogueItem saved = repository.save(item);
        approvals.submit(VendorState.APPROVAL_ENTITY_CATALOGUE_ITEM, saved.getId(),
                VendorState.APPROVAL_ACTION_PUBLISH, saved.getTenantId(), null,
                saved.getTitle(), null);
        audit.record(AppConstant.ACTION_REQUEST, "CatalogueItem", saved.getId(), null,
                "submitted for publication");
        return toResponse(saved);
    }

    @Transactional
    public ItemResponse withdraw(String reference, WithdrawRequest request) {
        CatalogueItem item = requireOwn(reference);
        String before = snapshot(item);
        item.setState(VendorState.ITEM_WITHDRAWN);
        item.setWithdrawnAt(OffsetDateTime.now());
        item.setWithdrawnReason(request.reason().trim());
        item.setPublishedAt(null);
        item.setUpdatedBy(AuthContext.username());
        CatalogueItem saved = repository.save(item);
        audit.record(AppConstant.ACTION_UPDATE, "CatalogueItem", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public void archive(String reference) {
        CatalogueItem item = requireOwn(reference);
        if (item.isLive()) {
            throw new HodiException("Take it down before archiving it.", HttpStatus.CONFLICT);
        }
        String before = snapshot(item);
        item.setStatus(AppConstant.STATUS_DELETED);
        item.setStatusFlag(AppConstant.FLAG_DELETED);
        item.setUpdatedBy(AuthContext.username());
        CatalogueItem saved = repository.save(item);
        audit.record(AppConstant.ACTION_DELETE, "CatalogueItem", saved.getId(), before, snapshot(saved));
    }

    @Transactional
    public ItemResponse uploadImage(String reference, MultipartFile file) {
        CatalogueItem item = requireOwn(reference);
        StorageService.Stored stored = storage.store(file, "catalogue");
        item.setImageKey(stored.key());
        item.setUpdatedBy(AuthContext.username());
        CatalogueItem saved = repository.save(item);
        audit.record(AppConstant.ACTION_UPDATE, "CatalogueItem", saved.getId(), null, "image replaced");
        return toResponse(saved);
    }

    // ── what the approval handler calls ───────────────────────────────────────

    @Transactional
    public void applyPublication(Long id) {
        CatalogueItem item = repository.findById(id)
                .orElseThrow(() -> new HodiException("That catalogue item no longer exists.",
                        HttpStatus.CONFLICT));
        item.setState(VendorState.ITEM_LIVE);
        item.setPublishedAt(OffsetDateTime.now());
        item.setWithdrawnAt(null);
        item.setWithdrawnReason(null);
        repository.save(item);
        log.info("Catalogue item {} published", item.getReference());
    }

    @Transactional
    public void applyRefusal(Long id, String reason) {
        CatalogueItem item = repository.findById(id)
                .orElseThrow(() -> new HodiException("That catalogue item no longer exists.",
                        HttpStatus.CONFLICT));
        // Back to the vendor, not withdrawn: a refused submission is a draft with a note against it, and
        // WITHDRAWN would say the vendor took it down themselves.
        item.setState(VendorState.ITEM_DRAFT);
        item.setWithdrawnReason(reason);
        repository.save(item);
        log.info("Catalogue item {} sent back: {}", item.getReference(), reason);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void apply(CatalogueItem item, SaveItemRequest request, VendorCategory category) {
        item.setTitle(request.title().trim());
        item.setDescription(blankToNull(request.description()));
        item.setCategoryId(category.getId());
        item.setCategoryName(category.getName());
        item.setCategoryCode(category.getCode());
        item.setPrice(request.price());
        item.setPriceFrom(Boolean.TRUE.equals(request.priceFrom()));
        item.setUnit(blankToNull(request.unit()));
        item.setPriceNote(blankToNull(request.priceNote()));
        item.setLeadTimeDays(request.leadTimeDays());
        item.setCounties(blankToNull(request.counties()));
    }

    /**
     * The item, if it is this vendor's.
     *
     * <p>By vendor rather than by {@code TenantScope}: a vendor's catalogue is theirs individually, and the
     * organisation exists to hold it rather than to be shared. Platform staff reach these rows through the
     * approval queue, which is where a decision about somebody else's item belongs.
     */
    private CatalogueItem requireOwn(String reference) {
        CatalogueItem item = repository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Catalogue item", reference));
        VendorProfile vendor = vendors.requireMine();
        if (!item.getVendorId().equals(vendor.getId())) {
            throw new HodiException("That is not yours.", HttpStatus.FORBIDDEN);
        }
        return item;
    }

    private VendorProfile requireTradingVendor() {
        VendorProfile vendor = vendors.requireMine();
        if (!vendor.isTrading()) {
            throw new HodiException(
                    "Your registration is not approved yet, so nothing can be published.",
                    HttpStatus.FORBIDDEN);
        }
        return vendor;
    }

    private String freshReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("CI");
            if (!repository.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a reference — try again.", HttpStatus.CONFLICT);
    }

    ItemResponse toResponse(CatalogueItem i) {
        return new ItemResponse(
                i.getReference(), vendorReferenceOf(i), i.getVendorName(),
                i.getCategoryCode(), i.getCategoryName(), i.getTitle(), i.getDescription(),
                i.getPrice(), i.isPriceFrom(), i.getCurrency(), i.getUnit(), i.getPriceNote(),
                i.getLeadTimeDays(), i.getCounties(), storage.urlFor(i.getImageKey()),
                i.getState(), i.getPublishedAt(), i.getWithdrawnAt(), i.getWithdrawnReason(),
                i.getStatus(), i.getStatusFlag(), i.getCreatedAt());
    }

    private String vendorReferenceOf(CatalogueItem item) {
        return vendors.referenceOf(item.getVendorId());
    }

    private static String snapshot(CatalogueItem i) {
        return "{\"reference\":\"%s\",\"title\":\"%s\",\"state\":\"%s\",\"price\":%s}".formatted(
                i.getReference(), i.getTitle(), i.getState(), String.valueOf(i.getPrice()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
