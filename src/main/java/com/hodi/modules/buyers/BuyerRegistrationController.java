package com.hodi.modules.buyers;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.buyers.BuyerRegistrationService.RegistrationOutcome;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer self-registration, under {@code /public} because the whole point is somebody with no account.
 *
 * <p>The messages are deliberately identical whether or not the address already has an account — see
 * {@link BuyerRegistrationService} for why. Anything here that distinguished the two cases would turn an
 * open endpoint into an account-enumeration oracle.
 */
@RestController
@RequestMapping("/api/v1/public/buyers")
@RequiredArgsConstructor
public class BuyerRegistrationController {

    private final BuyerRegistrationService service;

    @PostMapping("/register")
    @RequestAction("BUYER_REGISTER")
    public ApiResponse<RegistrationOutcome> register(@Valid @RequestBody BuyerDtos.RegisterRequest request) {
        RegistrationOutcome outcome = service.register(request);
        return ApiResponse.success(
                outcome.verificationRequired()
                        ? "Check your email for a confirmation code."
                        : "Your account is ready — you can sign in.",
                outcome);
    }

    @PostMapping("/verify")
    @RequestAction("BUYER_VERIFY")
    public ApiResponse<Void> verify(@Valid @RequestBody BuyerDtos.VerifyRequest request) {
        service.verify(request);
        // No session is issued here. The buyer signs in afterwards with the password they chose, so a leaked
        // verification code on its own is not a way into an account.
        return ApiResponse.success("Confirmed — you can sign in now.", null);
    }

    @PostMapping("/verify/resend")
    @RequestAction("BUYER_VERIFY_RESEND")
    public ApiResponse<RegistrationOutcome> resend(@Valid @RequestBody BuyerDtos.ResendRequest request) {
        return ApiResponse.success("If that address is waiting to be confirmed, a new code is on its way.",
                service.resend(request));
    }
}
