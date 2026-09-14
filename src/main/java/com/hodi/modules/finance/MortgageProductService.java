package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.finance.FinanceDtos.ProductListRequest;
import com.hodi.modules.finance.FinanceDtos.ProductResponse;
import com.hodi.modules.finance.FinanceDtos.SaveProductRequest;
import com.hodi.modules.banks.Bank;
import com.hodi.modules.banks.BankRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * A bank's products (M3, BRD FR025–FR030).
 *
 * <h2>Visibility is the institution, and it comes off the principal</h2>
 *
 * <p>{@code TenantScope} governs *sellers*; a bank's own rows are scoped by the institution on their
 * profile, exactly as {@code PartnershipService} does it. Platform staff see every institution's products —
 * they administer the catalogue — and a caller with neither an institution nor platform standing sees none,
 * which is the honest answer for a buyer who reached this endpoint.
 *
 * <h2>Publishing is a separate act</h2>
 *
 * <p>{@code MORTGAGE_PRODUCTS_PUBLISH} is its own permission and this service is where it means something:
 * a product goes in front of the public through {@link #setPublished}, never as a side effect of an edit.
 * Editing a published product leaves it published — the rate on offer changes, which is the point — but the
 * audit trail records the edit, so "when did this rate change" is answerable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MortgageProductService {

    private static final String REFERENCE_PREFIX = "MP";

    private final MortgageProductRepository repository;
    private final BankRepository institutions;
    private final AuditService audit;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> list(ProductListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<MortgageProduct> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("productType", blankToNull(request.getProductType())),
                publishedIs(request.getPublished()),
                ownInstitution(caller));

        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductResponse find(String hashId) {
        return toResponse(loadVisible(hashId));
    }

    // ── writes ────────────────────────────────────────────────────────────────

    @Transactional
    public ProductResponse create(SaveProductRequest request) {
        UserPrincipal caller = AuthContext.require();
        Long institutionId = caller.getInstitutionId();
        if (institutionId == null) {
            // Platform staff administering the catalogue still have to say whose product it is, and there is
            // nowhere in this request to say it. Refused with the sentence rather than silently filed under
            // nobody — a product with no bank is a rate a buyer cannot act on.
            throw new HodiException(
                    "Only a bank's own staff can create a product. Ask the institution to add it.",
                    HttpStatus.FORBIDDEN);
        }
        Bank institution = institutions.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException("Institution", institutionId));

        MortgageProduct product = MortgageProduct.builder()
                .institutionId(institution.getId())
                .institutionName(institution.getName())
                .reference(nextReference())
                .createdBy(caller.getUsername())
                .updatedBy(caller.getUsername())
                .build();
        apply(product, request);

        MortgageProduct saved = repository.save(product);
        audit.record(AppConstant.ACTION_CREATE, "MortgageProduct", saved.getId(), null, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public ProductResponse update(String hashId, SaveProductRequest request) {
        MortgageProduct product = loadOwn(hashId);
        ProductResponse before = toResponse(product);

        apply(product, request);
        product.setUpdatedBy(AuthContext.username());
        product.setStatus(AppConstant.STATUS_EDITED);
        product.setStatusFlag(AppConstant.FLAG_EDITED);

        MortgageProduct saved = repository.save(product);
        audit.record(AppConstant.ACTION_UPDATE, "MortgageProduct", saved.getId(), before, toResponse(saved));
        return toResponse(saved);
    }

    /**
     * Puts a product in front of the public, or takes it back off.
     *
     * <p>{@code published_at} is stamped on the way up and deliberately left in place on the way down: the
     * CHECK only requires it while published, and keeping it answers "when was this last on offer" without a
     * second column.
     */
    @Transactional
    public ProductResponse setPublished(String hashId, boolean publish) {
        MortgageProduct product = loadOwn(hashId);
        if (product.isPublished() == publish) return toResponse(product);

        if (publish && !AppConstant.isLive(product.getStatus())) {
            throw new HodiException("Activate the product before publishing it.", HttpStatus.BAD_REQUEST);
        }

        product.setPublished(publish);
        if (publish) product.setPublishedAt(OffsetDateTime.now());
        product.setUpdatedBy(AuthContext.username());

        MortgageProduct saved = repository.save(product);
        audit.record(publish ? AppConstant.AUDIT_PRODUCT_PUBLISH : AppConstant.AUDIT_PRODUCT_WITHDRAW,
                "MortgageProduct", saved.getId(), null,
                saved.getReference() + " " + (publish ? "published" : "withdrawn")
                        + " at " + saved.getInterestRate() + "%");
        return toResponse(saved);
    }

    @Transactional
    public ProductResponse setActive(String hashId, boolean active, String reason) {
        MortgageProduct product = loadOwn(hashId);
        product.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        product.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        product.setDeactivationReason(active ? null : reason);
        product.setUpdatedBy(AuthContext.username());
        // A product nobody may offer must not stay on the marketplace. Deactivating it takes it down as
        // well, rather than leaving a switched-off product quietly on offer.
        if (!active) product.setPublished(false);

        MortgageProduct saved = repository.save(product);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "MortgageProduct", saved.getId(), null, reason);
        return toResponse(saved);
    }

    @Transactional
    public void archive(String hashId) {
        MortgageProduct product = loadOwn(hashId);
        product.setStatus(AppConstant.STATUS_DELETED);
        product.setStatusFlag(AppConstant.FLAG_DELETED);
        product.setPublished(false);
        product.setUpdatedBy(AuthContext.username());
        repository.save(product);
        audit.record(AppConstant.ACTION_DELETE, "MortgageProduct", product.getId(), null,
                product.getReference());
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Readable by this caller: their institution's, or anybody's if they are platform staff. */
    private MortgageProduct loadVisible(String hashId) {
        MortgageProduct product = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Product", hashId));
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return product;
        if (!product.getInstitutionId().equals(caller.getInstitutionId())) {
            throw new ResourceNotFoundException("Product", hashId);
        }
        return product;
    }

    /**
     * Writable by this caller — their own institution's, and platform staff are <em>not</em> exempt.
     *
     * <p>The one place the platform's reach deliberately stops. Reading every bank's catalogue is oversight;
     * editing another organisation's published rate is not, and a support administrator with a typo could put
     * a number in front of the public that the bank never agreed to.
     */
    private MortgageProduct loadOwn(String hashId) {
        MortgageProduct product = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Product", hashId));
        Long callerInstitution = AuthContext.institutionId();
        if (callerInstitution == null || !product.getInstitutionId().equals(callerInstitution)) {
            throw new HodiException("That product belongs to another institution.", HttpStatus.FORBIDDEN);
        }
        return product;
    }

    private Specification<MortgageProduct> ownInstitution(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        Long institutionId = caller.getInstitutionId();
        // A caller with no institution and no platform standing is party to no bank's catalogue.
        if (institutionId == null) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> cb.equal(root.get("institutionId"), institutionId);
    }

    private Specification<MortgageProduct> publishedIs(Boolean published) {
        if (published == null) return null;
        return (root, query, cb) -> cb.equal(root.get("published"), published);
    }

    private void apply(MortgageProduct product, SaveProductRequest request) {
        product.setName(request.name().trim());
        product.setDescription(blankToNull(request.description()));
        product.setProductType(oneOf(request.productType(), AppConstant.PRODUCT_MORTGAGE,
                Set.of(AppConstant.PRODUCT_MORTGAGE, AppConstant.PRODUCT_CONSTRUCTION,
                        AppConstant.PRODUCT_PLOT_PURCHASE, AppConstant.PRODUCT_EQUITY_RELEASE,
                        AppConstant.PRODUCT_REFINANCE)));
        product.setInterestRate(request.interestRate());
        product.setRateType(oneOf(request.rateType(), AppConstant.RATE_FIXED,
                Set.of(AppConstant.RATE_FIXED, AppConstant.RATE_VARIABLE, AppConstant.RATE_REDUCING)));

        product.setMinAmount(request.minAmount());
        product.setMaxAmount(request.maxAmount());
        if (request.minTermMonths() != null) product.setMinTermMonths(request.minTermMonths());
        if (request.maxTermMonths() != null) product.setMaxTermMonths(request.maxTermMonths());

        if (request.maxLtvPercent() != null) product.setMaxLtvPercent(request.maxLtvPercent());
        if (request.minDepositPercent() != null) product.setMinDepositPercent(request.minDepositPercent());
        if (request.processingFeePercent() != null) {
            product.setProcessingFeePercent(request.processingFeePercent());
        }
        if (request.insurancePercent() != null) product.setInsurancePercent(request.insurancePercent());
        product.setOtherFeesNote(blankToNull(request.otherFeesNote()));

        product.setMinMonthlyIncome(request.minMonthlyIncome());
        product.setMaxDtiPercent(request.maxDtiPercent());
        product.setEligibilityNotes(blankToNull(request.eligibilityNotes()));
        product.setRequiredDocuments(blankToNull(request.requiredDocuments()));

        validate(product);
    }

    /**
     * The database's CHECKs, said in sentences.
     *
     * <p>The constraints are what make these true; this is what makes the refusal readable. The bank who has
     * typed the term range backwards should be told which field, not shown a constraint name.
     */
    private void validate(MortgageProduct product) {
        if (product.getMaxTermMonths() < product.getMinTermMonths()) {
            throw new HodiException("The longest term cannot be shorter than the shortest.",
                    HttpStatus.BAD_REQUEST);
        }
        if (product.getMinAmount() != null && product.getMaxAmount() != null
                && product.getMaxAmount().compareTo(product.getMinAmount()) < 0) {
            throw new HodiException("The most you will lend cannot be less than the least.",
                    HttpStatus.BAD_REQUEST);
        }
        if (product.getInterestRate().compareTo(BigDecimal.ZERO) <= 0
                || product.getInterestRate().compareTo(new BigDecimal("100")) >= 0) {
            throw new HodiException("An interest rate has to be between 0 and 100.", HttpStatus.BAD_REQUEST);
        }
        if (product.getMaxLtvPercent().compareTo(BigDecimal.ZERO) <= 0
                || product.getMaxLtvPercent().compareTo(new BigDecimal("100")) > 0) {
            throw new HodiException("Loan-to-value has to be between 0 and 100 percent.",
                    HttpStatus.BAD_REQUEST);
        }
        if (product.getMinDepositPercent().compareTo(BigDecimal.ZERO) < 0
                || product.getMinDepositPercent().compareTo(new BigDecimal("100")) >= 0) {
            throw new HodiException("A minimum deposit has to be under 100 percent.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a product reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    ProductResponse toResponse(MortgageProduct p) {
        return new ProductResponse(
                HashIdUtil.encodeId(p.getId()),
                p.getReference(),
                p.getInstitutionId() == null ? null : HashIdUtil.encodeId(p.getInstitutionId()),
                p.getInstitutionName(),
                p.getName(),
                p.getDescription(),
                p.getProductType(),
                p.getCurrency(),
                p.getMinAmount(),
                p.getMaxAmount(),
                p.getMinTermMonths(),
                p.getMaxTermMonths(),
                p.getInterestRate(),
                p.getRateType(),
                p.getMaxLtvPercent(),
                p.getMinDepositPercent(),
                p.getProcessingFeePercent(),
                p.getInsurancePercent(),
                p.getOtherFeesNote(),
                p.getMinMonthlyIncome(),
                p.getMaxDtiPercent(),
                p.getEligibilityNotes(),
                p.getRequiredDocuments(),
                p.isPublished(),
                p.getPublishedAt(),
                p.getStatus(),
                p.getStatusFlag(),
                p.getCreatedAt(),
                p.getCreatedBy());
    }

    private static String oneOf(String value, String fallback, Set<String> allowed) {
        String candidate = value == null ? "" : value.trim().toUpperCase();
        return allowed.contains(candidate) ? candidate : fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
