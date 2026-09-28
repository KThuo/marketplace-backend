package com.hodi.modules.beneficiaries;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ChangeSet;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.banks.Bank;
import com.hodi.modules.banks.BankRepository;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.*;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The people and companies an organisation pays, and the two things that make one safe to pay.
 *
 * <h2>The bank's name, not the typed one</h2>
 *
 * <p>Every beneficiary is registered against an account, and the bank is asked who holds it — at registration,
 * and again whenever the account changes. What comes back is stored as {@code confirmedName} and is what a
 * checker later reads when approving a payment. An account the bank cannot confirm is saved, marked
 * {@code UNVERIFIED} with the reason, and cannot be paid until {@link #verify} gets an answer: the seller is
 * told at registration, not the maker at payment time.
 *
 * <h2>Maker/Checker on where the money goes</h2>
 *
 * <p>A new beneficiary, and any change to the bank code or account number, is written at {@code STATUS_NEW}
 * and waits for a second person holding {@code BENEFICIARIES_APPROVE} — the same arrangement a payment
 * account has, for the same reason: a changed account is how money is diverted, and one person's session is
 * not two people's agreement. Contact details, the type and the notes change without ceremony.
 *
 * <h2>Whose it is</h2>
 *
 * <p>An owner's staff manage their own. The bank's staff manage any owner's, naming the owner, because on a
 * development the bank manages spending for it is the bank that registers who is paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaries;
    private final BeneficiaryTypeRepository types;
    private final PayoutAccountCheck bank;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final TenantRepository tenants;
    private final BankRepository institutions;

    /** Whose beneficiary this is: exactly one of the two, never neither. */
    record Owner(Long tenantId, Long institutionId) {
        String kind() { return institutionId != null ? "INSTITUTION" : "TENANT"; }
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<BeneficiaryResponse> list(BeneficiaryListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<Beneficiary> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                scope(caller, request.getTenantId(), request.getInstitutionId()),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("typeId", HashIdUtil.decodeId(request.getTypeId())),
                SearchSpecs.eq("verification", upperOrNull(request.getVerification())));
        var page = beneficiaries.findAll(spec, request.toPageable(Sort.by("name")));
        Names names = names(page.getContent());
        return PagedResponse.from(page, b -> toResponse(b, names, caller));
    }

    @Transactional(readOnly = true)
    public BeneficiaryResponse find(String hashId) {
        UserPrincipal caller = AuthContext.require();
        Beneficiary row = requireVisible(hashId, caller);
        return toResponse(row, names(List.of(row)), caller);
    }

    /** What a payment form may pick from: live and verified, for one owner. */
    @Transactional(readOnly = true)
    public List<PayableBeneficiary> payable(String tenantHash, String institutionHash) {
        Owner owner = ownerFor(AuthContext.require(), tenantHash, institutionHash);
        List<Beneficiary> rows = beneficiaries.findPayable(owner.tenantId(), owner.institutionId());
        Names names = names(rows);
        return rows.stream().map(b -> new PayableBeneficiary(HashIdUtil.encodeId(b.getId()), b.getReference(),
                b.getName(), names.typeName(b.getTypeId()), b.getBankCode(), b.getAccountNo(),
                b.getConfirmedName())).toList();
    }

    /** Who holds an account. Reads nothing of ours; asks the bank, so the form can show the name first. */
    public AccountCheckResponse check(CheckAccountRequest request) {
        PayoutAccountCheck.Answer answer = bank.check(request.bankCode(), request.accountNo());
        return new AccountCheckResponse(answer.confirmed(), answer.accountNo(), answer.bankCode(),
                answer.holderName(), answer.confirmed() ? "Held by " + answer.holderName() + "." : answer.failure());
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Not transactional as a whole: the bank is asked between the checks and the write, and a database
     * connection held open across a call to somebody else's server is one the rest of the site cannot have.
     */
    public BeneficiaryResponse create(SaveBeneficiaryRequest request) {
        UserPrincipal caller = AuthContext.require();
        Owner owner = ownerFor(caller, request.tenantId(), request.institutionId());
        BeneficiaryType type = requireType(request.typeId());
        String bankCode = bankCode(request.bankCode());
        String accountNo = accountNo(request.accountNo());
        requirePayoutFree(owner, bankCode, accountNo, -1L);

        PayoutAccountCheck.Answer answer = bank.check(bankCode, accountNo);
        return save(owner, type, request, bankCode, accountNo, answer, caller);
    }

    @Transactional
    protected BeneficiaryResponse save(Owner owner, BeneficiaryType type, SaveBeneficiaryRequest request,
                                       String bankCode, String accountNo, PayoutAccountCheck.Answer answer,
                                       UserPrincipal caller) {
        Beneficiary row = Beneficiary.builder()
                .reference(nextReference())
                .tenantId(owner.tenantId()).institutionId(owner.institutionId())
                .typeId(type.getId())
                .name(request.name().trim())
                .kraPin(blankToNull(request.kraPin()))
                .contactName(blankToNull(request.contactName()))
                .contactPhone(blankToNull(request.contactPhone()))
                .contactEmail(blankToNull(request.contactEmail()))
                .bankCode(bankCode).accountNo(accountNo)
                .notes(blankToNull(request.notes()))
                .createdBy(caller.getUsername()).updatedBy(caller.getUsername())
                .build();
        applyAnswer(row, answer);
        // Written, and deliberately not live: nothing can be paid to it until a second person agrees.
        row.setStatus(AppConstant.STATUS_NEW);
        row.setStatusFlag(AppConstant.FLAG_NEW);
        Beneficiary saved = beneficiaries.save(row);

        approvals.submitOrRestate(AppConstant.APPROVAL_ENTITY_BENEFICIARY, saved.getId(),
                AppConstant.APPROVAL_ACTION_CREATE, owner.tenantId(), owner.institutionId(),
                saved.getName() + " — " + nameOf(owner),
                "A new beneficiary, " + type.getName().toLowerCase() + ". Nothing can be paid to it until approved."
                        + (saved.isVerified() ? "" : " The bank has not confirmed the account."),
                null, describe(saved, type));
        audit.record(AppConstant.ACTION_CREATE, "Beneficiary", saved.getId(), null, snapshot(saved));
        log.info("{} registered beneficiary {} for {}", caller.getUsername(), saved.getReference(), nameOf(owner));
        return toResponse(saved, names(List.of(saved)), caller);
    }

    /**
     * Edits. A change to the account goes back through the bank and the checker; anything else is direct.
     *
     * <p>Not transactional as a whole, for {@link #create}'s reason; the write is.
     */
    public BeneficiaryResponse update(String hashId, SaveBeneficiaryRequest request) {
        UserPrincipal caller = AuthContext.require();
        Beneficiary row = requireOwn(hashId, caller);
        Owner owner = new Owner(row.getTenantId(), row.getInstitutionId());
        BeneficiaryType type = requireType(request.typeId());
        String bankCode = bankCode(request.bankCode());
        String accountNo = accountNo(request.accountNo());
        boolean payoutChanged = !bankCode.equals(row.getBankCode()) || !accountNo.equals(row.getAccountNo());
        if (payoutChanged) requirePayoutFree(owner, bankCode, accountNo, row.getId());

        PayoutAccountCheck.Answer answer = payoutChanged ? bank.check(bankCode, accountNo) : null;
        return applyUpdate(row, owner, type, request, bankCode, accountNo, answer, caller);
    }

    @Transactional
    protected BeneficiaryResponse applyUpdate(Beneficiary row, Owner owner, BeneficiaryType type,
                                              SaveBeneficiaryRequest request, String bankCode, String accountNo,
                                              PayoutAccountCheck.Answer answer, UserPrincipal caller) {
        String before = snapshot(row);
        ChangeSet.Snapshot beforeSnap = describe(row, currentType(row));
        boolean payoutChanged = answer != null;

        row.setTypeId(type.getId());
        row.setName(request.name().trim());
        row.setKraPin(blankToNull(request.kraPin()));
        row.setContactName(blankToNull(request.contactName()));
        row.setContactPhone(blankToNull(request.contactPhone()));
        row.setContactEmail(blankToNull(request.contactEmail()));
        row.setNotes(blankToNull(request.notes()));
        row.setUpdatedBy(caller.getUsername());

        if (payoutChanged) {
            row.setBankCode(bankCode);
            row.setAccountNo(accountNo);
            applyAnswer(row, answer);
            /*
             * Out of use until somebody approves the new account. The field most likely to be edited is the
             * one that decides where the money goes, and an unapproved destination that stays payable is the
             * exact failure Maker/Checker is for.
             */
            row.setStatus(AppConstant.STATUS_NEW);
            row.setStatusFlag(AppConstant.FLAG_NEW);
        }
        Beneficiary saved = beneficiaries.save(row);

        boolean pending = saved.getStatus() == AppConstant.STATUS_NEW;
        if (pending) {
            // A new row edited before anybody decided restates the same request; a payout change on a live
            // row opens a fresh one. Either way the checker reads one difference from the last approved state.
            String action = AppConstant.APPROVAL_ACTION_CREATE.equals(pendingAction(saved))
                    ? AppConstant.APPROVAL_ACTION_CREATE : AppConstant.ACTION_UPDATE;
            approvals.submitOrRestate(AppConstant.APPROVAL_ENTITY_BENEFICIARY, saved.getId(), action,
                    owner.tenantId(), owner.institutionId(),
                    saved.getName() + " — " + nameOf(owner),
                    "The account this beneficiary is paid into changed. Nothing can be paid to it until approved."
                            + (saved.isVerified() ? "" : " The bank has not confirmed the new account."),
                    beforeSnap, describe(saved, type));
        }
        audit.record(AppConstant.ACTION_UPDATE, "Beneficiary", saved.getId(), before, snapshot(saved));
        return toResponse(saved, names(List.of(saved)), caller);
    }

    /** Asks the bank again about an unconfirmed account. Not transactional, for {@link #create}'s reason. */
    public BeneficiaryResponse verify(String hashId) {
        UserPrincipal caller = AuthContext.require();
        Beneficiary row = requireOwn(hashId, caller);
        PayoutAccountCheck.Answer answer = bank.check(row.getBankCode(), row.getAccountNo());
        return applyVerification(row, answer, caller);
    }

    @Transactional
    protected BeneficiaryResponse applyVerification(Beneficiary row, PayoutAccountCheck.Answer answer,
                                                    UserPrincipal caller) {
        String before = snapshot(row);
        applyAnswer(row, answer);
        row.setUpdatedBy(caller.getUsername());
        Beneficiary saved = beneficiaries.save(row);
        audit.record(AppConstant.ACTION_UPDATE, "Beneficiary", saved.getId(), before, snapshot(saved));
        return toResponse(saved, names(List.of(saved)), caller);
    }

    /**
     * Withdraw a beneficiary from use, or bring one back.
     *
     * <p>Deactivating needs nobody's agreement — stopping payments to somebody is the safe direction. Bringing
     * one back is allowed directly too: the account was approved, and nothing about it has changed.
     */
    @Transactional
    public String setStatus(String hashId, boolean active) {
        UserPrincipal caller = AuthContext.require();
        Beneficiary row = requireOwn(hashId, caller);
        if (active && row.getStatus() == AppConstant.STATUS_NEW) {
            throw new HodiException("That beneficiary is waiting for approval; it cannot be activated by hand.",
                    HttpStatus.CONFLICT);
        }
        String before = snapshot(row);
        row.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        row.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        row.setUpdatedBy(caller.getUsername());
        beneficiaries.save(row);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE, "Beneficiary",
                row.getId(), before, snapshot(row));
        return active
                ? row.getName() + " can be paid again" + (row.isVerified() ? "." : " once the bank confirms the account.")
                : row.getName() + " is deactivated. Nothing more will be paid to them.";
    }

    // ── the decision ──────────────────────────────────────────────────────────

    /** Approved: it may be paid, once the bank has confirmed the account. Called by the approval handler. */
    @Transactional
    public void applyApproval(Long id, String approvedBy) {
        Beneficiary row = beneficiaries.findById(id).orElse(null);
        if (row == null) return;
        row.setStatus(AppConstant.STATUS_ACTIVE);
        row.setStatusFlag(AppConstant.FLAG_ACTIVE);
        row.setUpdatedBy(approvedBy);
        beneficiaries.save(row);
        log.info("Beneficiary {} approved by {}", row.getReference(), approvedBy);
    }

    /** Refused: it stays, switched off, so the details are there to correct. */
    @Transactional
    public void applyRefusal(Long id, String decision, String reason) {
        Beneficiary row = beneficiaries.findById(id).orElse(null);
        if (row == null) return;
        row.setStatus(AppConstant.STATUS_INACTIVE);
        row.setStatusFlag(AppConstant.FLAG_INACTIVE);
        beneficiaries.save(row);
        log.info("Beneficiary {} {}: {}", row.getReference(), decision, reason);
    }

    // ── rules ─────────────────────────────────────────────────────────────────

    private void applyAnswer(Beneficiary row, PayoutAccountCheck.Answer answer) {
        if (answer.confirmed()) {
            row.setVerification(Beneficiary.VERIFIED);
            row.setConfirmedName(answer.holderName().trim());
            row.setConfirmedAt(OffsetDateTime.now());
            row.setVerificationNote(null);
            if (answer.bankCode() != null && !answer.bankCode().isBlank()) row.setBankCode(answer.bankCode());
        } else {
            row.setVerification(Beneficiary.UNVERIFIED);
            row.setConfirmedName(null);
            row.setConfirmedAt(null);
            row.setVerificationNote(answer.failure());
        }
    }

    private void requirePayoutFree(Owner owner, String bankCode, String accountNo, Long exceptId) {
        beneficiaries.findPayoutClash(owner.tenantId(), owner.institutionId(), bankCode, accountNo, exceptId)
                .ifPresent(other -> {
                    throw new HodiException("That account is already registered as " + other.getName()
                            + " (" + other.getReference() + ").", HttpStatus.CONFLICT);
                });
    }

    /** The owner named by platform staff, or derived from everybody else — never chosen by them. */
    private Owner ownerFor(UserPrincipal caller, String tenantHash, String institutionHash) {
        Long tenantId = HashIdUtil.decodeId(tenantHash);
        Long institutionId = HashIdUtil.decodeId(institutionHash);
        if (caller.isPlatformStaff()) {
            if ((tenantId == null) == (institutionId == null)) {
                throw new HodiException("Say which organisation this beneficiary belongs to: a seller or a bank, "
                        + "not both and not neither.", HttpStatus.BAD_REQUEST);
            }
            return new Owner(tenantId, institutionId);
        }
        if (caller.getInstitutionId() != null) {
            if (tenantId != null || (institutionId != null && !institutionId.equals(caller.getInstitutionId()))) {
                throw new HodiException("That is not your organisation.", HttpStatus.FORBIDDEN);
            }
            return new Owner(null, caller.getInstitutionId());
        }
        if (caller.getTenantId() != null) {
            if (institutionId != null || (tenantId != null && !tenantId.equals(caller.getTenantId()))) {
                throw new HodiException("That is not your organisation.", HttpStatus.FORBIDDEN);
            }
            return new Owner(caller.getTenantId(), null);
        }
        throw new HodiException("You do not belong to an organisation that pays beneficiaries.", HttpStatus.FORBIDDEN);
    }

    /** The rows this caller may list: the platform's choice of owner, or their own organisation only. */
    private Specification<Beneficiary> scope(UserPrincipal caller, String tenantHash, String institutionHash) {
        if (caller.isPlatformStaff()) {
            return SearchSpecs.allOf(
                    SearchSpecs.eq("tenantId", HashIdUtil.decodeId(tenantHash)),
                    SearchSpecs.eq("institutionId", HashIdUtil.decodeId(institutionHash)));
        }
        if (caller.getInstitutionId() != null) return SearchSpecs.eq("institutionId", caller.getInstitutionId());
        if (caller.getTenantId() != null) return SearchSpecs.eq("tenantId", caller.getTenantId());
        return (root, query, cb) -> cb.disjunction();
    }

    private Beneficiary requireVisible(String hashId, UserPrincipal caller) {
        Beneficiary row = beneficiaries.findById(HashIdUtil.decodeId(hashId))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary", hashId));
        // Not found rather than forbidden: whose suppliers an organisation has is itself information.
        if (!caller.isPlatformStaff() && !row.belongsTo(caller.getTenantId(), caller.getInstitutionId())) {
            throw new ResourceNotFoundException("Beneficiary", hashId);
        }
        return row;
    }

    private Beneficiary requireOwn(String hashId, UserPrincipal caller) {
        return requireVisible(hashId, caller);
    }

    private BeneficiaryType requireType(String hashId) {
        return types.findById(HashIdUtil.decodeId(hashId))
                .filter(BeneficiaryType::isLive)
                .orElseThrow(() -> new HodiException("Choose a beneficiary type that is available.", HttpStatus.BAD_REQUEST));
    }

    private BeneficiaryType currentType(Beneficiary row) {
        return types.findById(row.getTypeId()).orElse(null);
    }

    private String pendingAction(Beneficiary row) {
        return approvals.pendingFor(AppConstant.APPROVAL_ENTITY_BENEFICIARY, row.getId(),
                        AppConstant.APPROVAL_ACTION_CREATE)
                .map(w -> AppConstant.APPROVAL_ACTION_CREATE).orElse(AppConstant.ACTION_UPDATE);
    }

    private String nextReference() {
        for (int i = 0; i < 5; i++) {
            String candidate = RrnGenerator.generate("BN");
            if (!beneficiaries.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a reference; try again.", HttpStatus.CONFLICT);
    }

    /**
     * The clearing code, padded to the four digits the bank quotes it in — "0011" for Co-op itself.
     *
     * <p>Padded here, before the duplicate check and the write, and not only in the bank's answer: the first
     * version compared the typed "11" against a stored "0011", found no clash, and hit the unique index instead.
     * Stored padded because that is what a disbursement stores and what the transfer call unpads from.
     */
    private static String bankCode(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty()) code = "11";
        if (!code.matches("\\d{1,4}")) throw new HodiException("A bank code is up to four digits.", HttpStatus.BAD_REQUEST);
        return "0000".substring(code.length()) + code;
    }

    private static String accountNo(String raw) {
        String account = raw == null ? "" : raw.trim();
        if (account.isEmpty()) throw new HodiException("Enter the account number they are paid into.", HttpStatus.BAD_REQUEST);
        return account;
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private static String upperOrNull(String v) { return v == null || v.isBlank() ? null : v.trim().toUpperCase(); }

    // ── the checker's view and the audit's ────────────────────────────────────

    /** Every field a checker weighs, by its own label. Nothing here is secret. */
    private ChangeSet.Snapshot describe(Beneficiary b, BeneficiaryType type) {
        return ChangeSet.of()
                .put("name", "Name", b.getName())
                .put("type", "Kind of payee", type == null ? null : type.getName())
                .put("bankCode", "Bank code", b.getBankCode())
                .put("accountNo", "Account number", b.getAccountNo())
                .put("confirmedName", "Held by, per the bank", b.getConfirmedName())
                .put("verification", "Confirmed with the bank", b.isVerified() ? "Yes" : "No — " + b.getVerificationNote())
                .put("kraPin", "KRA PIN", b.getKraPin())
                .put("contact", "Contact", b.getContactName());
    }

    private static String snapshot(Beneficiary b) {
        return b.getReference() + " " + b.getName() + " type=" + b.getTypeId() + " bank=" + b.getBankCode()
                + " account=" + b.getAccountNo() + " verification=" + b.getVerification()
                + " confirmed=" + b.getConfirmedName() + " status=" + b.getStatus();
    }

    // ── rows ──────────────────────────────────────────────────────────────────

    private record Names(Map<Long, String> tenants, Map<Long, String> institutions, Map<Long, BeneficiaryType> types) {
        String typeName(Long id) { BeneficiaryType t = types.get(id); return t == null ? null : t.getName(); }
    }

    private Names names(List<Beneficiary> rows) {
        Map<Long, String> tenantNames = tenants.findAllById(rows.stream().map(Beneficiary::getTenantId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName));
        Map<Long, String> institutionNames = institutions.findAllById(rows.stream().map(Beneficiary::getInstitutionId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Bank::getId, Bank::getName));
        Map<Long, BeneficiaryType> typeRows = types.findAllById(rows.stream().map(Beneficiary::getTypeId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(BeneficiaryType::getId, Function.identity()));
        return new Names(tenantNames, institutionNames, typeRows);
    }

    private String nameOf(Owner owner) {
        if (owner.institutionId() != null) {
            return institutions.findById(owner.institutionId()).map(Bank::getName).orElse("the bank");
        }
        return tenants.findById(owner.tenantId()).map(Tenant::getName).orElse("the organisation");
    }

    private BeneficiaryResponse toResponse(Beneficiary b, Names names, UserPrincipal caller) {
        BeneficiaryType type = names.types().get(b.getTypeId());
        Owner owner = new Owner(b.getTenantId(), b.getInstitutionId());
        String ownerName = owner.institutionId() != null ? names.institutions().get(owner.institutionId())
                : names.tenants().get(owner.tenantId());
        return new BeneficiaryResponse(
                HashIdUtil.encodeId(b.getId()), b.getReference(),
                owner.kind(), ownerName,
                HashIdUtil.encodeId(b.getTenantId()), HashIdUtil.encodeId(b.getInstitutionId()),
                HashIdUtil.encodeId(b.getTypeId()), type == null ? null : type.getCode(), type == null ? null : type.getName(),
                b.getName(), b.getKraPin(), b.getContactName(), b.getContactPhone(), b.getContactEmail(),
                b.getBankCode(), b.getAccountNo(),
                b.getVerification(), b.getConfirmedName(), b.getConfirmedAt(), b.getVerificationNote(),
                b.getNotes(), b.isPayable(),
                b.getStatus(), b.getStatusFlag(),
                caller.isPlatformStaff() || b.belongsTo(caller.getTenantId(), caller.getInstitutionId()),
                b.getCreatedAt(), b.getCreatedBy(), b.getUpdatedAt(), b.getUpdatedBy());
    }
}
