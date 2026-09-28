package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.beneficiaries.PayoutAccountCheck;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Where an agent is paid.
 *
 * <p>Several accounts, one default, each confirmed with the bank the way a beneficiary is. Two people may
 * change them: the agent, on their own profile, and the bank, on the register. Neither needs the other's
 * approval — a one-person organisation has no second person, and the bank's confirmation of the holder is
 * the check that matters: only a {@code VERIFIED} account is ever paid to.
 *
 * <p>The bank is asked outside any transaction, as for beneficiaries: a database connection held open across
 * somebody else's server is one the rest of the site cannot have.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPayoutAccountService {

    private final AgentPayoutAccountRepository accounts;
    private final AgentProfileRepository agents;
    private final AgentService agentService;
    private final PayoutAccountCheck bank;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record PayoutAccountResponse(
            String id,
            String bankCode,
            String accountNo,
            String holderName,
            String verification,
            String confirmedName,
            OffsetDateTime confirmedAt,
            String verificationNote,
            boolean defaultAccount,
            /** Confirmed by the bank, so a settlement may pay into it. */
            boolean payable) {}

    public record SavePayoutAccountRequest(
            @Size(max = 4) String bankCode,
            @NotBlank(message = "Enter the account number") @Size(max = 32) String accountNo,
            @Size(max = 160) String holderName,
            /** Make it the one settlements propose. The first account an agent adds is the default regardless. */
            Boolean makeDefault) {}

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PayoutAccountResponse> mine() {
        return listFor(agentService.requireMine());
    }

    @Transactional(readOnly = true)
    public List<PayoutAccountResponse> forAgent(String agentRef) {
        return listFor(requireAgent(agentRef));
    }

    @Transactional(readOnly = true)
    public List<PayoutAccountResponse> listFor(AgentProfile agent) {
        return accounts.findLiveFor(agent.getId()).stream().map(AgentPayoutAccountService::toResponse).toList();
    }

    /** The account a settlement proposes for this agent, if there is one it may pay into. */
    @Transactional(readOnly = true)
    public java.util.Optional<AgentPayoutAccount> payableDefault(Long agentId) {
        return accounts.findDefault(agentId).filter(AgentPayoutAccount::isVerified)
                .or(() -> accounts.findLiveFor(agentId).stream().filter(AgentPayoutAccount::isVerified).findFirst());
    }

    // ── writing: the agent's own ──────────────────────────────────────────────

    public PayoutAccountResponse addMine(SavePayoutAccountRequest request) {
        return add(agentService.requireMine(), request);
    }

    public PayoutAccountResponse verifyMine(String accountHashId) {
        return verify(agentService.requireMine(), accountHashId);
    }

    @Transactional
    public PayoutAccountResponse makeMineDefault(String accountHashId) {
        return makeDefault(agentService.requireMine(), accountHashId);
    }

    @Transactional
    public void removeMine(String accountHashId) {
        remove(agentService.requireMine(), accountHashId);
    }

    // ── writing: the bank's, on the register ──────────────────────────────────

    public PayoutAccountResponse addFor(String agentRef, SavePayoutAccountRequest request) {
        return add(requireAgent(agentRef), request);
    }

    public PayoutAccountResponse verifyFor(String agentRef, String accountHashId) {
        return verify(requireAgent(agentRef), accountHashId);
    }

    @Transactional
    public PayoutAccountResponse makeDefaultFor(String agentRef, String accountHashId) {
        return makeDefault(requireAgent(agentRef), accountHashId);
    }

    @Transactional
    public void removeFor(String agentRef, String accountHashId) {
        remove(requireAgent(agentRef), accountHashId);
    }

    // ── the work ──────────────────────────────────────────────────────────────

    private PayoutAccountResponse add(AgentProfile agent, SavePayoutAccountRequest request) {
        String bankCode = bankCode(request.bankCode());
        String accountNo = accountNo(request.accountNo());
        accounts.findLive(agent.getId(), bankCode, accountNo).ifPresent(existing -> {
            throw new HodiException("That account is already on " + agent.getFullName() + "'s profile.",
                    HttpStatus.CONFLICT);
        });
        PayoutAccountCheck.Answer answer = bank.check(bankCode, accountNo);
        return save(agent, request, bankCode, accountNo, answer);
    }

    @Transactional
    protected PayoutAccountResponse save(AgentProfile agent, SavePayoutAccountRequest request, String bankCode,
                                         String accountNo, PayoutAccountCheck.Answer answer) {
        boolean first = accounts.findLiveFor(agent.getId()).isEmpty();
        boolean makeDefault = first || Boolean.TRUE.equals(request.makeDefault());
        if (makeDefault) clearDefault(agent.getId());
        AgentPayoutAccount row = AgentPayoutAccount.builder()
                .agentProfileId(agent.getId())
                .bankCode(bankCode)
                .accountNo(accountNo)
                .holderName(blankToNull(request.holderName()))
                .defaultAccount(makeDefault)
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();
        applyAnswer(row, answer);
        AgentPayoutAccount saved = accounts.save(row);
        audit.record(AppConstant.ACTION_CREATE, "AgentPayoutAccount", saved.getId(), null,
                agent.getReference() + " " + snapshot(saved));
        log.info("{} added payout account ••{} for agent {} ({})", AuthContext.username(),
                tail(accountNo), agent.getReference(), saved.getVerification());
        return toResponse(saved);
    }

    private PayoutAccountResponse verify(AgentProfile agent, String accountHashId) {
        AgentPayoutAccount row = requireOwn(agent, accountHashId);
        PayoutAccountCheck.Answer answer = bank.check(row.getBankCode(), row.getAccountNo());
        return applyVerification(row, answer);
    }

    @Transactional
    protected PayoutAccountResponse applyVerification(AgentPayoutAccount row, PayoutAccountCheck.Answer answer) {
        String before = snapshot(row);
        applyAnswer(row, answer);
        row.setUpdatedBy(AuthContext.username());
        AgentPayoutAccount saved = accounts.save(row);
        audit.record(AppConstant.ACTION_UPDATE, "AgentPayoutAccount", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    private PayoutAccountResponse makeDefault(AgentProfile agent, String accountHashId) {
        AgentPayoutAccount row = requireOwn(agent, accountHashId);
        if (row.isDefaultAccount()) return toResponse(row);
        clearDefault(agent.getId());
        row.setDefaultAccount(true);
        row.setUpdatedBy(AuthContext.username());
        AgentPayoutAccount saved = accounts.save(row);
        audit.record(AppConstant.ACTION_UPDATE, "AgentPayoutAccount", saved.getId(), null,
                "default " + snapshot(saved));
        return toResponse(saved);
    }

    private void remove(AgentProfile agent, String accountHashId) {
        AgentPayoutAccount row = requireOwn(agent, accountHashId);
        row.setStatus(AppConstant.STATUS_DELETED);
        row.setStatusFlag(AppConstant.FLAG_DELETED);
        row.setDefaultAccount(false);
        row.setUpdatedBy(AuthContext.username());
        accounts.save(row);
        // The next one along becomes the default, so a settlement always has one to propose.
        accounts.findLiveFor(agent.getId()).stream().findFirst().ifPresent(next -> {
            next.setDefaultAccount(true);
            accounts.save(next);
        });
        audit.record(AppConstant.ACTION_DELETE, "AgentPayoutAccount", row.getId(), snapshot(row), null);
    }

    private void clearDefault(Long agentId) {
        accounts.findDefault(agentId).ifPresent(current -> {
            current.setDefaultAccount(false);
            accounts.saveAndFlush(current);
        });
    }

    private static void applyAnswer(AgentPayoutAccount row, PayoutAccountCheck.Answer answer) {
        if (answer.confirmed()) {
            row.setVerification(AgentPayoutAccount.VERIFIED);
            row.setConfirmedName(answer.holderName().trim());
            row.setConfirmedAt(OffsetDateTime.now());
            row.setVerificationNote(null);
            if (answer.bankCode() != null && !answer.bankCode().isBlank()) row.setBankCode(answer.bankCode());
        } else {
            row.setVerification(AgentPayoutAccount.UNVERIFIED);
            row.setConfirmedName(null);
            row.setConfirmedAt(null);
            row.setVerificationNote(answer.failure());
        }
    }

    // ── lookups ───────────────────────────────────────────────────────────────

    private AgentProfile requireAgent(String agentRef) {
        return agents.findByReference(agentRef)
                .filter(a -> a.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", agentRef));
    }

    /** An account of this agent's, or not found — another agent's is not theirs to see. */
    private AgentPayoutAccount requireOwn(AgentProfile agent, String accountHashId) {
        return accounts.findById(HashIdUtil.decodeId(accountHashId))
                .filter(a -> a.isLive() && a.getAgentProfileId().equals(agent.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payout account", accountHashId));
    }

    private static String bankCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty()) code = "11";
        if (!code.matches("\\d{1,4}")) throw new HodiException("A bank code is up to four digits.", HttpStatus.BAD_REQUEST);
        return "0000".substring(code.length()) + code;
    }

    private static String accountNo(String raw) {
        String account = raw == null ? "" : raw.trim();
        if (account.isEmpty()) throw new HodiException("Enter the account number.", HttpStatus.BAD_REQUEST);
        return account;
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private static String tail(String accountNo) {
        return accountNo.length() <= 4 ? accountNo : accountNo.substring(accountNo.length() - 4);
    }

    private static String snapshot(AgentPayoutAccount a) {
        return a.getBankCode() + " ••" + tail(a.getAccountNo()) + " " + a.getVerification()
                + (a.getConfirmedName() == null ? "" : " held by " + a.getConfirmedName())
                + (a.isDefaultAccount() ? " (default)" : "");
    }

    static PayoutAccountResponse toResponse(AgentPayoutAccount a) {
        return new PayoutAccountResponse(HashIdUtil.encodeId(a.getId()), a.getBankCode(), a.getAccountNo(),
                a.getHolderName(), a.getVerification(), a.getConfirmedName(), a.getConfirmedAt(),
                a.getVerificationNote(), a.isDefaultAccount(), a.isVerified());
    }
}
