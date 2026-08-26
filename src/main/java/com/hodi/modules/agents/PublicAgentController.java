package com.hodi.modules.agents;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.agents.AgentRegistrationService.RegisterAgentRequest;
import com.hodi.modules.agents.AgentRegistrationService.RegistrationOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Applying to become an agent, from outside.
 *
 * <p>Two endpoints and no more: read the terms, and apply having accepted them. Both are named individually
 * in {@code SecurityConfig} — a POST under {@code /api/v1/public} is not public by default, and this one
 * creates an account, so it should be listed where somebody auditing the public surface will read it.
 */
@RestController
@RequiredArgsConstructor
public class PublicAgentController {

    private final AgentRegistrationService registrations;
    private final SignatureService signatures;

    /**
     * The terms as they stand, with the version to send back.
     *
     * <p>The hash is returned so the page can show it beside the signature box — somebody about to sign
     * something should be able to see the fingerprint of what they are signing. It is not what the server
     * verifies against; that is recomputed at capture from the same source.
     */
    @GetMapping("/api/v1/public/agents/terms")
    public ApiResponse<SignatureService.Terms> terms() {
        return ApiResponse.success(signatures.currentTerms());
    }

    @PostMapping("/api/v1/public/agents/apply")
    @RequestAction("AGENT APPLICATION")
    public ApiResponse<RegistrationOutcome> apply(@Valid @RequestBody RegisterAgentRequest request,
                                                  HttpServletRequest http) {
        return ApiResponse.success("Application received", registrations.register(request, http));
    }
}
