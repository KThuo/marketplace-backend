package com.hodi.modules.finance;

import com.hodi.common.PagedResponse;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.finance.FinanceDtos.PublicProductResponse;
import com.hodi.modules.finance.FinanceDtos.PublicProductSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The product catalogue as a stranger sees it.
 *
 * <p>Every query starts from published-and-live, which is also the partial index that serves it. There is no
 * code path here that could return a draft — not because each method remembers to filter, but because the
 * only specification any of them builds starts with {@link #onOffer()}. The same construction the marketplace
 * uses for listings, for the same reason.
 *
 * <p>Cheapest first. A catalogue ordered by anything else is a catalogue somebody is being steered through.
 */
@Service
@RequiredArgsConstructor
public class PublicMortgageProductService {

    private final MortgageProductRepository repository;

    @Transactional(readOnly = true)
    public PagedResponse<PublicProductResponse> search(PublicProductSearchRequest request) {
        Specification<MortgageProduct> spec = SearchSpecs.allOf(
                onOffer(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("productType", blankToNull(request.getProductType())));

        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "interestRate")));
        return PagedResponse.from(page, FinanceMatchService::publicView);
    }

    /** The only thing a stranger may see. Every public query starts here. */
    private Specification<MortgageProduct> onOffer() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("published")),
                cb.notEqual(root.get("status"), com.hodi.common.AppConstant.STATUS_DELETED),
                cb.notEqual(root.get("status"), com.hodi.common.AppConstant.STATUS_INACTIVE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
