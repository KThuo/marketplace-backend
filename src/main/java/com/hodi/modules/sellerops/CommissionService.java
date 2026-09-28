package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.agents.AgentProfile;
import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.properties.Property;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.tenant.TenantContext;
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
 * What a sale pays the bank, and the agent who brought the buyer (M13, extended by the commissions plan).
 *
 * <h2>One line per payee, against the booking</h2>
 *
 * <p>A completed booking raises up to two lines: the platform's, at the development's bank rate, and an
 * agent's, when the booking names who introduced the buyer and the agent rate is above zero. A house has no
 * development and takes the platform default — resolved for the <em>seller's</em> tenant, so a seller with
 * an agreed rate carries it as an override without a table of their own.
 *
 * <h2>The rate is copied, not referenced</h2>
 *
 * <p>The rate in force at the moment the sale completes is written onto the line, as is who bears the
 * agent's fee. A rate change next quarter must not silently restate what was owed last quarter — and a
 * report that reads a live setting to explain a historical figure is a report nobody can reconcile.
 *
 * <h2>Raising never fails the sale</h2>
 *
 * <p>{@link #raiseFor} swallows its own failures. Completing a booking is the seller recording a fact about
 * their business; what it pays is a consequence of it. A line that could not be written is recoverable — a
 * sale that could not be recorded because of it is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final CommissionRepository repository;
    private final com.hodi.modules.disbursements.DisbursementRepository disbursements;
    private final AgentProfileRepository agents;
    private final ConfigurationService configs;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CommissionResponse(
            String reference,
            /** PLATFORM or AGENT. */
            String payeeKind,
            String agentName,
            String bookingId,
            String bookingRef,
            String developmentName,
            /** SELLER or BANK: whose money it comes out of when the bank settles the sale. */
            String paidBy,
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
            String note,
            /** The transfer that paid it, when the bank paid it through a settlement. */
            String disbursementId,
            String disbursementReference) {}

    public record SettleRequest(
            @NotBlank(message = "Say what you are doing") String action,
            @Size(max = 64) String invoiceRef,
            String note) {}

    /** What is owed and what has been paid, in all and by whom it is earned. */
    public record CommissionTotals(BigDecimal outstanding, BigDecimal paid, String currency,
                                   BigDecimal bankOutstanding, BigDecimal bankPaid,
                                   BigDecimal agentOutstanding, BigDecimal agentPaid) {}

    @Getter
    @Setter
    public static class CommissionListRequest extends PagedDataRequest {
        private String state;
        /** PLATFORM or AGENT. */
        private String payeeKind;
        /** The period the sale completed in, inclusive, as dates. */
        private java.time.LocalDate from;
        private java.time.LocalDate to;
    }

    /** The rates a sale is raised at, resolved once. */
    record Schedule(BigDecimal bankRate, BigDecimal agentRate, String agentPaidBy) {}

    // ── raising ───────────────────────────────────────────────────────────────

    /**
     * Raises what a completed sale pays: the platform's line, and the agent's where the booking names one.
     *
     * <p>Idempotent per (booking, payee) by unique index: completing the same sale twice is one sale.
     *
     * @param booking     the sale, COMPLETED
     * @param home        the unit or house it sold — for the names that are copied onto the line
     * @param development the project above a unit, whose schedule applies; null for a house
     */
    @Transactional
    public void raiseFor(UnitBooking booking, Property home, Development development) {
        try {
            if (!AppConstant.BOOKING_COMPLETED.equals(booking.getState()) || booking.getPriceAgreed() == null) return;
            Schedule schedule = scheduleFor(development, booking.getTenantId(),
                    development != null ? development.getTenantName() : home.getTenantName());
            OffsetDateTime soldAt = booking.getCompletedAt() != null ? booking.getCompletedAt() : OffsetDateTime.now();

            raiseLine(booking, home, development, SellerOpsConstants.PAYEE_PLATFORM, null,
                    schedule.bankRate(), Development.AGENT_PAID_BY_SELLER, soldAt);

            if (booking.getIntroducedByAgentId() != null) {
                AgentProfile agent = agents.findById(booking.getIntroducedByAgentId()).orElse(null);
                if (agent == null) {
                    log.warn("Booking {} names agent {} who does not exist — no agent line",
                            booking.getReference(), booking.getIntroducedByAgentId());
                } else {
                    raiseLine(booking, home, development, SellerOpsConstants.PAYEE_AGENT, agent,
                            schedule.agentRate(), schedule.agentPaidBy(), soldAt);
                }
            }
        } catch (RuntimeException e) {
            // See the class comment: the sale is the fact, what it pays is a consequence of it.
            log.warn("Could not raise commission for {}: {}", booking.getReference(), e.getMessage());
        }
    }

    private void raiseLine(UnitBooking booking, Property home, Development development, String payeeKind,
                           AgentProfile agent, BigDecimal rate, String paidBy, OffsetDateTime soldAt) {
        if (repository.existsByBookingIdAndPayeeKindAndStatusNot(booking.getId(), payeeKind,
                AppConstant.STATUS_DELETED)) return;
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            // A rate of nothing is a valid arrangement, and a row of zero would be noise in every list that
            // exists to show what is owed.
            log.debug("{} rate is zero — nothing raised for {}", payeeKind, booking.getReference());
            return;
        }
        BigDecimal amount = booking.getPriceAgreed().multiply(rate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        CommissionRecord record = repository.save(CommissionRecord.builder()
                .reference(RrnGenerator.generate("CM"))
                .bookingId(booking.getId())
                .bookingRef(booking.getReference())
                .developmentId(development == null ? null : development.getId())
                .developmentName(development == null ? null : development.getName())
                .payeeKind(payeeKind)
                .agentProfileId(agent == null ? null : agent.getId())
                .agentName(agent == null ? null : agent.getFullName())
                .paidBy(paidBy)
                .propertyId(home.getId())
                .propertyRef(home.getReference())
                .propertyTitle(home.getUnitLabel() != null ? home.getUnitLabel() : home.getTitle())
                .tenantId(booking.getTenantId())
                .tenantName(development != null ? development.getTenantName() : home.getTenantName())
                .salePrice(booking.getPriceAgreed())
                .ratePercent(rate)
                .amount(amount)
                .currency(booking.getCurrency() == null ? "KES" : booking.getCurrency())
                .soldAt(soldAt)
                .state(SellerOpsConstants.COMMISSION_DUE)
                .createdBy(AuthContext.username())
                .build());

        audit.record(AppConstant.ACTION_CREATE, "CommissionRecord", record.getId(), null,
                "%s %s at %s%% of %s = %s%s".formatted(payeeKind, booking.getReference(), rate,
                        record.getSalePrice(), amount, agent == null ? "" : " to " + agent.getFullName()));
        log.info("Commission {} raised: {} {} on {}", record.getReference(), payeeKind, amount,
                booking.getReference());
    }

    /**
     * The rates a sale is raised at: the development's own where it names them, the platform's defaults
     * otherwise — resolved for the seller's tenant, so a seller's agreed override is theirs and not the
     * caller's.
     */
    Schedule scheduleFor(Development development, Long sellerTenantId, String sellerTenantName) {
        BigDecimal bank = development == null ? null : development.getBankCommissionPercent();
        BigDecimal agent = development == null ? null : development.getAgentCommissionPercent();
        if (bank == null) bank = defaultRate(ConfigKey.COMMISSION_RATE_PERCENT, sellerTenantId, sellerTenantName);
        if (agent == null) agent = defaultRate(ConfigKey.AGENT_COMMISSION_RATE_PERCENT, sellerTenantId, sellerTenantName);
        return new Schedule(bank, agent,
                development == null ? Development.AGENT_PAID_BY_SELLER : development.agentFeeBorneBy());
    }

    private BigDecimal defaultRate(ConfigKey key, Long tenantId, String tenantName) {
        String raw = tenantId == null ? configs.getString(key)
                : TenantContext.runAs(tenantId, tenantName, () -> configs.getString(key));
        try {
            return new BigDecimal(raw == null || raw.isBlank() ? "0" : raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{} \"{}\" is not a number — nothing raised", key.getKey(), raw);
            return BigDecimal.ZERO;
        }
    }

    /**
     * An agent's own lines, by their id rather than by tenant: an agent's line sits on the seller's tenant,
     * which is not theirs, and this is how they see it anyway.
     */
    @Transactional(readOnly = true)
    public PagedResponse<CommissionResponse> forAgent(Long agentProfileId, PagedDataRequest request) {
        Specification<CommissionRecord> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("agentProfileId", agentProfileId),
                SearchSpecs.eq("payeeKind", SellerOpsConstants.PAYEE_AGENT));
        var page = repository.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "soldAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    /** Every line on one sale, for the booking's own screen. */
    @Transactional(readOnly = true)
    public java.util.List<CommissionResponse> forBooking(Long bookingId) {
        return repository.findByBookingIdAndStatusNotOrderByPayeeKind(bookingId, AppConstant.STATUS_DELETED)
                .stream().map(this::toResponse).toList();
    }

    // ── reading and settling ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<CommissionResponse> list(CommissionListRequest request) {
        Specification<CommissionRecord> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("payeeKind", blankToNull(request.getPayeeKind())),
                SearchSpecs.betweenDays("soldAt", request.getFrom(), request.getTo()),
                // A seller sees what they owe; the platform sees everybody's.
                TenantScope.restrict("tenantId"));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "soldAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public CommissionTotals totals() {
        Long tenantId = TenantScope.ownTenantId();
        return new CommissionTotals(
                zero(repository.outstandingTotal(tenantId)),
                zero(repository.paidTotal(tenantId)),
                "KES",
                zero(repository.outstandingTotalFor(SellerOpsConstants.PAYEE_PLATFORM, tenantId)),
                zero(repository.paidTotalFor(SellerOpsConstants.PAYEE_PLATFORM, tenantId)),
                zero(repository.outstandingTotalFor(SellerOpsConstants.PAYEE_AGENT, tenantId)),
                zero(repository.paidTotalFor(SellerOpsConstants.PAYEE_AGENT, tenantId)));
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

    private static BigDecimal zero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private CommissionResponse toResponse(CommissionRecord c) {
        return new CommissionResponse(c.getReference(), c.getPayeeKind(), c.getAgentName(),
                c.getBookingId() == null ? null : HashIdUtil.encodeId(c.getBookingId()), c.getBookingRef(),
                c.getDevelopmentName(), c.getPaidBy(),
                c.getPropertyRef(), c.getPropertyTitle(),
                c.getTenantName(), c.getSalePrice(), c.getRatePercent(), c.getAmount(), c.getCurrency(),
                c.getSoldAt(), c.getState(), c.getInvoiceRef(), c.getInvoicedAt(), c.getPaidAt(),
                c.getWaivedReason(), c.getNote(),
                c.getDisbursementId() == null ? null : HashIdUtil.encodeId(c.getDisbursementId()),
                c.getDisbursementId() == null ? null : disbursements.findById(c.getDisbursementId())
                        .map(com.hodi.modules.disbursements.Disbursement::getReference).orElse(null));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
