package com.hodi.modules.sellers;

import com.hodi.common.AppConstant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerApplicationDocumentRepository
        extends JpaRepository<SellerApplicationDocument, Long> {

    @Query("select d from SellerApplicationDocument d where d.applicationId = :applicationId "
            + "and d.status <> " + AppConstant.STATUS_DELETED + " order by d.documentCode")
    List<SellerApplicationDocument> findLiveFor(@Param("applicationId") Long applicationId);

    @Query("select d from SellerApplicationDocument d where d.applicationId = :applicationId "
            + "and d.documentCode = :code and d.status <> " + AppConstant.STATUS_DELETED)
    Optional<SellerApplicationDocument> findLiveLine(@Param("applicationId") Long applicationId,
                                                     @Param("code") String code);
}
