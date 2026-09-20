package com.hodi.modules.disbursements;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.coop.CoopAnswer;
import com.hodi.infra.coop.CoopClient;
import com.hodi.infra.coop.CoopRoutes;
import com.hodi.infra.coop.CoopTransferService;
import com.hodi.infra.coop.CoopTransferService.ResolvedAccount;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ChangeSet;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.disbursements.DisbursementDtos.*;
import com.hodi.modules.payments.CoopChannel;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Money going out, from proposal to the bank's last word.
 *
 * <h2>The one flow with no undo</h2>
 *
 * <p>A payment that lands on the wrong booking is voided and re-applied. Money that reaches the wrong
 * account is gone. So every step here is a precondition for the next and none is skipped: the destination
 * is resolved to a name by the bank before a row exists; a second person approves that name and amount;
 * the send is claimed in its own transaction before the call leaves; and a transfer whose answer never
 * came is chased with a question, never with a second send.
 *
 * <h2>Send after commit, not inside it</h2>
 *
 * <p>The approval decision runs in a database transaction. Calling the bank inside it would hold a
 * connection for as long as Co-op takes, and a rollback after a successful send would leave money moved
 * against a row that says otherwise. So approval records the decision and registers the send to run once
 * that commits; the sweep picks up any approved row the send never reached.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementService {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    /** How long an approved row may sit unsent before the sweep sends it: an after-commit hook that never ran. */
    static final long UNSENT_GRACE_SECONDS = 120;

    private final DisbursementRepository rows;
    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final TenantRepository tenants;
    private final CoopTransferService coop;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final ConfigurationService configs;
    private final TransactionTemplate newTransaction;
    private final ObjectMapper mapper;

    // ── asking who holds the account ──────────────────────────────────────────

    /** Resolves an account to the name it is held in. Reads nothing of ours; asks the bank. */
    public ValidateResponse validate(ValidateRequest request) {
        ResolvedAccount resolved = resolve(request.bankCode(), request.accountNo());
        return new ValidateResponse(resolved.resolved(), resolved.accountNumber(), resolved.bankCode(),
                resolved.holderName(), resolved.resolved()
                        ? "Held by " + resolved.holderName() + "." : resolved.failure());
    }

    private ResolvedAccount resolve(String bankCode, String accountNo) {
        PaymentType enquiry = types.findByProviderType(CoopChannel.COOP_ACCOUNT_VALIDATION.name())
                .filter(PaymentType::isAvailable)
                .orElseThrow(() -> new HodiException(
                        "Account validation is switched off, so nothing can be sent.", HttpStatus.CONFLICT));
        return coop.validate(enquiry, accountNo == null ? null : accountNo.trim(),
                bankCode == null || bankCode.isBlank() ? "11" : bankCode.trim());
    }

    // ── proposing ─────────────────────────────────────────────────────────────

    /**
     * Proposes a transfer and puts it in front of a second person.
     *
     * <p>The account is validated here, whatever the form showed: the name approved is the one the bank
     * gave this server, not one the browser was told. A validation that fails refuses the proposal with
     * the bank's reason.
     */
    @Transactional
    public DisbursementResponse propose(ProposeRequest request) {
        String by = AuthContext.username();
        PaymentAccount source = sourceAccount();
        PaymentType channel = types.findById(source.getPaymentTypeId()).orElseThrow();

        String kind = request.payeeKind() == null ? "" : request.payeeKind().trim().toUpperCase(Locale.ROOT);
        Long tenantId = null;
        String payeeName;
        if (Disbursement.PAYEE_SELLER.equals(kind)) {
            Tenant tenant = tenants.findById(HashIdUtil.decodeId(request.tenantId()))
                    .filter(t -> t.getStatus() != AppConstant.STATUS_DELETED)
                    .orElseThrow(() -> new HodiException("Choose the seller organisation being paid.",
                            HttpStatus.BAD_REQUEST));
            tenantId = tenant.getId();
            payeeName = tenant.getName();
        } else if (Disbursement.PAYEE_OTHER.equals(kind)) {
            if (request.payeeName() == null || request.payeeName().isBlank()) {
                throw new HodiException("Say who is being paid.", HttpStatus.BAD_REQUEST);
            }
            payeeName = request.payeeName().trim();
        } else {
            throw new HodiException("Say who is being paid: a seller organisation, or somebody else.",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new HodiException("Enter the amount to send.", HttpStatus.BAD_REQUEST);
        }

        ResolvedAccount resolved = resolve(request.bankCode(), request.accountNo());
        if (!resolved.resolved()) {
            throw new HodiException("The account could not be confirmed: " + resolved.failure(),
                    HttpStatus.BAD_REQUEST);
        }

        Disbursement row = rows.saveAndFlush(Disbursement.builder()
                .reference(RrnGenerator.generate("DB"))
                .payeeKind(kind)
                .tenantId(tenantId)
                .payeeName(payeeName)
                .bankCode(resolved.bankCode())
                .accountNo(resolved.accountNumber())
                .validatedName(resolved.holderName())
                .validatedAt(OffsetDateTime.now())
                .amount(request.amount().setScale(2, RoundingMode.HALF_UP))
                .currency("KES")
                .purpose(request.purpose().trim())
                .narration(request.narration() == null || request.narration().isBlank()
                        ? clip(request.purpose().trim(), 160) : request.narration().trim())
                .sourceAccountId(source.getId())
                .state(Disbursement.AWAITING_APPROVAL)
                .callbackTimeoutSeconds(Math.max(300, configs.getInt(ConfigKey.COOP_CALLBACK_TIMEOUT_SECONDS)))
                .madeBy(by)
                .createdBy(by)
                .updatedBy(by)
                .build());

        /*
         * The checker approves what they can read. The snapshot is the whole of the decision: the money,
         * where it goes, the name the bank put on that account, and why.
         */
        ChangeSet.Snapshot what = ChangeSet.of()
                .put("amount", "Amount", row.getCurrency() + " " + MONEY.format(row.getAmount()))
                .put("payee", "Paid to", row.getPayeeName())
                .put("account", "Account", row.getAccountNo() + " at bank " + row.getBankCode())
                .put("validatedName", "Account held by (Co-op)", row.getValidatedName())
                .put("purpose", "Purpose", row.getPurpose())
                .put("source", "Sent from", label(source, channel))
                .put("madeBy", "Proposed by", by);
        approvals.submit(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, row.getId(), AppConstant.APPROVAL_ACTION_SEND,
                null, null,
                row.getCurrency() + " " + MONEY.format(row.getAmount()) + " to " + row.getValidatedName(),
                "Money out. Check the name Co-op resolved the account to against who is meant to be paid.",
                null, what);
        audit.record(AppConstant.AUDIT_DISBURSEMENT_PROPOSED, "Disbursement", row.getId(), null, snapshot(row));
        log.info("Disbursement {} proposed by {}: {} {} to {} ({})", row.getReference(), by, row.getCurrency(),
                row.getAmount(), row.getValidatedName(), row.getAccountNo());
        return toResponse(row);
    }

    /** The platform's own account the money leaves: its live PesaLink account. */
    private PaymentAccount sourceAccount() {
        PaymentType channel = types.findByProviderType(CoopChannel.COOP_PESALINK.name())
                .filter(PaymentType::isAvailable)
                .orElseThrow(() -> new HodiException("The PesaLink channel is switched off.", HttpStatus.CONFLICT));
        return accounts.findLiveForPlatform().stream()
                .filter(a -> channel.getId().equals(a.getPaymentTypeId()))
                .min(java.util.Comparator.comparing(PaymentAccount::getId))
                .orElseThrow(() -> new HodiException(
                        "No approved PesaLink account is set up for the platform to send from.", HttpStatus.CONFLICT));
    }

    // ── the decision ──────────────────────────────────────────────────────────

    /** Approved: recorded now, sent once this transaction commits. Called by the approval handler. */
    @Transactional
    public void approved(Long id, String checker) {
        Disbursement row = rows.lockById(id).orElseThrow();
        if (!Disbursement.AWAITING_APPROVAL.equals(row.getState())) return;
        String before = snapshot(row);
        row.setState(Disbursement.APPROVED);
        row.setCheckedBy(checker);
        row.setCheckedAt(OffsetDateTime.now());
        row.setUpdatedBy(checker);
        rows.save(row);
        audit.record(AppConstant.AUDIT_DISBURSEMENT_DECIDED, "Disbursement", id, before, snapshot(row));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch(id); }
            });
        } else {
            dispatch(id);
        }
    }

    /** Refused or sent back. Nothing was sent; nothing will be. */
    @Transactional
    public void refused(Long id, String decision, String reason, String checker) {
        Disbursement row = rows.lockById(id).orElseThrow();
        if (!Disbursement.AWAITING_APPROVAL.equals(row.getState())) return;
        String before = snapshot(row);
        row.setState(Disbursement.REFUSED);
        row.setCheckedBy(checker);
        row.setCheckedAt(OffsetDateTime.now());
        row.setDecisionReason(reason);
        row.setProcessingReason((AppConstant.APPROVAL_SENT_BACK.equals(decision) ? "Sent back" : "Refused")
                + (reason == null || reason.isBlank() ? "." : ": " + reason));
        row.setUpdatedBy(checker);
        rows.save(row);
        audit.record(AppConstant.AUDIT_DISBURSEMENT_DECIDED, "Disbursement", id, before, snapshot(row));
    }

    // ── sending ───────────────────────────────────────────────────────────────

    /**
     * Sends an approved transfer, once.
     *
     * <p>Three transactions and one HTTP call between them. The claim moves APPROVED to SENDING and
     * commits before anything leaves, so a process that dies mid-call leaves a row saying "we may have
     * sent this" rather than one that will be sent again. The outcome is recorded in its own transaction
     * afterwards. Nothing here ever re-sends: a SENDING row past its deadline is chased with an enquiry.
     */
    public void dispatch(Long id) {
        Disbursement claimed = newTransaction.execute(status -> {
            Disbursement row = rows.lockById(id).orElse(null);
            if (row == null || !Disbursement.APPROVED.equals(row.getState())) return null;
            row.setState(Disbursement.SENDING);
            row.setSentAt(OffsetDateTime.now());
            row.setProcessingReason("Sending to Co-op.");
            row.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
            return rows.save(row);
        });
        if (claimed == null) return;

        PaymentAccount source = accounts.findById(claimed.getSourceAccountId()).orElse(null);
        PaymentType channel = source == null ? null : types.findById(source.getPaymentTypeId()).orElse(null);
        if (source == null || channel == null || !channel.isAvailable()) {
            record(id, Disbursement.FAILED, null, null, "The source account or the PesaLink channel is no longer "
                    + "available, so nothing was sent.", null);
            return;
        }

        CoopClient.Outcome<Map<String, Object>> answer = coop.send(channel, claimed.getReference(),
                source.getAccountNo(),
                new ResolvedAccount(claimed.getAccountNo(), claimed.getBankCode(), claimed.getValidatedName(), null),
                claimed.getAmount(), claimed.getNarration(), callbackUrl());

        if (answer.neverSent()) {
            // A settings problem: nothing left this process, so nobody was paid.
            record(id, Disbursement.FAILED, null, null, "Not sent: " + answer.failure(), null);
            return;
        }
        if (!answer.succeeded()) {
            // The request left us and we cannot say what happened. In flight; the enquiry settles it.
            record(id, Disbursement.SENT, null, null, "Sent, but Co-op's acknowledgement was not read ("
                    + answer.failure() + "). The status enquiry will settle it.", null);
            return;
        }
        Map<String, Object> body = answer.value();
        CoopAnswer.Outcome envelope = CoopAnswer.read(body, pendingCodes(), pendingDescriptions());
        String said = CoopAnswer.description(body);
        if (envelope == CoopAnswer.Outcome.FAILED) {
            record(id, Disbursement.FAILED, CoopFtAnswer.read(body, pendingCodes(), pendingDescriptions()).code(),
                    null, "Co-op did not accept the transfer: " + said, toJson(body));
            return;
        }
        // "REQUEST ACCEPTED FOR PROCESSING": the money is confirmed by the callback or the enquiry, never here.
        record(id, Disbursement.SENT, null, CoopFtAnswer.read(body, Set0.EMPTY, Set0.EMPTY).transactionId(),
                "Accepted by Co-op: " + said + ". Waiting for the bank to confirm the transfer.", toJson(body));
    }

    private void record(Long id, String state, String code, String bankReference, String reason, String raw) {
        newTransaction.executeWithoutResult(status -> {
            Disbursement row = rows.lockById(id).orElseThrow();
            if (!Disbursement.SENDING.equals(row.getState())) return;
            String before = snapshot(row);
            row.setState(state);
            if (code != null) row.setResponseCode(code);
            if (bankReference != null) row.setBankReference(bankReference);
            if (raw != null) row.setRawResponse(raw);
            row.setProcessingReason(reason);
            if (Disbursement.FAILED.equals(state)) row.setSettledAt(OffsetDateTime.now());
            row.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
            rows.save(row);
            audit.record(AppConstant.AUDIT_DISBURSEMENT_SENT, "Disbursement", id, before, snapshot(row));
            log.info("Disbursement {} → {}: {}", row.getReference(), state, reason);
        });
    }

    // ── the answer ────────────────────────────────────────────────────────────

    /**
     * Asks Co-op what became of a transfer, and settles it when the answer is definite.
     *
     * @param counted whether this attempt counts against the automatic cap — false for a person
     */
    public String query(Long id, boolean counted, String by) {
        Disbursement row = rows.findById(id).orElseThrow(() -> new ResourceNotFoundException("Disbursement", String.valueOf(id)));
        if (!row.isOut()) return row.getState();
        PaymentType enquiry = types.findByProviderType(CoopChannel.COOP_FT_STATUS.name())
                .filter(PaymentType::isAvailable).orElse(null);
        int attempts = counted ? row.getStatusQueryAttempts() + 1 : row.getStatusQueryAttempts();
        if (enquiry == null) {
            return stillWaiting(id, row.getStatusQueryAttempts(),
                    "The Co-op transfer enquiry is switched off, so it cannot be asked. Settle this one by hand.");
        }
        CoopClient.Outcome<Map<String, Object>> answer = coop.statusOf(enquiry, row.getReference());
        if (answer.neverSent()) return stillWaiting(id, row.getStatusQueryAttempts(), answer.failure());
        if (!answer.succeeded()) {
            return stillWaiting(id, attempts, "Asked Co-op and could not get an answer (" + answer.failure() + ").");
        }
        return settle(id, answer.value(), attempts, by == null ? AppConstant.USERNAME_SYSTEM : by);
    }

    /**
     * Co-op calling back about a transfer. Correlated on our reference; untrusted callbacks settle nothing.
     *
     * @return the row it was about, or null when it matched none
     */
    public Disbursement callback(Map<String, Object> body, boolean trusted) {
        String reference = text(body.get("MessageReference"));
        Disbursement row = reference == null ? null : rows.findByReference(reference)
                .or(() -> rows.findByBankReference(reference)).orElse(null);
        if (row == null) {
            log.info("Co-op transfer callback matched no disbursement (reference {})", reference);
            return null;
        }
        if (!trusted) {
            log.warn("Unauthenticated Co-op transfer callback for {} ignored; the enquiry will settle it", row.getReference());
            return row;
        }
        settle(row.getId(), body, row.getStatusQueryAttempts(), "callback");
        return rows.findById(row.getId()).orElse(row);
    }

    /** One reading of the bank's answer, applied once. A success is never reversed by a later failure. */
    private String settle(Long id, Map<String, Object> body, int attempts, String by) {
        CoopFtAnswer.Reading reading = CoopFtAnswer.read(body, pendingCodes(), pendingDescriptions());
        String raw = toJson(body);
        return newTransaction.execute(status -> {
            Disbursement row = rows.lockById(id).orElseThrow();
            if (row.isTerminal()) return row.getState();
            String before = snapshot(row);
            row.setStatusQueryAttempts(attempts);
            if (reading.transactionId() != null) row.setBankReference(reading.transactionId());
            if (reading.code() != null) row.setResponseCode(reading.code());
            row.setResponseDescription(clip(reading.description(), 400));
            if (raw != null) row.setRawResponse(raw);
            switch (reading.outcome()) {
                case SUCCESS -> {
                    row.setState(Disbursement.SUCCEEDED);
                    row.setSettledAt(OffsetDateTime.now());
                    row.setProcessingReason("Confirmed by Co-op: " + reading.description());
                }
                case FAILED -> {
                    row.setState(Disbursement.FAILED);
                    row.setSettledAt(OffsetDateTime.now());
                    row.setProcessingReason("Co-op says it did not go through: " + reading.description());
                }
                case PENDING -> row.setProcessingReason("Co-op says it is still in progress: " + reading.description());
            }
            row.setUpdatedBy(by);
            rows.save(row);
            if (reading.outcome() != CoopAnswer.Outcome.PENDING) {
                audit.record(AppConstant.AUDIT_DISBURSEMENT_SETTLED, "Disbursement", id, before, snapshot(row));
            }
            log.info("Disbursement {} → {} ({})", row.getReference(), row.getState(), reading.description());
            return row.getState();
        });
    }

    private String stillWaiting(Long id, int attempts, String reason) {
        return newTransaction.execute(status -> {
            Disbursement row = rows.lockById(id).orElseThrow();
            if (row.isTerminal()) return row.getState();
            row.setStatusQueryAttempts(attempts);
            row.setProcessingReason(reason);
            row.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
            rows.save(row);
            return row.getState();
        });
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<DisbursementResponse> list(ListRequest request) {
        Specification<Disbursement> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", request.getState() == null || request.getState().isBlank()
                        ? null : request.getState().trim().toUpperCase(Locale.ROOT)),
                SearchSpecs.betweenDays("createdAt", request.getFrom(), request.getTo()));
        Page<Disbursement> page = rows.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public DisbursementDetail find(String hashId) {
        Disbursement row = rows.findById(HashIdUtil.decodeId(hashId))
                .filter(d -> d.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Disbursement", hashId));
        return new DisbursementDetail(toResponse(row), row.getRawResponse());
    }

    public DisbursementResponse toResponse(Disbursement d) {
        PaymentAccount source = accounts.findById(d.getSourceAccountId()).orElse(null);
        return new DisbursementResponse(
                HashIdUtil.encodeId(d.getId()), d.getReference(), d.getState(), stateLabel(d.getState()),
                d.isTerminal(), d.getPayeeKind(), HashIdUtil.encodeId(d.getTenantId()), d.getPayeeName(),
                d.getBankCode(), d.getAccountNo(), d.getValidatedName(), d.getValidatedAt(),
                d.getAmount(), d.getCurrency(), d.getPurpose(), d.getNarration(),
                HashIdUtil.encodeId(d.getSourceAccountId()),
                source == null ? null : source.getAccountNo(), source == null ? null : source.getAccountName(),
                d.getBankReference(), d.getResponseCode(), d.getResponseDescription(),
                d.getSentAt(), d.getSettledAt(), d.getStatusQueryAttempts(), d.getProcessingReason(),
                d.getMadeBy(), d.getCheckedBy(), d.getCheckedAt(), d.getDecisionReason(), d.getCreatedAt());
    }

    static String stateLabel(String state) {
        return switch (state == null ? "" : state) {
            case Disbursement.AWAITING_APPROVAL -> "Awaiting approval";
            case Disbursement.APPROVED -> "Approved, about to send";
            case Disbursement.SENDING -> "Sending";
            case Disbursement.SENT -> "Sent, awaiting the bank";
            case Disbursement.SUCCEEDED -> "Paid";
            case Disbursement.FAILED -> "Not paid";
            case Disbursement.REFUSED -> "Refused";
            default -> state;
        };
    }

    // ── small things ──────────────────────────────────────────────────────────

    private String callbackUrl() {
        String base = configs.getString(ConfigKey.PUBLIC_URL);
        if (base == null || base.isBlank()) return null;
        String root = base.trim().endsWith("/") ? base.trim().substring(0, base.trim().length() - 1) : base.trim();
        return root + CoopRoutes.FT_CALLBACK;
    }

    private java.util.Set<String> pendingCodes() {
        return CoopAnswer.csv(configs.getString(ConfigKey.COOP_PENDING_STATUS_CODES));
    }

    private java.util.Set<String> pendingDescriptions() {
        return CoopAnswer.csv(configs.getString(ConfigKey.COOP_PENDING_STATUS_DESCRIPTIONS));
    }

    /** Named empty sets for the one reading that wants no pending matching at all. */
    private static final class Set0 {
        static final java.util.Set<String> EMPTY = java.util.Set.of();
    }

    private static String label(PaymentAccount source, PaymentType channel) {
        return channel.getName() + " · " + source.getAccountNo()
                + (source.getAccountName() == null ? "" : " (" + source.getAccountName() + ")");
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String snapshot(Disbursement d) {
        return d.getReference() + " " + d.getCurrency() + " " + d.getAmount().toPlainString() + " to "
                + d.getValidatedName() + " (" + d.getAccountNo() + ") state=" + d.getState()
                + (d.getProcessingReason() == null ? "" : " — " + d.getProcessingReason());
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String clip(String value, int width) {
        return value == null || value.length() <= width ? value : value.substring(0, width);
    }

    /** For the sweep: what is out and past its deadline, and what was approved and never sent. */
    List<Disbursement> unanswered() { return rows.findOut(); }
    List<Disbursement> approvedAndUnsent() {
        return rows.findApprovedBefore(OffsetDateTime.now().minusSeconds(UNSENT_GRACE_SECONDS));
    }
    static BigDecimal zero() { return BigDecimal.ZERO; }
}
