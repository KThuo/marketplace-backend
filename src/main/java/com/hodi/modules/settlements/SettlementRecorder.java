package com.hodi.modules.settlements;

import com.hodi.common.AppConstant;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.disbursements.Disbursement;
import com.hodi.modules.disbursements.DisbursementRepository;
import com.hodi.modules.sellerops.CommissionRecord;
import com.hodi.modules.sellerops.CommissionRepository;
import com.hodi.modules.sellerops.SellerOpsConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What a settlement's transfer writes for itself when the bank confirms it went.
 *
 * <p>Called inside the transaction that marks a disbursement SUCCEEDED, like the cost recorder. An agent's
 * fee marks the agent's line paid; the proceeds mark the sale settled and — when the bank keeps its fee
 * rather than moving it — the platform's line paid, because paying out the rest is how the bank kept it.
 * A moved fee marks the platform's line paid on its own arrival. Nothing here is done by hand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementRecorder {

    private final CommissionRepository commissions;
    private final UnitBookingRepository bookings;
    private final DisbursementRepository disbursements;

    public void onPaid(Disbursement row) {
        if (row.getBookingId() == null || row.getSettlementKind() == null) return;
        List<CommissionRecord> lines = commissions.findByBookingIdAndStatusNotOrderByPayeeKind(row.getBookingId(),
                AppConstant.STATUS_DELETED);
        switch (row.getSettlementKind()) {
            case Disbursement.SETTLEMENT_AGENT_FEE -> lines.stream().filter(CommissionRecord::isAgentLine)
                    .forEach(line -> markPaid(line, row));
            case Disbursement.SETTLEMENT_BANK_FEE -> lines.stream().filter(l -> !l.isAgentLine())
                    .forEach(line -> markPaid(line, row));
            case Disbursement.SETTLEMENT_PROCEEDS -> {
                UnitBooking booking = bookings.findById(row.getBookingId()).orElse(null);
                if (booking != null && booking.getSettledAt() == null) {
                    booking.setSettledAt(OffsetDateTime.now());
                    booking.setUpdatedBy(row.getUpdatedBy());
                    bookings.save(booking);
                    log.info("Booking {} settled by {}", booking.getReference(), row.getReference());
                }
                // The fee was retained unless a transfer of it exists: then that transfer's arrival says so.
                boolean feeMoves = disbursements.findByBookingIdAndStatusNot(row.getBookingId(), AppConstant.STATUS_DELETED)
                        .stream().anyMatch(d -> Disbursement.SETTLEMENT_BANK_FEE.equals(d.getSettlementKind())
                                && !Disbursement.FAILED.equals(d.getState()) && !Disbursement.REFUSED.equals(d.getState()));
                if (!feeMoves) lines.stream().filter(l -> !l.isAgentLine()).forEach(line -> markPaid(line, row));
            }
            default -> log.warn("Disbursement {} has an unknown settlement kind {}", row.getReference(), row.getSettlementKind());
        }
    }

    private void markPaid(CommissionRecord line, Disbursement by) {
        if (!line.isOutstanding()) return;
        line.setState(SellerOpsConstants.COMMISSION_PAID);
        line.setPaidAt(OffsetDateTime.now());
        line.setDisbursementId(by.getId());
        line.setNote(appendNote(line.getNote(), "Paid by " + by.getReference()));
        line.setUpdatedBy(by.getUpdatedBy());
        commissions.save(line);
        log.info("Commission {} ({}) paid by {}", line.getReference(), line.getPayeeKind(), by.getReference());
    }

    private static String appendNote(String existing, String addition) {
        return existing == null || existing.isBlank() ? addition : existing + "\n" + addition;
    }
}
