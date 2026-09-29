package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * The figure the bank lends against (plan §3.1).
 *
 * <p>A price is what the seller asks; a valuation is what an independent valuer found. Where a completed
 * valuation exists for a property, affordability and the mortgage panel lend against the lesser of the two,
 * and say so. Which of the valuer's two figures is used — the forced-sale value the bank recovers in a bad
 * year, or the market value — is {@code valuation.lending.basis}, because the client's standing rule is that
 * what can be configurable should be.
 *
 * <p>Only a COMPLETED valuation counts: one that has been through the review. A submitted one is an
 * opinion nobody has checked, and a cancelled one is nothing.
 */
@Service
@RequiredArgsConstructor
public class LendingValueService {

    public static final String BASIS_PRICE = "PRICE";
    public static final String BASIS_FORCED_SALE = "FORCED_SALE";
    public static final String BASIS_MARKET = "MARKET";

    private final ValuationRequestRepository requests;
    private final ValuationReportRepository reports;
    private final ConfigurationService configs;

    /**
     * The latest completed valuation of a property, as the sale's page shows it.
     *
     * @param lendingValue the figure to lend against, given the price: the lesser of price and the basis
     * @param basis        PRICE when the price is the lower or no valuation exists; else FORCED_SALE or MARKET
     */
    public record ValuationFigures(
            String reference,
            BigDecimal marketValue,
            BigDecimal forcedSaleValue,
            String currency,
            String valuerName,
            OffsetDateTime completedAt,
            BigDecimal lendingValue,
            String basis) {}

    /** The latest completed valuation on the property, or empty. The price decides the lending value. */
    @Transactional(readOnly = true)
    public Optional<ValuationFigures> latestFor(Long propertyId, BigDecimal price) {
        if (propertyId == null) return Optional.empty();
        return requests.findLatestCompletedForProperty(propertyId).stream().findFirst()
                .flatMap(job -> reports.findByRequestId(job.getId()).map(report -> {
                    String basis = configuredBasis();
                    BigDecimal figure = BASIS_MARKET.equals(basis) || report.getForcedSaleValue() == null
                            ? report.getMarketValue() : report.getForcedSaleValue();
                    String used = BASIS_MARKET.equals(basis) || report.getForcedSaleValue() == null
                            ? BASIS_MARKET : BASIS_FORCED_SALE;
                    BigDecimal value = figure;
                    if (price != null && price.signum() > 0 && (figure == null || price.compareTo(figure) <= 0)) {
                        value = price;
                        used = BASIS_PRICE;
                    }
                    return new ValuationFigures(job.getReference(), report.getMarketValue(),
                            report.getForcedSaleValue(), report.getCurrency(), job.getValuerName(),
                            job.getCompletedAt(), value, used);
                }));
    }

    /**
     * What to lend against: the lesser of the price and the valuer's figure, or the price alone.
     *
     * <p>Never null when the price is not: a property with no valuation is lent against at its price, as it
     * always was. The basis says which was used, for the working the buyer reads.
     */
    @Transactional(readOnly = true)
    public LendingValue lendingValueFor(Long propertyId, BigDecimal price) {
        return latestFor(propertyId, price)
                .map(v -> new LendingValue(v.lendingValue(), v.basis(), v.reference()))
                .orElse(new LendingValue(price, BASIS_PRICE, null));
    }

    public record LendingValue(BigDecimal value, String basis, String valuationReference) {
        /** Whether the valuation, rather than the price, set the figure. */
        public boolean fromValuation() {
            return !BASIS_PRICE.equals(basis) && valuationReference != null;
        }

        /** A phrase for the working: "the forced-sale value of valuation VL…". */
        public String said() {
            return switch (basis) {
                case BASIS_FORCED_SALE -> "the forced-sale value from valuation " + valuationReference;
                case BASIS_MARKET -> "the market value from valuation " + valuationReference;
                default -> valuationReference == null ? "the asking price"
                        : "the asking price, which is below valuation " + valuationReference;
            };
        }
    }

    private String configuredBasis() {
        String basis = configs.getString(ConfigKey.VALUATION_LENDING_BASIS);
        return basis != null && BASIS_MARKET.equalsIgnoreCase(basis.trim()) ? BASIS_MARKET : BASIS_FORCED_SALE;
    }

    static boolean isCompleted(ValuationRequest job) {
        return AppConstant.VALUATION_COMPLETED.equals(job.getState());
    }
}
