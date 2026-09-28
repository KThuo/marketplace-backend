package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.disbursements.Disbursement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The cost a successful payment writes for itself.
 *
 * <p>A payment and the cost it is are one fact, entered once. When a disbursement from a development settles
 * as paid, this writes the {@code SPENT} line against the same development, phase and category, naming the
 * beneficiary and pointing back at the payment — so nobody keys the invoice a second time, and a ledger line
 * that says "paid through Hodi" is one the bank confirmed.
 *
 * <p>Once, however many times the bank's answer is read: a unique index on the payment's id backs the check
 * here. A failed or refused payment writes nothing, because nothing was spent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaidCostRecorder {

    public static final String ENTRY_MANUAL = "MANUAL";
    public static final String ENTRY_DISBURSEMENT = "DISBURSEMENT";

    private final DevelopmentExpenditureRepository expenditures;
    private final DevelopmentInventoryService inventory;
    private final AuditService audit;

    /** Joins the settling transaction, so the cost exists exactly when the payment says paid. */
    @Transactional
    public Optional<DevelopmentExpenditure> record(Disbursement paid) {
        if (paid.getDevelopmentId() == null || !Disbursement.SUCCEEDED.equals(paid.getState())) return Optional.empty();
        if (expenditures.existsByDisbursementId(paid.getId())) return Optional.empty();

        LocalDate on = (paid.getSettledAt() == null ? java.time.OffsetDateTime.now() : paid.getSettledAt())
                .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        DevelopmentExpenditure line = expenditures.save(DevelopmentExpenditure.builder()
                .reference(nextReference())
                .developmentId(paid.getDevelopmentId())
                .phaseId(paid.getPhaseId())
                .categoryId(paid.getCostCategoryId())
                .kind(AppConstant.COST_SPENT)
                .amount(paid.getAmount())
                .currency(paid.getCurrency())
                .incurredOn(on)
                .payee(paid.getPayeeName())
                .referenceNo(paid.getInvoiceReference() == null ? paid.getReference() : paid.getInvoiceReference())
                .notes("Paid through Hodi, " + paid.getReference()
                        + (paid.getBankReference() == null ? "" : " (Co-op " + paid.getBankReference() + ")"))
                .documentId(paid.getDocumentId())
                .beneficiaryId(paid.getBeneficiaryId())
                .disbursementId(paid.getId())
                .entryKind(ENTRY_DISBURSEMENT)
                .tenantId(paid.getOwnerTenantId())
                .institutionId(paid.getOwnerInstitutionId())
                .createdBy(AppConstant.USERNAME_SYSTEM)
                .updatedBy(AppConstant.USERNAME_SYSTEM)
                .build());
        inventory.recountPhaseMoney(paid.getDevelopmentId());
        audit.record(AppConstant.AUDIT_COST_RECORDED, "DevelopmentExpenditure", line.getId(), null,
                line.getReference() + " written by payment " + paid.getReference());
        log.info("Payment {} recorded cost {} on development {}", paid.getReference(), line.getReference(),
                paid.getDevelopmentId());
        return Optional.of(line);
    }

    private String nextReference() {
        for (int i = 0; i < 5; i++) {
            String candidate = RrnGenerator.generate("EX");
            if (!expenditures.existsByReference(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate a cost reference");
    }
}
