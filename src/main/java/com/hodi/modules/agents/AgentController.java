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
    private final IntroducerService introducers;
    private final AgentPayoutAccountService accounts;
    private final AgentEarningsService earnings;

    // ── who may be named as having brought a buyer ────────────────────────────

    /** For the "Introduced by" picker on an enquiry, an offer or a booking — whoever may set those. */
    @GetMapping("/api/v1/agents/options")
    @PreAuthorize("hasAnyAuthority('BOOKINGS_MANAGE', 'ENQUIRIES_ASSIGN', 'PURCHASE_REQUESTS_DECIDE')")
    public ApiResponse<IntroducerService.IntroducerOptions> options() {
        return ApiResponse.success(introducers.options());
    }

    // ── where an agent is paid: the bank, on the register ─────────────────────

    @GetMapping("/api/v1/agents/{reference}/accounts")
    @PreAuthorize("hasAuthority('AGENTS_VIEW')")
    public ApiResponse<java.util.List<AgentPayoutAccountService.PayoutAccountResponse>> accountsOf(
            @PathVariable String reference) {
        return ApiResponse.success(accounts.forAgent(reference));
    }

    @PostMapping("/api/v1/agents/{reference}/accounts")
    @PreAuthorize("hasAuthority('AGENTS_DECIDE')")
    @RequestAction("ADD AN AGENT'S PAYOUT ACCOUNT")
    public ApiResponse<AgentPayoutAccountService.PayoutAccountResponse> addAccountFor(
            @PathVariable String reference,
            @Valid @RequestBody AgentPayoutAccountService.SavePayoutAccountRequest request) {
        return ApiResponse.success("Added", accounts.addFor(reference, request));
    }

    @PostMapping("/api/v1/agents/{reference}/accounts/{accountId}/verify")
    @PreAuthorize("hasAuthority('AGENTS_DECIDE')")
    @RequestAction("ASK THE BANK WHO HOLDS AN AGENT'S ACCOUNT")
    public ApiResponse<AgentPayoutAccountService.PayoutAccountResponse> verifyAccountFor(
            @PathVariable String reference, @PathVariable String accountId) {
        return ApiResponse.success("Asked", accounts.verifyFor(reference, accountId));
    }

    @PostMapping("/api/v1/agents/{reference}/accounts/{accountId}/default")
    @PreAuthorize("hasAuthority('AGENTS_DECIDE')")
    @RequestAction("SET AN AGENT'S DEFAULT PAYOUT ACCOUNT")
    public ApiResponse<AgentPayoutAccountService.PayoutAccountResponse> defaultAccountFor(
            @PathVariable String reference, @PathVariable String accountId) {
        return ApiResponse.success("Saved", accounts.makeDefaultFor(reference, accountId));
    }

    @PostMapping("/api/v1/agents/{reference}/accounts/{accountId}/remove")
    @PreAuthorize("hasAuthority('AGENTS_DECIDE')")
    @RequestAction("REMOVE AN AGENT'S PAYOUT ACCOUNT")
    public ApiResponse<Void> removeAccountFor(@PathVariable String reference, @PathVariable String accountId) {
        accounts.removeFor(reference, accountId);
        return ApiResponse.success("Removed", null);
    }

    // ── where an agent is paid: their own ─────────────────────────────────────

    @GetMapping("/api/v1/me/agent/accounts")
    @PreAuthorize("hasAuthority('AGENT_SELF_VIEW')")
    public ApiResponse<java.util.List<AgentPayoutAccountService.PayoutAccountResponse>> myAccounts() {
        return ApiResponse.success(accounts.mine());
    }

    @PostMapping("/api/v1/me/agent/accounts")
    @PreAuthorize("hasAuthority('AGENT_SELF_UPDATE')")
    @RequestAction("ADD MY PAYOUT ACCOUNT")
    public ApiResponse<AgentPayoutAccountService.PayoutAccountResponse> addMyAccount(
            @Valid @RequestBody AgentPayoutAccountService.SavePayoutAccountRequest request) {
        return ApiResponse.success("Added", accounts.addMine(request));
    }

    @PostMapping("/api/v1/me/agent/accounts/{accountId}/verify")
    @PreAuthorize("hasAuthority('AGENT_SELF_UPDATE')")
    @RequestAction("ASK THE BANK WHO HOLDS MY ACCOUNT")
    public ApiResponse<AgentPayoutAccountService.PayoutAccountResponse> verifyMyAccount(@PathVariable String accountId) {
        return ApiResponse.success("Asked", accounts.verifyMine(accountId));
    }

    @PostMapping("/api/v1/me/agent/accounts/{accountId}/default")
    @PreAuthorize("hasAuthority('AGENT_SELF_UPDATE')")
    @RequestAction("SET MY DEFAULT PAYOUT ACCOUNT")
    public ApiResponse<AgentPayoutAccountService.PayoutAccountResponse> defaultMyAccount(@PathVariable String accountId) {
        return ApiResponse.success("Saved", accounts.makeMineDefault(accountId));
    }

    @PostMapping("/api/v1/me/agent/accounts/{accountId}/remove")
    @PreAuthorize("hasAuthority('AGENT_SELF_UPDATE')")
    @RequestAction("REMOVE MY PAYOUT ACCOUNT")
    public ApiResponse<Void> removeMyAccount(@PathVariable String accountId) {
        accounts.removeMine(accountId);
        return ApiResponse.success("Removed", null);
    }

    // ── what an agent brought in, and earned ──────────────────────────────────

    @GetMapping("/api/v1/me/agent/introductions")
    @PreAuthorize("hasAuthority('AGENT_SELF_VIEW')")
    public ApiResponse<java.util.List<AgentEarningsService.IntroductionResponse>> myIntroductions() {
        return ApiResponse.success(earnings.myIntroductions());
    }

    @GetMapping("/api/v1/me/agent/commissions")
    @PreAuthorize("hasAuthority('AGENT_SELF_VIEW')")
    public ApiResponse<PagedResponse<com.hodi.modules.sellerops.CommissionService.CommissionResponse>> myCommissions(
            @ModelAttribute com.hodi.common.dto.PagedDataRequest request) {
        return ApiResponse.success(earnings.myCommissions(request));
    }

    @GetMapping("/api/v1/me/agent/totals")
    @PreAuthorize("hasAuthority('AGENT_SELF_VIEW')")
    public ApiResponse<AgentEarningsService.AgentTotals> myTotals() {
        return ApiResponse.success(earnings.myTotals());
    }

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
