package com.hodi.modules.usergroups;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.CloneTemplateRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.CreateUserGroupRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.UpdateUserGroupRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.UserGroupResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user-groups")
@RequiredArgsConstructor
public class UserGroupController {

    private final UserGroupService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('USER_GROUPS_VIEW')")
    public ApiResponse<PagedResponse<UserGroupResponse>> list(@ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('USER_GROUPS_VIEW')")
    public ApiResponse<UserGroupResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAnyAuthority('USER_GROUPS_CLONE','USER_GROUPS_VIEW')")
    public ApiResponse<List<UserGroupResponse>> templates() {
        return ApiResponse.success(service.templates());
    }

    /**
     * The groups assignable to a user of one type — feeds the user form's second dropdown.
     *
     * <p>Gated on {@code USERS_VIEW} as well as {@code USER_GROUPS_VIEW}: somebody who administers users
     * needs it to fill the form, and requiring the group permission too would mean granting rights over the
     * role catalogue to anybody who can add a member of staff.
     */
    @GetMapping("/assignable")
    @PreAuthorize("hasAnyAuthority('USER_GROUPS_VIEW','USERS_VIEW','USERS_CREATE')")
    public ApiResponse<List<UserGroupResponse>> assignable(@RequestParam String userTypeCode) {
        return ApiResponse.success(service.assignable(userTypeCode));
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('USER_GROUPS_CREATE')")
    public ApiResponse<UserGroupResponse> create(@Valid @RequestBody CreateUserGroupRequest request) {
        return ApiResponse.success("User group created", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('USER_GROUPS_UPDATE')")
    public ApiResponse<UserGroupResponse> update(@PathVariable String hashId,
                                                 @Valid @RequestBody UpdateUserGroupRequest request) {
        return ApiResponse.success("User group updated", service.update(hashId, request));
    }

    @PostMapping("/clone/{hashId}")
    @PreAuthorize("hasAuthority('USER_GROUPS_CLONE')")
    public ApiResponse<UserGroupResponse> clone(@PathVariable String hashId,
                                                @Valid @RequestBody CloneTemplateRequest request) {
        return ApiResponse.success("Template cloned", service.cloneTemplate(hashId, request));
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('USER_GROUPS_DEACTIVATE')")
    public ApiResponse<Void> deactivate(@PathVariable String hashId,
                                        @Valid @RequestBody DeactivateRequest request) {
        service.deactivate(hashId, request.reason());
        return ApiResponse.success("User group deactivated", null);
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('USER_GROUPS_ACTIVATE')")
    public ApiResponse<Void> activate(@PathVariable String hashId) {
        service.activate(hashId);
        return ApiResponse.success("User group activated", null);
    }

    @PostMapping("/delete/{hashId}")
    @PreAuthorize("hasAuthority('USER_GROUPS_DELETE')")
    public ApiResponse<Void> delete(@PathVariable String hashId) {
        service.archive(hashId);
        return ApiResponse.success("User group deleted", null);
    }
}
