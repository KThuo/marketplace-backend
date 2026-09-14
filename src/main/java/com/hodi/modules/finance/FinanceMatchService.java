package com.hodi.modules.finance;

import com.hodi.modules.finance.FinanceDtos.FinanceOption;
import com.hodi.modules.finance.FinanceDtos.FinancePanel;
import com.hodi.modules.finance.FinanceDtos.PublicProductResponse;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The finance beside the listing — the platform's headline, in one class.
 *
 * <h2>Which products appear</h2>
 *
 * <p>Every published one. This used to be filtered through the partnership table — a lender who had not
 * partnered with this seller did not appear however good their rate — because the panel was showing a market
 * of competing banks and had to say which of them had a right to this seller's portfolio. There is one bank,
 * it runs the platform, and it lends against everything listed on it, so the filter has nothing left to
 * decide and its absence is the rule rather than an omission.
 *
 * <h2>Everything here is computed on read</h2>
 *
 * <p>Nothing is stored. These are indicative figures derived from products that change by the week, and a
 * frozen copy would be a promise the platform did not make. The disclaimer travels with the payload rather
 * than living in the client, so no screen can render these numbers without it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinanceMatchService {

    static final String DISCLAIMER =
            "Indicative only. Figures are calculated from published product terms and are not an offer, a "
                    + "quotation or a credit decision. A lender will assess your circumstances before "
                    + "lending.";

    private final PropertyRepository properties;
    private final MortgageProductRepository products;

    /**
     * The finance panel for one live listing.
     *
     * @param netMonthlyIncome what the caller can put towards a repayment each month, or null when they have
     *                         not said — which is different from a "no", and the option's
     *                         {@code affordable} flag stays null rather than becoming false
     */
    @Transactional(readOnly = true)
    public FinancePanel forListing(String reference, Short requestedTerm, BigDecimal netMonthlyIncome) {
        Property property = properties.findLiveByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));

        List<MortgageProduct> onOffer = products.findOnOffer(Pageable.unpaged());
        List<FinanceOption> options = new ArrayList<>(onOffer.size());
        for (MortgageProduct product : onOffer) {
            FinanceOption option = cost(product, property.getPrice(), requestedTerm, netMonthlyIncome);
            if (option != null) options.add(option);
        }

        return new FinancePanel(property.getReference(), property.getPrice(), property.getCurrency(),
                options, DISCLAIMER);
    }

    /**
     * One product costed against one price.
     *
     * @return null when the product cannot cover this purchase at all — a lender whose ceiling is below the
     *         loan required is not an option, and listing it with a figure they would refuse would be worse
     *         than leaving it out
     */
    public FinanceOption cost(MortgageProduct product, BigDecimal price, Short requestedTerm,
                              BigDecimal netMonthlyIncome) {
        if (price == null || price.signum() <= 0) return null;

        BigDecimal deposit = product.depositOn(price);
        BigDecimal loan = price.subtract(deposit);
        if (loan.signum() <= 0) return null;

        if (product.getMaxAmount() != null && loan.compareTo(product.getMaxAmount()) > 0) return null;
        if (product.getMinAmount() != null && loan.compareTo(product.getMinAmount()) < 0) return null;

        // The caller's preferred term, clamped into what this product allows — a buyer asking for 25 years
        // from a lender who caps at 20 should see the 20-year figure rather than nothing at all.
        short term = requestedTerm != null && requestedTerm > 0
                ? requestedTerm
                : product.getMaxTermMonths();
        if (term > product.getMaxTermMonths()) term = product.getMaxTermMonths();
        if (term < product.getMinTermMonths()) term = product.getMinTermMonths();

        BigDecimal repayment = Amortisation.monthlyRepayment(loan, product.getInterestRate(), term);
        BigDecimal processingFee = Amortisation.percentOf(loan, product.getProcessingFeePercent());
        BigDecimal totalPayable = repayment.multiply(BigDecimal.valueOf(term)).add(processingFee);

        Boolean affordable = null;
        if (netMonthlyIncome != null && netMonthlyIncome.signum() > 0) {
            BigDecimal ceiling = product.getMaxDtiPercent();
            BigDecimal share = Amortisation.shareOf(repayment, netMonthlyIncome);
            // The product's own ceiling where it states one; otherwise simply whether the repayment fits
            // inside the income at all. Never this platform's default ceiling — that is the assessor's
            // opinion, and putting it under a named lender's row would attribute it to them.
            affordable = ceiling != null
                    ? share.compareTo(ceiling) <= 0
                    : repayment.compareTo(netMonthlyIncome) <= 0;
        }

        return new FinanceOption(publicView(product), deposit, loan, repayment, term, processingFee,
                totalPayable, affordable);
    }

    /** The lender-facing row, reduced to what is on offer. */
    public static PublicProductResponse publicView(MortgageProduct p) {
        return new PublicProductResponse(
                p.getReference(),
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
                p.getEligibilityNotes(),
                p.getRequiredDocuments());
    }
}
