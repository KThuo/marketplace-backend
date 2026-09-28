package com.hodi.modules.beneficiaries;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.SaveTypeRequest;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.TypeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The beneficiary types: read by anyone who names a payee, written by the platform. */
@RestController
@RequestMapping("/api/v1/beneficiary-types")
@RequiredArgsConstructor
public class BeneficiaryTypeController {

    private final BeneficiaryTypeService service;

    /** What the register-a-beneficiary form offers. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('BENEFICIARIES_VIEW','BENEFICIARIES_MANAGE','BENEFICIARY_TYPES_MANAGE')")
    public ApiResponse<List<TypeResponse>> available() {
        return ApiResponse.success(service.available());
    }

    /** Everything, suspended ones included — the admin screen. */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('BENEFICIARY_TYPES_MANAGE')")
    public ApiResponse<List<TypeResponse>> all() {
        return ApiResponse.success(service.all());
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('BENEFICIARY_TYPES_MANAGE')")
    @RequestAction("CREATE_BENEFICIARY_TYPE")
    public ApiResponse<TypeResponse> create(@Valid @RequestBody SaveTypeRequest request) {
        return ApiResponse.success("Type added", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('BENEFICIARY_TYPES_MANAGE')")
    @RequestAction("UPDATE_BENEFICIARY_TYPE")
    public ApiResponse<TypeResponse> update(@PathVariable String hashId, @Valid @RequestBody SaveTypeRequest request) {
        return ApiResponse.success("Saved", service.update(hashId, request));
    }

    @PostMapping("/{hashId}/status")
    @PreAuthorize("hasAuthority('BENEFICIARY_TYPES_MANAGE')")
    @RequestAction("SET_BENEFICIARY_TYPE_STATUS")
    public ApiResponse<Void> setStatus(@PathVariable String hashId, @RequestParam boolean active) {
        return ApiResponse.success(service.setStatus(hashId, active), null);
    }
}
