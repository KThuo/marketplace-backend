package com.hodi.modules.bookings;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * What a booking here is made under: the refund penalty, the windows, the seller's note.
 *
 * <p>Every figure is a platform default that a development may override (lapsed-bookings plan §2.2,
 * decisions 1 and 2). A development's null means "whatever the platform says today"; the policy a buyer
 * agreed to is the one their booking's terms were filled with, not this — see {@link BookingTermsService}.
 */
@Service
@RequiredArgsConstructor
public class BookingPolicyService {

    public static final String BASIS_PERCENT_OF_PAID = "PERCENT_OF_PAID";
    public static final String BASIS_PERCENT_OF_DEPOSIT = "PERCENT_OF_DEPOSIT";
    public static final String BASIS_FIXED = "FIXED";
    public static final Set<String> BASES = Set.of(BASIS_PERCENT_OF_PAID, BASIS_PERCENT_OF_DEPOSIT, BASIS_FIXED);

    private final ConfigurationService configs;

    /**
     * The effective policy for a development, or for a house (no development: the platform's).
     *
     * @param penaltyBasis     PERCENT_OF_PAID, PERCENT_OF_DEPOSIT or FIXED
     * @param penaltyRate      the percent, or the fixed amount, as the basis says
     * @param penaltyCap       the penalty never exceeds this; null for no cap
     * @param bankSharePercent the bank's share of the penalty; the owner keeps the rest
     * @param refundWithinDays a refund may be raised this long after the booking closes; 0 for always
     * @param reviveWithinDays a lapsed booking may be revived this long after; 0 for always
     * @param note             the seller's own words to the buyer, or blank
     */
    public record BookingPolicy(
            String penaltyBasis,
            BigDecimal penaltyRate,
            BigDecimal penaltyCap,
            BigDecimal bankSharePercent,
            int refundWithinDays,
            int reviveWithinDays,
            String note) {

        /** The penalty on what was paid, given the deposit due, under this policy. Never above what was paid. */
        public BigDecimal penaltyOn(BigDecimal paid, BigDecimal depositDue) {
            if (paid == null || paid.signum() <= 0 || penaltyRate == null || penaltyRate.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal penalty = switch (penaltyBasis) {
                case BASIS_FIXED -> penaltyRate;
                case BASIS_PERCENT_OF_DEPOSIT -> percentOf(depositDue == null ? BigDecimal.ZERO : depositDue);
                default -> percentOf(paid);
            };
            if (penaltyCap != null && penaltyCap.signum() > 0 && penalty.compareTo(penaltyCap) > 0) penalty = penaltyCap;
            return penalty.min(paid).setScale(2, RoundingMode.HALF_UP);
        }

        private BigDecimal percentOf(BigDecimal base) {
            return base.multiply(penaltyRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        /** The penalty, said to a buyer. */
        public String penaltySaid(String currency) {
            if (penaltyRate == null || penaltyRate.signum() <= 0) {
                return "Nothing is kept back: what you paid is refunded in full.";
            }
            String kept = switch (penaltyBasis) {
                case BASIS_FIXED -> currency + " " + penaltyRate.stripTrailingZeros().toPlainString();
                case BASIS_PERCENT_OF_DEPOSIT -> penaltyRate.stripTrailingZeros().toPlainString() + "% of the deposit due";
                default -> penaltyRate.stripTrailingZeros().toPlainString() + "% of what you paid";
            };
            String cap = penaltyCap == null || penaltyCap.signum() <= 0 ? ""
                    : ", and never more than " + currency + " " + penaltyCap.stripTrailingZeros().toPlainString();
            return "The seller keeps " + kept + cap + "; the rest is refunded.";
        }
    }

    public BookingPolicy policyFor(Development development) {
        String basis = development == null || development.getRefundPenaltyBasis() == null
                ? basisOf(configs.getString(ConfigKey.BOOKING_REFUND_PENALTY_BASIS))
                : development.getRefundPenaltyBasis();
        BigDecimal rate = development == null || development.getRefundPenaltyRate() == null
                ? number(ConfigKey.BOOKING_REFUND_PENALTY_RATE) : development.getRefundPenaltyRate();
        BigDecimal cap = development == null || development.getRefundPenaltyCap() == null
                ? number(ConfigKey.BOOKING_REFUND_PENALTY_CAP) : development.getRefundPenaltyCap();
        BigDecimal share = development == null || development.getRefundPenaltyBankSharePercent() == null
                ? number(ConfigKey.BOOKING_REFUND_PENALTY_BANK_SHARE_PERCENT) : development.getRefundPenaltyBankSharePercent();
        int refundDays = development == null || development.getRefundWithinDays() == null
                ? configs.getInt(ConfigKey.BOOKING_REFUND_WITHIN_DAYS, 0) : development.getRefundWithinDays();
        int reviveDays = development == null || development.getReviveWithinDays() == null
                ? configs.getInt(ConfigKey.BOOKING_REVIVE_WITHIN_DAYS, 30) : development.getReviveWithinDays();
        String note = development == null || development.getBookingPolicyNote() == null
                || development.getBookingPolicyNote().isBlank()
                ? configs.getString(ConfigKey.BOOKING_POLICY_NOTE) : development.getBookingPolicyNote();
        return new BookingPolicy(basis, rate, cap == null || cap.signum() <= 0 ? null : cap,
                share == null ? BigDecimal.ZERO : share, Math.max(0, refundDays), Math.max(0, reviveDays),
                note == null ? "" : note.trim());
    }

    /** The platform's defaults alone, for the money settings card to say what a blank means. */
    public BookingPolicy platformDefaults() {
        return policyFor(null);
    }

    public static String basisOf(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return BASES.contains(value) ? value : BASIS_PERCENT_OF_PAID;
    }

    private BigDecimal number(ConfigKey key) {
        String raw = configs.getString(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
