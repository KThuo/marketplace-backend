package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.bookings.BookingBalanceReader;
import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.sellerops.CommissionRecord;
import com.hodi.modules.sellerops.CommissionRepository;
import com.hodi.modules.sellerops.CommissionService;
import com.hodi.modules.sellerops.CommissionService.CommissionResponse;
import com.hodi.modules.sellerops.SellerOpsConstants;
import com.hodi.security.hashid.HashIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What an agent has brought in, and what it has earned them — their own figures, through their own login.
 *
 * <p>Not the commissions list scoped by tenant: an agent's line sits on the seller's tenant, which is not
 * theirs. This reads by the agent's own id, so an agent sees every line with their name on it and nothing
 * else, and sees a booking they introduced from the day it is made — "I brought this buyer and the sale is
 * at 60% paid" — not only once money is due.
 */
@Service
@RequiredArgsConstructor
public class AgentEarningsService {

    private final AgentService agentService;
    private final UnitBookingRepository bookings;
    private final BookingBalanceReader balances;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitRepository homes;
    private final CommissionRepository commissions;
    private final CommissionService commissionService;

    /** A booking the agent introduced, with where the sale stands and what it has earned so far. */
    public record IntroductionResponse(
            String bookingId,
            String reference,
            String developmentName,
            String home,
            String buyerName,
            String state,
            BigDecimal priceAgreed,
            BigDecimal paid,
            String currency,
            OffsetDateTime bookedAt,
            OffsetDateTime completedAt,
            /** The agent's own line once the sale completes: its state and amount, or nulls before then. */
            String commissionState,
            BigDecimal commissionAmount) {}

    public record AgentTotals(BigDecimal due, BigDecimal paid, int introductions, int completed, String currency) {}

    @Transactional(readOnly = true)
    public List<IntroductionResponse> myIntroductions() {
        AgentProfile me = agentService.requireMine();
        List<UnitBooking> rows = bookings.findIntroducedBy(me.getId());
        Map<Long, CommissionRecord> lines = commissions
                .findByAgentProfileIdAndPayeeKindAndStatusNot(me.getId(), SellerOpsConstants.PAYEE_AGENT,
                        AppConstant.STATUS_DELETED)
                .stream().filter(c -> c.getBookingId() != null)
                .collect(Collectors.toMap(CommissionRecord::getBookingId, Function.identity(), (a, b) -> a));
        return rows.stream().map(b -> {
            BalanceRow balance = balances.forBooking(b.getId()).orElse(null);
            Property home = homes.findById(b.getPropertyId()).orElse(null);
            String developmentName = b.getDevelopmentId() == null ? null
                    : developments.findById(b.getDevelopmentId()).map(Development::getName).orElse(null);
            CommissionRecord line = lines.get(b.getId());
            return new IntroductionResponse(HashIdUtil.encodeId(b.getId()), b.getReference(), developmentName,
                    home == null ? null : home.getUnitLabel() != null ? home.getUnitLabel() : home.getTitle(),
                    b.getBuyerName(), b.getState(), b.getPriceAgreed(),
                    balance == null ? BigDecimal.ZERO : balance.paid(), b.getCurrency(),
                    b.getCreatedAt(), b.getCompletedAt(),
                    line == null ? null : line.getState(), line == null ? null : line.getAmount());
        }).toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<CommissionResponse> myCommissions(PagedDataRequest request) {
        return commissionService.forAgent(agentService.requireMine().getId(), request);
    }

    @Transactional(readOnly = true)
    public AgentTotals myTotals() {
        AgentProfile me = agentService.requireMine();
        List<CommissionRecord> lines = commissions.findByAgentProfileIdAndPayeeKindAndStatusNot(me.getId(),
                SellerOpsConstants.PAYEE_AGENT, AppConstant.STATUS_DELETED);
        BigDecimal due = lines.stream().filter(CommissionRecord::isOutstanding)
                .map(CommissionRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = lines.stream().filter(l -> SellerOpsConstants.COMMISSION_PAID.equals(l.getState()))
                .map(CommissionRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<UnitBooking> introduced = bookings.findIntroducedBy(me.getId());
        int completed = (int) introduced.stream()
                .filter(b -> AppConstant.BOOKING_COMPLETED.equals(b.getState())).count();
        return new AgentTotals(due, paid, introduced.size(), completed, "KES");
    }
}
