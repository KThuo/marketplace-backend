package com.hodi.modules.beneficiaries;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The people and companies an organisation pays.
 *
 * <p>Registering one is proposing where money may go, so it is gated like a payment account and waits for a
 * second person — the approvals screen decides, not this controller.
 */
@RestController
@RequestMapping("/api/v1/beneficiaries")
@RequiredArgsConstructor
public class BeneficiaryController {

    private final BeneficiaryService service;

    @GetMapping("/list")
    @PreAuthorize("hasAnyAuthority('BENEFICIARIES_VIEW','BENEFICIARIES_MANAGE')")
    public ApiResponse<PagedResponse<BeneficiaryResponse>> list(@ModelAttribute BeneficiaryListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAnyAuthority('BENEFICIARIES_VIEW','BENEFICIARIES_MANAGE')")
    public ApiResponse<BeneficiaryResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    /** Live and verified, for the payment form's picker. */
    @GetMapping("/payable")
    @PreAuthorize("hasAnyAuthority('BENEFICIARIES_VIEW','BENEFICIARIES_MANAGE','DISBURSEMENTS_MAKE')")
    public ApiResponse<List<PayableBeneficiary>> payable(@RequestParam(required = false) String tenantId,
                                                         @RequestParam(required = false) String institutionId) {
        return ApiResponse.success(service.payable(tenantId, institutionId));
    }

    /** Who holds an account, so the form can show the name before anybody commits. */
    @PostMapping("/check")
    @PreAuthorize("hasAuthority('BENEFICIARIES_MANAGE')")
    public ApiResponse<AccountCheckResponse> check(@Valid @RequestBody CheckAccountRequest request) {
        return ApiResponse.success(service.check(request));
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('BENEFICIARIES_MANAGE')")
    @RequestAction("REGISTER_BENEFICIARY")
    public ApiResponse<BeneficiaryResponse> create(@Valid @RequestBody SaveBeneficiaryRequest request) {
        BeneficiaryResponse saved = service.create(request);
        return ApiResponse.success(saved.name() + " registered and sent for approval", saved);
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('BENEFICIARIES_MANAGE')")
    @RequestAction("UPDATE_BENEFICIARY")
    public ApiResponse<BeneficiaryResponse> update(@PathVariable String hashId,
                                                   @Valid @RequestBody SaveBeneficiaryRequest request) {
        BeneficiaryResponse saved = service.update(hashId, request);
        return ApiResponse.success(saved.status() == 0 ? "Saved and sent for approval" : "Saved", saved);
    }

    @PostMapping("/{hashId}/verify")
    @PreAuthorize("hasAuthority('BENEFICIARIES_MANAGE')")
    @RequestAction("VERIFY_BENEFICIARY")
    public ApiResponse<BeneficiaryResponse> verify(@PathVariable String hashId) {
        BeneficiaryResponse row = service.verify(hashId);
        return ApiResponse.success(row.confirmedName() != null
                ? "The bank confirms the account is held by " + row.confirmedName()
                : "The bank could not confirm the account", row);
    }

    @PostMapping("/{hashId}/status")
    @PreAuthorize("hasAuthority('BENEFICIARIES_MANAGE')")
    @RequestAction("SET_BENEFICIARY_STATUS")
    public ApiResponse<Void> setStatus(@PathVariable String hashId, @RequestParam boolean active) {
        return ApiResponse.success(service.setStatus(hashId, active), null);
    }
}
