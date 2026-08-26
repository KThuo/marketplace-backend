package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.properties.Property;
import com.hodi.security.TenantScope;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * What the platform earned, and where it stands (M13).
 *
 * <h2>The rate is copied, not referenced</h2>
 *
 * <p>A commission is raised from the rate in force at the moment a listing is marked sold, and that rate is
 * written onto the row. A rate change next quarter must not silently restate what was owed last quarter —
 * and a report that reads a live setting to explain a historical figure is a report nobody can reconcile.
 *
 * <p>The rate itself comes from the configuration layer, so a tenant can carry an agreed rate of their own
 * as a by-exception override without a second table to hold it.
 *
 * <h2>Raising never fails the sale</h2>
 *
 * <p>{@link #raiseFor} swallows its own failures. Marking a listing sold is the seller recording a fact
 * about their business; the platform's invoice is a consequence of it. A commission that could not be
 * written is recoverable — a sale that could not be recorded because of it is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final CommissionRepository repository;
    private final ConfigurationService configs;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CommissionResponse(
            String reference,
            String propertyRef,
            String propertyTitle,
            String tenantName,
            BigDecimal salePrice,
            BigDecimal ratePercent,
            BigDecimal amount,
            String currency,
            OffsetDateTime soldAt,
            String state,
            String invoiceRef,
            OffsetDateTime invoicedAt,
            OffsetDateTime paidAt,
            String waivedReason,
            String note) {}

    public record SettleRequest(
            @NotBlank(message = "Say what you are doing") String action,
            @Size(max = 64) String invoiceRef,
            String note) {}

    public record CommissionTotals(BigDecimal outstanding, BigDecimal paid, String currency) {}

    @Getter
    @Setter
    public static class CommissionListRequest extends PagedDataRequest {
        private String state;
    }

    // ── raising ───────────────────────────────────────────────────────────────

    /**
     * Raises the commission for a completed sale.
     *
     * <p>Idempotent on (listing, sold-at) by unique index: marking the same sale twice is one commission,
     * because it is one sale.
     */
    @Transactional
    public void raiseFor(Property property) {
        try {
            if (property.getSoldAt() == null || property.getPrice() == null) return;
            if (repository.existsByPropertyIdAndSoldAt(property.getId(), property.getSoldAt())) return;

            BigDecimal rate = rateFor(property.getTenantId());
            if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                // A platform charging nothing is a valid configuration, and a row of zero would be noise
                // in every list that exists to show what is owed.
                log.debug("Commission rate is zero — nothing raised for {}", property.getReference());
                return;
            }

            BigDecimal amount = property.getPrice()
                    .multiply(rate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            CommissionRecord record = repository.save(CommissionRecord.builder()
                    .reference(RrnGenerator.generate("CM"))
                    .propertyId(property.getId())
                    .propertyRef(property.getReference())
                    .propertyTitle(property.getTitle())
                    .tenantId(property.getTenantId())
                    .tenantName(property.getTenantName())
                    .salePrice(property.getPrice())
                    .ratePercent(rate)
                    .amount(amount)
                    .currency(property.getCurrency())
                    .soldAt(property.getSoldAt())
                    .state(SellerOpsConstants.COMMISSION_DUE)
                    .createdBy(AuthContext.username())
                    .build());

            audit.record(AppConstant.ACTION_CREATE, "CommissionRecord", record.getId(), null,
                    "%s at %s%% of %s = %s".formatted(record.getPropertyRef(), rate,
                            record.getSalePrice(), amount));
            log.info("Commission {} raised: {} on {}", record.getReference(), amount,
                    record.getPropertyRef());
        } catch (RuntimeException e) {
            // See the class comment: the sale is the fact, the invoice is a consequence of it.
            log.warn("Could not raise a commission for {}: {}", property.getReference(), e.getMessage());
        }
    }

    /**
     * The rate in force for one organisation.
     *
     * <p>Through {@code ConfigurationService}, so a seller with an agreed rate carries it as a by-exception
     * override rather than needing a table of their own.
     */
    private BigDecimal rateFor(Long tenantId) {
        String raw = configs.getString(ConfigKey.COMMISSION_RATE_PERCENT);
        try {
            return new BigDecimal(raw == null || raw.isBlank() ? "0" : raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Commission rate \"{}\" is not a number — nothing raised", raw);
            return BigDecimal.ZERO;
        }
    }

    // ── reading and settling ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<CommissionResponse> list(CommissionListRequest request) {
        Specification<CommissionRecord> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                // A seller sees what they owe; the platform sees everybody's.
                TenantScope.restrict("tenantId"));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "soldAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public CommissionTotals totals() {
        Long tenantId = TenantScope.ownTenantId();
        BigDecimal outstanding = repository.outstandingTotal(tenantId);
        BigDecimal paid = repository.paidTotal(tenantId);
        return new CommissionTotals(
                outstanding == null ? BigDecimal.ZERO : outstanding,
                paid == null ? BigDecimal.ZERO : paid,
                "KES");
    }

    /**
     * Invoiced, paid, or written off.
     *
     * <p>Platform-only: a seller reads what they owe and does not decide whether it has been paid.
     */
    @Transactional
    public CommissionResponse settle(String reference, SettleRequest request) {
        CommissionRecord record = repository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Commission", reference));
        String action = request.action().trim().toUpperCase(Locale.ROOT);

        switch (action) {
            case "INVOICE" -> {
                if (!SellerOpsConstants.COMMISSION_DUE.equals(record.getState())) {
                    throw new HodiException("Only a commission that is due can be invoiced.",
                            HttpStatus.CONFLICT);
                }
                record.setState(SellerOpsConstants.COMMISSION_INVOICED);
                record.setInvoiceRef(blankToNull(request.invoiceRef()));
                record.setInvoicedAt(OffsetDateTime.now());
            }
            case "PAID" -> {
                if (!record.isOutstanding()) {
                    throw new HodiException("That one is already settled.", HttpStatus.CONFLICT);
                }
                record.setState(SellerOpsConstants.COMMISSION_PAID);
                record.setPaidAt(OffsetDateTime.now());
            }
            case "WAIVE" -> {
                if (request.note() == null || request.note().isBlank()) {
                    // The table's own CHECK insists too. Writing off money without saying why is not a
                    // decision anybody can review afterwards.
                    throw new HodiException("Say why it is being written off.", HttpStatus.BAD_REQUEST);
                }
                record.setState(SellerOpsConstants.COMMISSION_WAIVED);
                record.setWaivedReason(request.note().trim());
            }
            default -> throw new HodiException("Invoice it, mark it paid, or write it off.",
                    HttpStatus.BAD_REQUEST);
        }

        record.setNote(blankToNull(request.note()));
        record.setUpdatedBy(AuthContext.username());
        CommissionRecord saved = repository.save(record);
        audit.record(AppConstant.ACTION_UPDATE, "CommissionRecord", saved.getId(), null,
                action + " " + saved.getReference());
        return toResponse(saved);
    }

    private CommissionResponse toResponse(CommissionRecord c) {
        return new CommissionResponse(c.getReference(), c.getPropertyRef(), c.getPropertyTitle(),
                c.getTenantName(), c.getSalePrice(), c.getRatePercent(), c.getAmount(), c.getCurrency(),
                c.getSoldAt(), c.getState(), c.getInvoiceRef(), c.getInvoicedAt(), c.getPaidAt(),
                c.getWaivedReason(), c.getNote());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
