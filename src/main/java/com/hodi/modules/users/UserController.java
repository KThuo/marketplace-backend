package com.hodi.modules.users;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.modules.users.dto.UserDtos.CreateUserRequest;
import com.hodi.modules.users.dto.UserDtos.TemporaryPasswordResponse;
import com.hodi.modules.users.dto.UserDtos.UpdateUserRequest;
import com.hodi.modules.users.dto.UserDtos.UserListRequest;
import com.hodi.modules.users.dto.UserDtos.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('USERS_VIEW')")
    public ApiResponse<PagedResponse<UserResponse>> list(@ModelAttribute UserListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('USERS_VIEW')")
    public ApiResponse<UserResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    /**
     * Creates a staff account and returns its temporary password once.
     *
     * <p>The password is in the response body and nowhere else — not stored readable, not emailed from here.
     * The screen shows it once and tells the administrator to pass it on; there is deliberately no way to
     * retrieve it again, only to issue a new one.
     */
    @PostMapping("/create")
    @PreAuthorize("hasAuthority('USERS_CREATE')")
    public ApiResponse<TemporaryPasswordResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success("User created", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('USERS_UPDATE')")
    public ApiResponse<UserResponse> update(@PathVariable String hashId,
                                            @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.success("User updated", service.update(hashId, request));
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('USERS_DEACTIVATE')")
    public ApiResponse<Void> deactivate(@PathVariable String hashId,
                                        @Valid @RequestBody DeactivateRequest request) {
        service.deactivate(hashId, request.reason());
        return ApiResponse.success("User deactivated", null);
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('USERS_ACTIVATE')")
    public ApiResponse<Void> activate(@PathVariable String hashId) {
        service.activate(hashId);
        return ApiResponse.success("User activated", null);
    }

    @PostMapping("/delete/{hashId}")
    @PreAuthorize("hasAuthority('USERS_DELETE')")
    public ApiResponse<Void> delete(@PathVariable String hashId) {
        service.archive(hashId);
        return ApiResponse.success("User deleted", null);
    }

    @PostMapping("/reset-password/{hashId}")
    @PreAuthorize("hasAuthority('USERS_RESET_PASSWORD')")
    public ApiResponse<TemporaryPasswordResponse> resetPassword(@PathVariable String hashId) {
        return ApiResponse.success("Temporary password issued", service.resetPassword(hashId));
    }

    @PostMapping("/revoke-sessions/{hashId}")
    @PreAuthorize("hasAuthority('USERS_REVOKE_SESSIONS')")
    public ApiResponse<Map<String, Integer>> revokeSessions(@PathVariable String hashId) {
        return ApiResponse.success("Signed out of every device",
                Map.of("revoked", service.revokeSessions(hashId)));
    }
}
