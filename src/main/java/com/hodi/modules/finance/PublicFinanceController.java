package com.hodi.modules.finance;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.finance.FinanceDtos.AffordabilityRequest;
import com.hodi.modules.finance.FinanceDtos.AffordabilityResponse;
import com.hodi.modules.finance.FinanceDtos.FinancePanel;
import com.hodi.modules.finance.FinanceDtos.PublicProductResponse;
import com.hodi.modules.finance.FinanceDtos.PublicProductSearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * The finance a stranger can see, and the sums they can do without an account.
 *
 * <h2>Why the calculator is public</h2>
 *
 * <p>"What can I afford" is the question people arrive with, and putting it behind a registration form asks
 * somebody to hand over their identity before the platform has been useful to them once. This endpoint
 * computes and returns; it stores nothing and has no user id to store it against. Signing in gets the same
 * answer with the row kept, through {@code /api/v1/me/affordability}.
 *
 * <p>It takes income figures and holds none of them. There is no rate limit because there is nothing to
 * exhaust: no write, no external call, no state — an annuity and a division.
 */
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicFinanceController {

    private final PublicMortgageProductService products;
    private final FinanceMatchService finance;
    private final AffordabilityService affordability;

    /** Every published product, whoever the lender. */
    @GetMapping("/mortgage-products/search")
    public ApiResponse<PagedResponse<PublicProductResponse>> search(
            @ModelAttribute PublicProductSearchRequest request) {
        return ApiResponse.success(products.search(request));
    }

    /**
     * The finance panel for one listing: the products of the lenders this seller is partnered with, each
     * costed against the asking price.
     *
     * @param netMonthlyIncome optional — when given, each option says whether it fits. Absent leaves the
     *                         answer null rather than false: not knowing is not a refusal.
     */
    @GetMapping("/properties/{reference}/finance")
    public ApiResponse<FinancePanel> financeFor(
            @PathVariable String reference,
            @RequestParam(required = false) Short termMonths,
            @RequestParam(required = false) BigDecimal netMonthlyIncome) {
        return ApiResponse.success(finance.forListing(reference, termMonths, netMonthlyIncome));
    }

    /** The calculator. Computes, returns, keeps nothing. */
    @PostMapping("/affordability/estimate")
    public ApiResponse<AffordabilityResponse> estimate(
            @Valid @RequestBody AffordabilityRequest request) {
        return ApiResponse.success(affordability.estimate(request));
    }
}
