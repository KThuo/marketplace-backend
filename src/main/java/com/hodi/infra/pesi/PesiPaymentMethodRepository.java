package com.hodi.infra.pesi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PesiPaymentMethodRepository extends JpaRepository<PesiPaymentMethod, Long> {

    /**
     * Which of our accounts a notification landed in.
     *
     * <p>The guide's lookup: {@code accountIdentifier} in, the till out, and with it whose money this is. A
     * notification naming an account we have never registered is not an error — it is a statement nobody can
     * place, which is exactly what the unmapped queue is for.
     */
    @Query("select m from PesiPaymentMethod m where m.accountNumber = :accountNumber and m.status <> 5")
    Optional<PesiPaymentMethod> findByAccountNumber(@Param("accountNumber") String accountNumber);
}
