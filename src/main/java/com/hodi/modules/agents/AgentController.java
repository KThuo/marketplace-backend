package com.hodi.modules.agents;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.agents.AgentService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The agent register, from both sides.
 *
 * <p>{@code AGENTS_VIEW} admits agents themselves as well as platform staff — the register is a directory.
 * The evidence behind an application is behind {@code AGENTS_EVIDENCE}, which is platform-only: an agent may
 * see who else is registered without seeing anybody's signature, address of origin or user agent.
 */
@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agents;

    // ── the register ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/agents/list")
    @PreAuthorize("hasAuthority('AGENTS_VIEW')")
    public ApiResponse<PagedResponse<AgentResponse>> list(@ModelAttribute AgentListRequest request) {
        return ApiResponse.success(agents.list(request));
    }

    @GetMapping("/api/v1/agents/counts")
    @PreAuthorize("hasAuthority('AGENTS_VIEW')")
    public ApiResponse<AgentCounts> counts() {
        return ApiResponse.success(agents.counts());
    }

    @GetMapping("/api/v1/agents/{reference}")
    @PreAuthorize("hasAuthority('AGENTS_VIEW')")
    public ApiResponse<AgentResponse> find(@PathVariable String reference) {
        return ApiResponse.success(agents.find(reference));
    }

    @PostMapping("/api/v1/agents/{reference}/decide")
    @PreAuthorize("hasAuthority('AGENTS_DECIDE')")
    @RequestAction("DECIDE AGENT APPLICATION")
    public ApiResponse<AgentResponse> decide(@PathVariable String reference,
                                             @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success("Decision recorded", agents.decide(reference, request));
    }

    // ── the evidence ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/agents/{reference}/agreement")
    @PreAuthorize("hasAuthority('AGENTS_EVIDENCE')")
    public ApiResponse<AgreementResponse> agreement(@PathVariable String reference) {
        return ApiResponse.success(agents.agreementFor(reference));
    }

    @GetMapping("/api/v1/agents/{reference}/signature")
    @PreAuthorize("hasAuthority('AGENTS_EVIDENCE')")
    public ApiResponse<SignatureResponse> signature(@PathVariable String reference) {
        return ApiResponse.success(agents.signatureFor(reference));
    }

    /**
     * The signature image itself.
     *
     * <p>Bytes, not a URL. The vault has no {@code urlFor} and should not gain one: a link to a specimen of
     * somebody's signature is a link that outlives the permission check that produced it.
     */
    @GetMapping("/api/v1/agents/{reference}/signature/image")
    @PreAuthorize("hasAuthority('AGENTS_EVIDENCE')")
    public ResponseEntity<byte[]> signatureImage(@PathVariable String reference) {
        StoredImage image = agents.signatureImage(reference);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        image.contentType() == null ? "image/png" : image.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(image.bytes());
    }

    // ── the agent's own ───────────────────────────────────────────────────────

    @GetMapping("/api/v1/me/agent")
    @PreAuthorize("hasAuthority('AGENT_SELF_VIEW')")
    public ApiResponse<AgentResponse> mine() {
        return ApiResponse.success(agents.mine());
    }

    @GetMapping("/api/v1/me/agent/agreement")
    @PreAuthorize("hasAuthority('AGENT_SELF_VIEW')")
    public ApiResponse<AgreementResponse> myAgreement() {
        return ApiResponse.success(agents.myAgreement());
    }

    @PostMapping("/api/v1/me/agent/update")
    @PreAuthorize("hasAuthority('AGENT_SELF_UPDATE')")
    @RequestAction("UPDATE MY AGENT DETAILS")
    public ApiResponse<AgentResponse> updateMine(@Valid @RequestBody UpdateAgentRequest request) {
        return ApiResponse.success("Saved", agents.updateMine(request));
    }
}
