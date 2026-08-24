package com.hodi.modules.finance;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MortgageProductRepository
        extends JpaRepository<MortgageProduct, Long>, JpaSpecificationExecutor<MortgageProduct> {

    boolean existsByReference(String reference);

    Optional<MortgageProduct> findByReference(String reference);

    /**
     * The products of these lenders, on offer, cheapest first.
     *
     * <p>The finance panel's only query. The institution list comes from the partnership table, so a seller's
     * listing shows the rates of the lenders that seller actually works with — and a lender who has not
     * partnered with them does not appear, however good their rate.
     *
     * <p>An empty collection would make {@code in ()} invalid SQL, so the caller checks first; there is no
     * sensible "all lenders" reading of this query that is not a bug.
     */
    @Query("select p from MortgageProduct p where p.institutionId in :institutionIds "
            + "and p.published = true and p.status <> 5 order by p.interestRate asc, p.name asc")
    List<MortgageProduct> findOnOfferFor(@Param("institutionIds") Collection<Long> institutionIds);

    @Query("select count(p) from MortgageProduct p where p.institutionId = :institutionId "
            + "and p.published = true and p.status <> 5")
    long countOnOfferFor(@Param("institutionId") Long institutionId);

    /**
     * Everything on offer, cheapest first, bounded by the caller.
     *
     * <p>For the calculator used without a listing in mind: there is no seller whose partnerships could
     * narrow the field, so the honest answer is the market. Paged rather than fetched whole — a catalogue of
     * every lender's every product is not a list anybody reads, and filtering it in the JVM would pull it
     * across the wire first.
     */
    @Query("select p from MortgageProduct p where p.published = true and p.status <> 5 "
            + "order by p.interestRate asc, p.name asc")
    List<MortgageProduct> findOnOffer(Pageable pageable);

    /** Re-stamps the denormalised lender name after a rename. One writer, in the institution service. */
    @Modifying
    @Query("update MortgageProduct p set p.institutionName = :name where p.institutionId = :institutionId")
    int renameInstitutionLabel(@Param("institutionId") Long institutionId, @Param("name") String name);
}
