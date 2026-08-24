package com.hodi.modules.auth;

import com.hodi.common.ApiResponse;
import com.hodi.modules.auth.dto.AuthDtos.ChangePasswordRequest;
import com.hodi.modules.auth.dto.AuthDtos.ChooseUsernameRequest;
import com.hodi.modules.auth.dto.AuthDtos.ForgotPasswordRequest;
import com.hodi.modules.auth.dto.AuthDtos.LoginRequest;
import com.hodi.modules.auth.dto.AuthDtos.LoginResponse;
import com.hodi.modules.auth.dto.AuthDtos.MeResponse;
import com.hodi.modules.auth.dto.AuthDtos.PasswordPolicyResponse;
import com.hodi.modules.auth.dto.AuthDtos.ResetPasswordRequest;
import com.hodi.modules.auth.dto.AuthDtos.SessionResponse;
import com.hodi.modules.auth.dto.AuthDtos.VerifyOtpRequest;
import com.hodi.infra.storage.StorageService;
import com.hodi.logging.RequestAction;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.principal.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Authentication endpoints.
 *
 * <p>The refresh token never appears in a response body — it goes out as an httpOnly cookie and comes back
 * the same way, so script on this origin cannot read it (see {@link RefreshCookies}). The access token does
 * appear in the body, deliberately: the client holds it in memory only, which is what keeps an XSS from
 * finding a renewable session in storage.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TwoFactorService twoFactor;
    private final PasswordResetService passwordReset;
    private final RefreshCookies cookies;
    private final StorageService storage;
    private final UserRepository users;

    // ── login ─────────────────────────────────────────────────────────────────

    @PostMapping("/login")
    @RequestAction("LOGIN")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest http) {
        var issued = authService.login(request, http.getHeader(HttpHeaders.USER_AGENT), clientIp(http));
        return withCookie(issued);
    }

    @PostMapping("/login/verify-otp")
    @RequestAction("LOGIN_VERIFY_OTP")
    public ResponseEntity<ApiResponse<LoginResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request,
                                                                HttpServletRequest http) {
        var issued = authService.verifyOtp(request, http.getHeader(HttpHeaders.USER_AGENT), clientIp(http));
        return withCookie(issued);
    }

    /**
     * Rotates the session.
     *
     * <p>Unauthenticated by routing: the access token it is replacing has usually expired, which is the whole
     * reason the client is here. The cookie is the credential, and {@code RefreshTokenService} is what
     * validates it.
     */
    @PostMapping("/refresh")
    @RequestAction("REFRESH")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @RequestParam(required = false) String sessionClass, HttpServletRequest http) {
        var presented = cookies.read(http, sessionClass);
        var issued = authService.refresh(presented.token(),
                http.getHeader(HttpHeaders.USER_AGENT), clientIp(http));
        return withCookie(issued);
    }

    /**
     * Ends the session presented.
     *
     * <p>Best-effort and idempotent: signing out twice, or with an already-dead token, is a success. Both
     * cookies are cleared rather than only the one matched — somebody signing out of a shared browser means
     * it, and leaving the other alive would be a surprise.
     */
    @PostMapping("/logout")
    @RequestAction("LOGOUT")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestParam(required = false) String sessionClass, HttpServletRequest http) {
        var presented = cookies.read(http, sessionClass);
        authService.logout(bearer(http), presented.token());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clear("ADMIN"))
                .header(HttpHeaders.SET_COOKIE, cookies.clear("BUYER"))
                .body(ApiResponse.success("Signed out", null));
    }

    // ── identity ──────────────────────────────────────────────────────────────

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.success(authService.me(AuthContext.requireUserId()));
    }

    @PostMapping("/username")
    @RequestAction("CHOOSE_USERNAME")
    public ApiResponse<MeResponse> chooseUsername(@Valid @RequestBody ChooseUsernameRequest request) {
        return ApiResponse.success("Username set",
                authService.chooseUsername(AuthContext.requireUserId(), request.username()));
    }

    @PostMapping("/avatar")
    @RequestAction("UPLOAD_AVATAR")
    public ApiResponse<Map<String, String>> avatar(@RequestParam("file") MultipartFile file) {
        Long userId = AuthContext.requireUserId();
        User user = users.findById(userId).orElseThrow();
        // The key is stored, not the URL: storage.s3.bucket decides where files live, and urlFor recomputes
        // the address on read, so a stored URL would freeze one deployment's arrangement into the data.
        var stored = storage.store(file, "avatars");
        user.setAvatarKey(stored.key());
        users.save(user);
        return ApiResponse.success("Photo updated", Map.of("avatarUrl", stored.url()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionResponse>> sessions() {
        return ApiResponse.success(authService.sessions(AuthContext.requireUserId()));
    }

    /** Signs the caller out everywhere, including here. The client treats it as a logout. */
    @PostMapping("/sessions/revoke-all")
    @RequestAction("REVOKE_ALL_SESSIONS")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> revokeAll() {
        int revoked = authService.revokeAllSessions(AuthContext.requireUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clear("ADMIN"))
                .header(HttpHeaders.SET_COOKIE, cookies.clear("BUYER"))
                .body(ApiResponse.success("Signed out of every device",
                        Map.of("revoked", revoked)));
    }

    // ── password ──────────────────────────────────────────────────────────────

    @PostMapping("/change-password")
    @RequestAction("CHANGE_PASSWORD")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(AuthContext.requireUserId(), request);
        return ApiResponse.success("Password changed — sign in again", null);
    }

    @PostMapping("/forgot-password")
    @RequestAction("FORGOT_PASSWORD")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                            HttpServletRequest http) {
        passwordReset.request(request.email(), clientIp(http));
        // Identical answer whether or not the address has an account. An endpoint that distinguishes them is
        // an account-enumeration oracle, and this one is unauthenticated.
        return ApiResponse.success(
                "If that address has an account, a reset link is on its way.", null);
    }

    @PostMapping("/reset-password")
    @RequestAction("RESET_PASSWORD")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.code(), request.newPassword());
        return ApiResponse.success("Password set — you can sign in now", null);
    }

    /** Public: the reset screen needs it, and whoever is on that screen cannot sign in by definition. */
    @GetMapping("/password-policy")
    public ApiResponse<PasswordPolicyResponse> passwordPolicy() {
        return ApiResponse.success(authService.passwordPolicy());
    }

    // ── two-factor ────────────────────────────────────────────────────────────

    @PostMapping("/totp/setup")
    @RequestAction("TOTP_SETUP")
    public ApiResponse<TwoFactorService.TotpSetup> totpSetup() {
        return ApiResponse.success(twoFactor.setup(AuthContext.requireUserId()));
    }

    @PostMapping("/totp/confirm")
    @RequestAction("TOTP_CONFIRM")
    public ApiResponse<MeResponse> totpConfirm(@RequestBody Map<String, String> body) {
        Long userId = AuthContext.requireUserId();
        twoFactor.confirm(userId, body.get("code"));
        return ApiResponse.success("Two-factor authentication is on", authService.me(userId));
    }

    @PostMapping("/totp/disable")
    @RequestAction("TOTP_DISABLE")
    public ApiResponse<MeResponse> totpDisable(@RequestBody Map<String, String> body) {
        Long userId = AuthContext.requireUserId();
        twoFactor.disable(userId, body.get("currentPassword"));
        return ApiResponse.success("Two-factor authentication is off", authService.me(userId));
    }

    @PostMapping("/sms-otp")
    @RequestAction("SMS_OTP_TOGGLE")
    public ApiResponse<MeResponse> smsOtp(@RequestBody Map<String, Boolean> body) {
        Long userId = AuthContext.requireUserId();
        twoFactor.setSmsOtp(userId, Boolean.TRUE.equals(body.get("enabled")));
        return ApiResponse.success("Updated", authService.me(userId));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Attaches the refresh cookie when a session was actually issued.
     *
     * <p>A challenge response carries no cookie: there is no session yet, and setting one would mean a
     * half-authenticated caller holding something that looks like a session.
     */
    private ResponseEntity<ApiResponse<LoginResponse>> withCookie(AuthService.SessionIssued issued) {
        if (issued.refreshToken() == null) {
            return ResponseEntity.ok(ApiResponse.success(issued.response()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.build(
                        issued.sessionClass(), issued.refreshToken(), issued.refreshMaxAgeSeconds()))
                .body(ApiResponse.success(issued.response()));
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring(7).trim();
    }

    /**
     * The caller's address, preferring the first hop in {@code X-Forwarded-For}.
     *
     * <p>Only trustworthy behind a proxy that sets it; taken as a hint for the audit trail rather than as an
     * access-control input, which is the only safe way to read a client-suppliable header.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
