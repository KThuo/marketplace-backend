package com.hodi.modules.usertypes;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.usertypes.dto.UserTypeDtos.CreateUserTypeRequest;
import com.hodi.modules.usertypes.dto.UserTypeDtos.UpdateUserTypeRequest;
import com.hodi.modules.usertypes.dto.UserTypeDtos.UserTypeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user-types")
@RequiredArgsConstructor
public class UserTypeController {

    private final UserTypeService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('USER_TYPES_VIEW')")
    public ApiResponse<PagedResponse<UserTypeResponse>> list(@ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.list(request));
    }

    /**
     * The picker feed, readable by anyone who administers users or groups.
     *
     * <p>Wider than {@code USER_TYPES_VIEW} on purpose: a seller owner has no business editing the catalogue
     * but cannot fill in the user form without reading it. Withholding it would mean either an empty dropdown
     * or granting them the platform's own permission, and the list is labels rather than anything sensitive.
     */
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('USER_TYPES_VIEW','USER_GROUPS_VIEW','USERS_VIEW')")
    public ApiResponse<List<UserTypeResponse>> options() {
        return ApiResponse.success(service.options());
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('USER_TYPES_CREATE')")
    public ApiResponse<UserTypeResponse> create(@Valid @RequestBody CreateUserTypeRequest request) {
        return ApiResponse.success("User type created", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('USER_TYPES_UPDATE')")
    public ApiResponse<UserTypeResponse> update(@PathVariable String hashId,
                                                @Valid @RequestBody UpdateUserTypeRequest request) {
        return ApiResponse.success("User type updated", service.update(hashId, request));
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('USER_TYPES_DEACTIVATE')")
    public ApiResponse<Void> deactivate(@PathVariable String hashId,
                                        @Valid @RequestBody DeactivateRequest request) {
        service.deactivate(hashId, request.reason());
        return ApiResponse.success("User type deactivated", null);
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('USER_TYPES_ACTIVATE')")
    public ApiResponse<Void> activate(@PathVariable String hashId) {
        service.activate(hashId);
        return ApiResponse.success("User type activated", null);
    }

    @PostMapping("/delete/{hashId}")
    @PreAuthorize("hasAuthority('USER_TYPES_DELETE')")
    public ApiResponse<Void> delete(@PathVariable String hashId) {
        service.archive(hashId);
        return ApiResponse.success("User type deleted", null);
    }
}
