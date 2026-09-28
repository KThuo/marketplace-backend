package com.hodi.modules.settlements;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.agents.AgentPayoutAccount;
import com.hodi.modules.agents.AgentPayoutAccountService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.beneficiaries.PayoutAccountCheck;
import com.hodi.modules.bookings.BookingAccess;
import com.hodi.modules.bookings.BookingBalanceReader;
import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.disbursements.Disbursement;
import com.hodi.modules.disbursements.DisbursementDtos.DisbursementResponse;
import com.hodi.modules.disbursements.DisbursementDtos.SettlementLeg;
import com.hodi.modules.disbursements.DisbursementRepository;
import com.hodi.modules.disbursements.DisbursementService;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.sellerops.CommissionRecord;
import com.hodi.modules.sellerops.CommissionRepository;
import com.hodi.modules.settlements.SettlementDtos.*;

import static com.hodi.modules.settlements.SettlementDtos.*;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Settling a sale the bank collected: the owner gets the proceeds, the agent their fee, the bank keeps or
 * moves its own.
 *
 * <h2>Computed, never stored</h2>
 *
 * <p>A settlement is what a completed booking's money in and its commission lines add up to at the moment
 * it is proposed: gross collected, less the bank's fee, less the agent's where the owner bears it. Nothing
 * is written until the bank's staff propose it; then the transfers that result are the record, each
 * carrying the booking and what it settles, and each marks what it settled when the bank confirms it went.
 *
 * <h2>Why one settlement, at the end</h2>
 *
 * <p>The sale is not concluded until it is fully paid and every document has moved, which is the whole
 * reason the bank holds the money. Nothing is deducted per instalment.
 *
 * <p>Under {@code collection_mode = OWNER} the bank holds nothing, so there is nothing to settle here: the
 * platform's line is invoiced to the owner and the agent's is the owner's to pay, as before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    static final String PERM_MAKE = "SETTLEMENTS_MAKE";
    private static final String FEE_RETAIN = "RETAIN";
    private static final String FEE_TRANSFER = "TRANSFER";

    private final UnitBookingRepository bookings;
    private final BookingAccess access;
    private final BookingBalanceReader balances;
    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;
    private final CommissionRepository commissions;
    private final DisbursementRepository disbursements;
    private final DisbursementService engine;
    private final AgentPayoutAccountService agentAccounts;
    private final PaymentAccountRepository paymentAccounts;
    private final PayoutAccountCheck bank;
    private final ConfigurationService configs;
    private final AuditService audit;

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SettlementResponse forBooking(String bookingHashId) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = requireVisible(bookingHashId, caller);
        return describe(booking, caller);
    }

    /** The queue: awaiting, or settled. The bank sees every owner's; an owner sees their own. */
    @Transactional(readOnly = true)
    public PagedResponse<QueueRow> queue(boolean settled, PagedDataRequest request) {
        UserPrincipal caller = AuthContext.require();
        Long tenantId = caller.isPlatformStaff() ? null : caller.getTenantId();
        Long institutionId = caller.isPlatformStaff() ? null : caller.getInstitutionId();
        if (!caller.isPlatformStaff() && tenantId == null && institutionId == null) {
            return PagedResponse.from(Page.empty(), this::toRow);
        }
        Page<UnitBooking> page = bookings.findForSettlement(settled, tenantId, institutionId,
                request.toPageable(Sort.by(Sort.Direction.DESC, "completedAt")));
        return PagedResponse.from(page, this::toRow);
    }

    /** A development's sales settlements, totalled for its finance tab. */
    @Transactional(readOnly = true)
    public DevelopmentSettlements forDevelopment(String developmentHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = developments.findById(HashIdUtil.decodeId(developmentHashId))
                .filter(d -> visibility.mayRead(d, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHashId));
        List<UnitBooking> sales = bookings.findCompletedFor(development.getId());
        BigDecimal gross = BigDecimal.ZERO, bankFees = BigDecimal.ZERO, agentFees = BigDecimal.ZERO,
                net = BigDecimal.ZERO, paidOut = BigDecimal.ZERO, withBank = BigDecimal.ZERO;
        int settledCount = 0, awaiting = 0;
        for (UnitBooking sale : sales) {
            Figures f = figures(sale, development);
            gross = gross.add(f.gross()); bankFees = bankFees.add(f.bankFee()); agentFees = agentFees.add(f.agentFee());
            net = net.add(f.netToOwner());
            if (sale.getSettledAt() != null) { settledCount++; paidOut = paidOut.add(f.netToOwner()); }
            else if (development.bankCollects()) { awaiting++; withBank = withBank.add(f.netToOwner()); }
        }
        return new DevelopmentSettlements(development.getCurrency() == null ? "KES" : development.getCurrency(),
                sales.size(), settledCount, awaiting, gross, bankFees, agentFees, net, paidOut, withBank);
    }

    // ── proposing ─────────────────────────────────────────────────────────────

    /**
     * Proposes the transfers that settle one sale. Not transactional as a whole: the bank is asked about the
     * accounts between the checks and the writes, and each leg is its own transaction in the engine.
     */
    public SettlementResponse settle(String bookingHashId, SettleRequest request) {
        UserPrincipal caller = AuthContext.require();
        if (!caller.isPlatformStaff() || !AuthContext.hasAuthority(PERM_MAKE)) {
            throw new HodiException("Only the bank's staff settle a sale it collected.", HttpStatus.FORBIDDEN);
        }
        UnitBooking booking = requireVisible(bookingHashId, caller);
        Development development = developmentOf(booking);
        SettlementResponse current = describe(booking, caller);
        if (!AWAITING.equals(current.state())) {
            throw new HodiException(switch (current.state()) {
                case SETTLED -> "This sale is already settled.";
                case IN_FLIGHT -> "This sale's settlement is already proposed; its transfers are on their way.";
                default -> "The bank did not collect this sale, so there is nothing here to settle.";
            }, HttpStatus.CONFLICT);
        }
        if (!current.blockers().isEmpty()) {
            throw new HodiException(String.join(" ", current.blockers()), HttpStatus.CONFLICT);
        }
        Figures f = figures(booking, development);
        String home = current.home();
        String narration = request == null || request.narration() == null || request.narration().isBlank()
                ? null : request.narration().trim();

        // The owner's account: typed or picked, confirmed with the bank now — the checker approves that name.
        String ownerBankCode = bankCode(request == null ? null : request.ownerBankCode());
        String ownerAccount = request == null || request.ownerAccountNo() == null ? "" : request.ownerAccountNo().trim();
        if (ownerAccount.isEmpty()) {
            throw new HodiException("Say which account the owner's proceeds go to.", HttpStatus.BAD_REQUEST);
        }
        PayoutAccountCheck.Answer owner = bank.check(ownerBankCode, ownerAccount);
        if (!owner.confirmed()) {
            throw new HodiException("The owner's account could not be confirmed: " + owner.failure(), HttpStatus.BAD_REQUEST);
        }

        // The agent's: one of their confirmed accounts, the default unless another was chosen, asked again.
        AgentPayoutAccount agentAccount = null;
        PayoutAccountCheck.Answer agentAnswer = null;
        if (f.agentLine() != null) {
            agentAccount = request == null || request.agentAccountId() == null || request.agentAccountId().isBlank()
                    ? agentAccounts.payableDefault(f.agentLine().getAgentProfileId()).orElse(null)
                    : agentAccounts.payableById(f.agentLine().getAgentProfileId(), request.agentAccountId());
            if (agentAccount == null || !agentAccount.isVerified()) {
                throw new HodiException("The agent has no account the bank has confirmed. Ask them to add one.", HttpStatus.CONFLICT);
            }
            agentAnswer = bank.check(agentAccount.getBankCode(), agentAccount.getAccountNo());
            if (!agentAnswer.confirmed()) {
                throw new HodiException("The agent's account could not be confirmed today: " + agentAnswer.failure(), HttpStatus.CONFLICT);
            }
        }

        // The bank's fee account, when it moves its fee rather than keeping it.
        String[] feeAccount = null;
        PayoutAccountCheck.Answer feeAnswer = null;
        if (FEE_TRANSFER.equals(feeSettlement()) && f.bankKeeps().signum() > 0) {
            feeAccount = feeAccount();
            feeAnswer = bank.check(feeAccount[0], feeAccount[1]);
            if (!feeAnswer.confirmed()) {
                throw new HodiException("The bank's fee account could not be confirmed: " + feeAnswer.failure(), HttpStatus.CONFLICT);
            }
        }

        String sale = home + " (" + booking.getReference() + ")";
        String currency = booking.getCurrency() == null ? "KES" : booking.getCurrency();
        List<DisbursementResponse> legs = new ArrayList<>();
        if (f.netToOwner().signum() > 0) {
            legs.add(engine.proposeSettlementLeg(new SettlementLeg(Disbursement.SETTLEMENT_PROCEEDS,
                    booking.getId(), booking.getReference(),
                    development.getInstitutionId() == null ? Disbursement.PAYEE_SELLER : Disbursement.PAYEE_OTHER,
                    development.getInstitutionId() == null ? development.getTenantId() : null,
                    development.principalName(), owner.bankCode() == null ? ownerBankCode : owner.bankCode(),
                    owner.accountNo() == null ? ownerAccount : owner.accountNo(), owner.holderName(),
                    f.netToOwner(), currency, "Sale proceeds — " + sale + ", " + development.getName(), narration,
                    development.getId(), development.getName(), development.getTenantId(), development.getInstitutionId())));
        }
        if (agentAccount != null && f.agentFee().signum() > 0) {
            legs.add(engine.proposeSettlementLeg(new SettlementLeg(Disbursement.SETTLEMENT_AGENT_FEE,
                    booking.getId(), booking.getReference(), Disbursement.PAYEE_OTHER, null,
                    f.agentLine().getAgentName(),
                    agentAnswer.bankCode() == null ? agentAccount.getBankCode() : agentAnswer.bankCode(),
                    agentAnswer.accountNo() == null ? agentAccount.getAccountNo() : agentAnswer.accountNo(),
                    agentAnswer.holderName(), f.agentFee(), currency,
                    "Agent's commission — " + sale + ", " + development.getName(), narration,
                    development.getId(), development.getName(), development.getTenantId(), development.getInstitutionId())));
        }
        if (feeAccount != null) {
            legs.add(engine.proposeSettlementLeg(new SettlementLeg(Disbursement.SETTLEMENT_BANK_FEE,
                    booking.getId(), booking.getReference(), Disbursement.PAYEE_OTHER, null,
                    "The bank's fee account",
                    feeAnswer.bankCode() == null ? feeAccount[0] : feeAnswer.bankCode(),
                    feeAnswer.accountNo() == null ? feeAccount[1] : feeAnswer.accountNo(),
                    feeAnswer.holderName(), f.bankKeeps(), currency,
                    "Bank's commission — " + sale + ", " + development.getName(), narration,
                    development.getId(), development.getName(), development.getTenantId(), development.getInstitutionId())));
        }
        audit.record(AppConstant.ACTION_CREATE, "Settlement", booking.getId(), null,
                booking.getReference() + " gross " + f.gross() + " bank " + f.bankFee() + " agent " + f.agentFee()
                        + " net " + f.netToOwner() + " legs " + legs.stream().map(DisbursementResponse::reference).toList());
        log.info("Settlement of {} proposed by {}: {} legs", booking.getReference(), caller.getUsername(), legs.size());
        return describe(booking, caller);
    }

    // ── the figures ───────────────────────────────────────────────────────────

    /** What one sale adds up to. */
    record Figures(BigDecimal gross, BigDecimal bankFee, BigDecimal agentFee, String agentBorneBy,
                   BigDecimal bankKeeps, BigDecimal netToOwner, CommissionRecord bankLine, CommissionRecord agentLine) {}

    Figures figures(UnitBooking booking, Development development) {
        BigDecimal gross = balances.forBooking(booking.getId()).map(BalanceRow::paid).orElse(BigDecimal.ZERO);
        CommissionRecord bankLine = null, agentLine = null;
        for (CommissionRecord line : commissions.findByBookingIdAndStatusNotOrderByPayeeKind(booking.getId(), AppConstant.STATUS_DELETED)) {
            if (line.getState().equals("WAIVED")) continue;
            if (line.isAgentLine()) agentLine = line; else bankLine = line;
        }
        BigDecimal bankFee = bankLine == null ? BigDecimal.ZERO : bankLine.getAmount();
        BigDecimal agentFee = agentLine == null ? BigDecimal.ZERO : agentLine.getAmount();
        String borneBy = agentLine == null ? null : agentLine.getPaidBy();
        boolean bankBears = Development.AGENT_PAID_BY_BANK.equals(borneBy);
        BigDecimal deductions = bankFee.add(bankBears ? BigDecimal.ZERO : agentFee);
        BigDecimal bankKeeps = bankFee.subtract(bankBears ? agentFee : BigDecimal.ZERO).max(BigDecimal.ZERO);
        BigDecimal net = gross.subtract(deductions).max(BigDecimal.ZERO);
        return new Figures(gross, bankFee, agentFee, borneBy, bankKeeps, net, bankLine, agentLine);
    }

    private SettlementResponse describe(UnitBooking booking, UserPrincipal caller) {
        Development development = developmentOf(booking);
        Property home = access.propertyOf(booking);
        Figures f = figures(booking, development);
        List<Disbursement> transfers = disbursements.findByBookingIdAndStatusNot(booking.getId(), AppConstant.STATUS_DELETED);
        List<Leg> legs = transfers.stream().map(d -> new Leg(d.getSettlementKind(), HashIdUtil.encodeId(d.getId()),
                d.getReference(), d.getState(), engine.toResponse(d).stateLabel(), d.getAmount(), d.getPayeeName(),
                d.getSettledAt())).toList();
        boolean inFlight = transfers.stream().anyMatch(d -> !d.isTerminal());
        String state = !development.bankCollects() ? NOT_BANK_COLLECTED
                : booking.getSettledAt() != null ? SETTLED
                : inFlight ? IN_FLIGHT
                : AWAITING;

        List<String> blockers = new ArrayList<>();
        if (AWAITING.equals(state)) {
            if (f.gross().signum() <= 0) blockers.add("Nothing was collected on this booking.");
            if (f.agentLine() != null && agentAccounts.payableDefault(f.agentLine().getAgentProfileId()).isEmpty()) {
                blockers.add(f.agentLine().getAgentName() + " has no payout account the bank has confirmed.");
            }
            if (FEE_TRANSFER.equals(feeSettlement()) && f.bankKeeps().signum() > 0 && feeAccountOrNull() == null) {
                blockers.add("The bank's commission is set to be transferred, but no fee account is named "
                        + "(commission.platform.fee.account).");
            }
        }
        boolean mayPropose = AWAITING.equals(state) && blockers.isEmpty()
                && caller.isPlatformStaff() && AuthContext.hasAuthority(PERM_MAKE);

        List<SuggestedAccount> ownerAccounts = caller.isPlatformStaff() ? ownerAccounts(development) : List.of();
        List<AgentAccountOption> agentOptions = caller.isPlatformStaff() && f.agentLine() != null
                ? agentAccounts.listFor(agentProfile(f.agentLine())).stream().filter(a -> a.payable())
                        .map(a -> new AgentAccountOption(a.id(), a.accountNo(), a.bankCode(), a.confirmedName(), a.defaultAccount()))
                        .toList()
                : List.of();

        return new SettlementResponse(HashIdUtil.encodeId(booking.getId()), booking.getReference(),
                HashIdUtil.encodeId(development.getId()), development.getName(),
                home.getUnitLabel() != null ? home.getUnitLabel() : home.getTitle(), booking.getBuyerName(),
                development.principalName(), booking.getCurrency() == null ? "KES" : booking.getCurrency(),
                booking.getPriceAgreed(), f.gross(), f.bankFee(), f.agentFee(), f.agentBorneBy(),
                f.agentLine() == null ? null : f.agentLine().getAgentName(), f.bankKeeps(), f.netToOwner(),
                feeSettlement(), state, booking.getCompletedAt(), booking.getSettledAt(), legs, blockers, mayPropose,
                ownerAccounts, agentOptions);
    }

    private QueueRow toRow(UnitBooking booking) {
        Development development = developmentOf(booking);
        Property home = access.propertyOf(booking);
        Figures f = figures(booking, development);
        boolean inFlight = disbursements.findByBookingIdAndStatusNot(booking.getId(), AppConstant.STATUS_DELETED)
                .stream().anyMatch(d -> !d.isTerminal());
        String state = booking.getSettledAt() != null ? SETTLED : inFlight ? IN_FLIGHT : AWAITING;
        return new QueueRow(HashIdUtil.encodeId(booking.getId()), booking.getReference(), development.getName(),
                home.getUnitLabel() != null ? home.getUnitLabel() : home.getTitle(), booking.getBuyerName(),
                development.principalName(), booking.getCurrency() == null ? "KES" : booking.getCurrency(),
                f.gross(), f.bankFee(), f.agentFee(), f.netToOwner(), state, booking.getCompletedAt(), booking.getSettledAt());
    }

    // ── lookups ───────────────────────────────────────────────────────────────

    private UnitBooking requireVisible(String hashId, UserPrincipal caller) {
        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(hashId))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", hashId));
        if (!access.mayRead(access.propertyOf(booking), caller)) throw new ResourceNotFoundException("Booking", hashId);
        if (!AppConstant.BOOKING_COMPLETED.equals(booking.getState())) {
            throw new HodiException("Only a completed sale is settled; this booking is "
                    + booking.getState().toLowerCase(Locale.ROOT) + ".", HttpStatus.CONFLICT);
        }
        if (booking.getDevelopmentId() == null) {
            throw new HodiException("A house is sold and paid for by its seller; the bank settles the sales it "
                    + "collects for a development.", HttpStatus.CONFLICT);
        }
        return booking;
    }

    private Development developmentOf(UnitBooking booking) {
        return developments.findById(booking.getDevelopmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Development", String.valueOf(booking.getDevelopmentId())));
    }

    private com.hodi.modules.agents.AgentProfile agentProfile(CommissionRecord agentLine) {
        return com.hodi.modules.agents.AgentProfile.builder().id(agentLine.getAgentProfileId()).build();
    }

    /** The owner's live accounts with us, as suggestions: account numbers the bank already knows. */
    private List<SuggestedAccount> ownerAccounts(Development development) {
        List<PaymentAccount> mine = development.getInstitutionId() != null
                ? paymentAccounts.findLiveForInstitution(development.getInstitutionId())
                : paymentAccounts.findLiveForTenant(development.getTenantId());
        return mine.stream().filter(a -> a.getAccountNo() != null && a.getAccountNo().matches("\\d{6,}"))
                .map(a -> new SuggestedAccount(a.getAccountNo(), "0011", a.getAccountName()))
                .distinct().toList();
    }

    private String feeSettlement() {
        String raw = configs.getString(ConfigKey.PLATFORM_COMMISSION_SETTLEMENT);
        return FEE_TRANSFER.equalsIgnoreCase(raw == null ? "" : raw.trim()) ? FEE_TRANSFER : FEE_RETAIN;
    }

    /** "0011/0110001122334455" → {bank code, account}; null when unset or malformed. */
    private String[] feeAccountOrNull() {
        String raw = configs.getString(ConfigKey.PLATFORM_COMMISSION_FEE_ACCOUNT);
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.trim().split("/");
        if (parts.length == 1) return new String[] {"0011", parts[0].trim()};
        if (parts.length != 2 || parts[1].isBlank()) return null;
        return new String[] {bankCode(parts[0]), parts[1].trim()};
    }

    private String[] feeAccount() {
        String[] account = feeAccountOrNull();
        if (account == null) {
            throw new HodiException("The bank's commission is set to be transferred, but no fee account is named "
                    + "(commission.platform.fee.account).", HttpStatus.CONFLICT);
        }
        return account;
    }

    private static String bankCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty()) code = "11";
        if (!code.matches("\\d{1,4}")) throw new HodiException("A bank code is up to four digits.", HttpStatus.BAD_REQUEST);
        return "0000".substring(code.length()) + code;
    }
}
