package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.payments.StatementDtos.AttachRequest;
import com.hodi.modules.payments.StatementDtos.SetAsideRequest;
import com.hodi.modules.payments.StatementDtos.StatementDetail;
import com.hodi.modules.payments.StatementDtos.StatementListRequest;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.Waiting;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The bank's notifications: what arrived, what it was placed on, and what is still waiting for a person.
 *
 * <p>One list serves the ledger and the worklist — {@code state=UNMAPPED} is the queue — and three
 * decisions a person may take about an unplaced row. Reading is {@code STATEMENTS_VIEW}, scoped to the
 * caller's own accounts; deciding is {@code STATEMENTS_RECONCILE}, which is the platform's.
 */
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('STATEMENTS_VIEW')")
    public ApiResponse<PagedResponse<StatementResponse>> list(@ModelAttribute StatementListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/waiting")
    @PreAuthorize("hasAuthority('STATEMENTS_VIEW')")
    public ApiResponse<Waiting> waiting() {
        return ApiResponse.success(service.waiting());
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('STATEMENTS_VIEW')")
    public ApiResponse<StatementDetail> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @PostMapping("/{hashId}/attach")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("ATTACH_STATEMENT")
    public ApiResponse<StatementResponse> attach(@PathVariable String hashId,
                                                 @Valid @RequestBody AttachRequest request) {
        StatementResponse applied = service.attach(hashId, request);
        return ApiResponse.success(applied.currency() + " " + applied.amount().toPlainString()
                + " applied to booking " + applied.bookingReference() + " as receipt "
                + applied.paymentReference() + ".", applied);
    }

    @PostMapping("/{hashId}/set-aside")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("SET_ASIDE_STATEMENT")
    public ApiResponse<StatementResponse> setAside(@PathVariable String hashId,
                                                   @Valid @RequestBody SetAsideRequest request) {
        return ApiResponse.success("Set aside. It stays on record and stops appearing as work.",
                service.setAside(hashId, request));
    }

    @PostMapping("/{hashId}/restore")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("RESTORE_STATEMENT")
    public ApiResponse<StatementResponse> restore(@PathVariable String hashId) {
        return ApiResponse.success("Back in the queue.", service.restore(hashId));
    }
}
