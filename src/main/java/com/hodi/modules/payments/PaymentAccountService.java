package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auth.OtpChallengeService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.banks.Bank;
import com.hodi.modules.banks.BankRepository;
import com.hodi.modules.payments.PaymentTypeDtos.*;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.modules.approvals.ChangeSet;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where an organisation's money lands.
 *
 * <p>Four fields — paybill, account number, account name, short code — and between them they decide which
 * bank account receives a buyer's deposit. That makes this the highest-value write in the system, and it is
 * guarded three ways: a permission for who may, ownership for whose, and a one-time code texted to the
 * organisation itself so it knows it happened.
 *
 * <p>Which fields are required is the <em>channel's</em> answer, not this record's. A phone prompt has no
 * account number to collect; an inbound credit cannot work without one. Reading it from the catalogue row
 * means the refusal can name the channel, and a channel added later needs no change here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAccountService {

    /** What {@code payments.collection.scope} reads when an organisation may collect its own money. */
    static final String SCOPE_ORGANISATION = "ORGANISATION";

    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final TenantRepository tenants;
    private final BankRepository institutions;
    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;
    private final UnitBookingRepository bookings;
    private final PaymentScope scope;
    private final OtpChallengeService otps;
    private final ConfigurationService configs;
    private final com.hodi.common.EncryptionUtil crypto;
    private final com.hodi.modules.users.UserRepository users;
    private final com.hodi.modules.properties.PropertyRepository listings;
    private final com.hodi.modules.approvals.ApprovalService approvals;
    private final AuditService audit;

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<AccountResponse> list(AccountListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<PaymentAccount> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("category", blankToNull(request.getCategory())),
                SearchSpecs.eq("paymentTypeId", HashIdUtil.decodeId(request.getPaymentTypeId())),
                SearchSpecs.eq("developmentId", HashIdUtil.decodeId(request.getDevelopmentId())),
                ownerKind(request.getOwnerKind()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                scope.accounts(caller));
        var page = accounts.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        Names names = names(page.getContent());
        return PagedResponse.from(page, a -> toResponse(a, names));
    }

    @Transactional(readOnly = true)
    public AccountResponse find(String hashId) {
        PaymentAccount account = requireOwn(hashId);
        return toResponse(account, names(List.of(account)));
    }

    /**
     * The channels this owner could still be given.
     *
     * <p>Cash and cheque drop out once assigned; a gateway channel does not, because an organisation really
     * does hold several accounts on one bank. Transfers are absent: they are money going out, and nothing
     * here pays out.
     */
    @Transactional(readOnly = true)
    public List<AssignableChannel> assignable(String tenantHash, String institutionHash) {
        Owner owner = ownerFor(AuthContext.require(), tenantHash, institutionHash);
        // Nothing to attach while the platform collects everything: a form that offers channels and then
        // refuses the save has told somebody the answer only after they did the work.
        if (!owner.platform() && !organisationsMayCollect()) return List.of();
        /*
         * The same allow-list the catalogue screen applies, for the reason it applies it: this deployment
         * banks with one of the four providers the reference gateway fronts, and a form offering the other three is a form
         * somebody attaches an account to the wrong bank from. A channel with no provider — cash,
         * cheque — is never filtered out, and an empty setting restricts nothing.
         */
        List<String> offered = PaymentTypeService.offeredProviderNames(configs);
        return types.findAvailable().stream()
                .filter(t -> offered.isEmpty()
                        || t.getProviderType() == null
                        || offered.contains(t.getProviderName()))
                /*
                 * Anything that is not an enquiry. Not "anything receivable" — that was the same
                 * mistake in a different place: money going out needs an account to go FROM, so a
                 * transfer method is configured here even though no payer is ever offered it.
                 */
                .filter(PaymentType::configurable)
                .filter(t -> !(t.isManual() && alreadyHeld(owner, t.getId(), null)))
                .map(t -> new AssignableChannel(HashIdUtil.encodeId(t.getId()), t.getName(),
                        t.getDescription(), t.getProviderName(), t.getCategory(), t.getMethod(),
                        t.needsAccount(), t.isRequiresShortCode(),
                        declaredFields(t, null),
                        ChannelConfig.accountsLabel(t.getAccountConfigFields())))
                .toList();
    }

    /**
     * The accounts money for a booking may be recorded through.
     *
     * <p>Both the development's own and the owner's organisation-wide ones, because an organisation-wide
     * paybill is the normal case and a per-development one the exception. Ordered by the catalogue's own
     * sort, so the method an organisation wants used most appears first.
     */
    @Transactional(readOnly = true)
    public List<OfferedAccount> offered(String bookingHash) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(bookingHash))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHash));

        /*
         * A booking need not belong to a development.
         *
         * <p>A standalone listing — a single house, a plot — is booked and paid for exactly like a unit
         * in a scheme, and its booking carries no development id. This looked the id up unconditionally
         * and handed null to findById, which answers "The given id must not be null" and takes the
         * receive form down with it: the seller could take a booking and then not record the money.
         *
         * <p>Without a development there is nothing to narrow by, so the owner's organisation-wide
         * accounts are the answer — which is what an account with no development means anyway.
         */
        List<OfferedAccount> all;
        if (booking.getDevelopmentId() == null) {
            all = offeredForOwner(new Owner(booking.getTenantId(), booking.getInstitutionId()));
        } else {
            Development development = developments.findById(booking.getDevelopmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHash));
            if (!visibility.mayRead(development, caller)) {
                throw new ResourceNotFoundException("Booking", bookingHash);
            }
            all = offeredFor(development);
        }

        // Narrowed to what this person can actually start against this listing. Done here rather than in
        // the browser: a form that hides a method the server would accept is a suggestion, not a rule.
        boolean byHand = mayRecordByHand(caller, booking.getPropertyId());
        boolean slip = maySeeSlip(caller);
        return all.stream()
                /*
                 * Two questions, not a list of channels.
                 *
                 * <p>Can somebody start it here, and does it credit. A transfer sends money out, and a form
                 * for taking money must never carry a way of sending it. A phone prompt is started here and
                 * credits, so anybody who reaches this form may use it. Cash and a cheque credit, and are
                 * narrowed by who is asking — see {@link #mayRecordByHand}.
                 *
                 * <p>An inbound account or a biller is begun by the payer at their own bank; what can be
                 * started <em>here</em> is finding the money afterwards by the reference off the slip. So an
                 * inbound channel is offered as slip validation, to platform staff always and to a buyer
                 * when the institution has switched that on. The screen renders it as VALIDATE.
                 */
                .filter(a -> {
                    CoopChannel.Category category = CoopChannel.Category.of(a.category());
                    if (category.isManual()) return byHand;
                    if (category == CoopChannel.Category.VALIDATE) return slip;
                    return category == CoopChannel.Category.STK_PUSH;
                })
                .toList();
    }

    /** The owner's accounts that are not tied to one development. */
    private List<OfferedAccount> offeredForOwner(Owner owner) {
        Map<Long, PaymentType> catalogue = types.findAllLive().stream()
                .collect(Collectors.toMap(PaymentType::getId, Function.identity()));
        return liveFor(owner).stream()
                .filter(a -> a.getDevelopmentId() == null)
                .filter(a -> catalogue.containsKey(a.getPaymentTypeId())
                        && catalogue.get(a.getPaymentTypeId()).selectable()
                        && a.channelCategory().isReceivable())
                .sorted(Comparator.comparingInt(a -> catalogue.get(a.getPaymentTypeId()).getSortOrder()))
                .map(a -> {
                    PaymentType type = catalogue.get(a.getPaymentTypeId());
                    return new OfferedAccount(HashIdUtil.encodeId(a.getId()), type.getName(),
                            type.getProviderName(), a.getCategory(), a.channelCategory().renderAs(),
                            type.getMethod(), a.getPayBillNo(), a.getAccountNo(),
                            PaymentTypeDtos.developmentLabel(a, null));
                })
                .toList();
    }

    /**
     * Whether this caller is offered slip validation: platform staff always, a buyer by the institution's
     * setting, anybody else never. The same rule {@code SlipValidationService.maySlip} enforces on the call.
     */
    private boolean maySeeSlip(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return true;
        return caller.isBuyer() && configs.getBoolean(ConfigKey.PAYMENTS_BUYER_SLIP_VALIDATION);
    }

    /**
     * Whether this caller may assert that cash arrived, against this listing.
     *
     * <p>Platform staff only, and never against a listing they own. Cash has no gateway behind it — it is
     * one person's word that money appeared — so the control is that the person recording it is not the
     * person it benefits. A seller marking cash received against their own unit is unanswerable by this
     * platform, and requiring a second party is the whole of the protection, exactly as it is on a
     * payment account.
     */
    private boolean mayRecordByHand(UserPrincipal caller, Long propertyId) {
        if (!caller.isPlatformStaff()) return false;
        if (propertyId == null) return true;
        com.hodi.modules.properties.Property listing = listings.findById(propertyId).orElse(null);
        if (listing == null) return true;
        boolean ownTenant = listing.getTenantId() != null
                && listing.getTenantId().equals(caller.getTenantId());
        boolean ownInstitution = listing.getInstitutionId() != null
                && listing.getInstitutionId().equals(caller.getInstitutionId());
        return !(ownTenant || ownInstitution);
    }

    /** The same list, for a caller already holding the development. */
    @Transactional(readOnly = true)
    public List<OfferedAccount> offeredFor(Development development) {
        Owner owner = new Owner(development.getTenantId(), development.getInstitutionId());
        Map<Long, PaymentType> catalogue = types.findAllLive().stream()
                .collect(Collectors.toMap(PaymentType::getId, Function.identity()));
        Map<Long, String> devNames = new HashMap<>();
        devNames.put(development.getId(), development.getName());

        return liveFor(owner).stream()
                .filter(a -> a.reaches(development.getId()))
                // Switching a channel off stops new set-ups, not money already banking — so an account on an
                // off channel is still offered, exactly as the catalogue's status message promises.
                .filter(a -> catalogue.containsKey(a.getPaymentTypeId())
                        && catalogue.get(a.getPaymentTypeId()).selectable()
                        && a.channelCategory().isReceivable())
                .sorted(Comparator.comparingInt(a -> catalogue.get(a.getPaymentTypeId()).getSortOrder()))
                .map(a -> {
                    PaymentType type = catalogue.get(a.getPaymentTypeId());
                    return new OfferedAccount(HashIdUtil.encodeId(a.getId()), type.getName(),
                            type.getProviderName(), a.getCategory(), a.channelCategory().renderAs(),
                            type.getMethod(), a.getPayBillNo(), a.getAccountNo(),
                            PaymentTypeDtos.developmentLabel(a, devNames.get(a.getDevelopmentId())));
                })
                .toList();
    }

    /**
     * The ways money can be written down by hand: cash and a cheque, and nothing else.
     *
     * <p>This used to add the method of every configured channel, so a form could offer "mobile money" as
     * something to key. It cannot be keyed: a phone payment or a transfer arrives as a notification from the
     * bank and is placed from that notification, and a hand-keyed one is a payment with nothing behind it.
     * The channels an organisation has configured still decide what a payer is <em>offered</em> — that is
     * {@link #offered}, per booking — but they do not become things a clerk may type in.
     */
    public List<PaymentDtos.MethodOption> methodsOnOffer() {
        return PaymentMethods.MANUAL.stream()
                .map(m -> new PaymentDtos.MethodOption(m, PaymentMethods.label(m)))
                .toList();
    }

    /**
     * Whether this account number is already registered.
     *
     * <p>Asked by the form before the code is spent, because discovering a clash after the code costs a second
     * SMS and the organisation's patience. Platform-wide, because the number is what an inbound credit is
     * matched on and two rows claiming one till would make the money ambiguous.
     */
    @Transactional(readOnly = true)
    public AccountCheck checkAccount(String accountNo, String excludingHash) {
        String value = accountNo == null ? "" : accountNo.trim();
        if (value.isEmpty()) return new AccountCheck(false, null);
        Long excluding = HashIdUtil.decodeId(excludingHash);
        boolean taken = accounts.findByAccountNoNotArchived(value).stream()
                .anyMatch(a -> excluding == null || !excluding.equals(a.getId()));
        return new AccountCheck(taken, taken
                ? "Account " + value + " is already registered on the platform." : null);
    }

    /** The developments an owner's account may be narrowed to. */
    @Transactional(readOnly = true)
    public List<DevelopmentOption> developmentOptions(String tenantHash, String institutionHash) {
        Owner owner = ownerFor(AuthContext.require(), tenantHash, institutionHash);
        if (owner.platform()) return List.of();
        Specification<Development> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("tenantId", owner.tenantId()),
                SearchSpecs.eq("institutionId", owner.institutionId()));
        return developments.findAll(spec, Sort.by("name")).stream()
                .map(d -> new DevelopmentOption(HashIdUtil.encodeId(d.getId()), d.getName()))
                .toList();
    }

    // ── the code ──────────────────────────────────────────────────────────────

    /**
     * Texts the organisation a code.
     *
     * <p>To the <em>organisation's</em> number, not the caller's. Somebody who has taken over a staff login
     * holds that person's phone; they do not hold the organisation's contact line, and that gap is the whole
     * control. Refused with a plain message when there is no number on file, because an unexplained failure
     * here reads as the system being broken.
     */
    @Transactional
    public OtpIssued requestCode(OtpRequest request) {
        UserPrincipal caller = AuthContext.require();
        Owner owner = ownerFor(caller, request.tenantId(), request.institutionId());
        requireMayCollect(owner);
        /*
         * To the person doing it, not to the organisation.
         *
         * <p>It used to go to the organisation's own contact number, on the reasoning that a stolen staff
         * login does not come with the office phone. Two things undid that. Maker/Checker now supplies the
         * organisational consent — a second named person has to approve the account before it collects a
         * shilling — so the code is no longer carrying that weight. And once the platform collects
         * everything, the "organisation" is the platform, whose number is a support line nobody watches;
         * an unset one blocked set-up altogether rather than protecting it.
         *
         * <p>So the code now proves presence: that whoever is typing still holds the handset on their own
         * user record. The approval is what proves the change was wanted.
         */
        String phone = phoneOfCaller(caller);
        OtpChallengeService.IssuedCode issued = otps.issueToPhone(caller.getUserId(),
                OtpChallengeService.PURPOSE_PAYMENT_ACCOUNT, phone, caller.getFullName(),
                "You are changing how " + nameOf(owner) + " collects payments on Hodi.");
        return new OtpIssued(issued.challengeToken(), issued.sentToMasked(), issued.expiresAt(),
                issued.validForMinutes());
    }

    /**
     * What the set-up form needs before it asks the first question.
     *
     * <p>Read through the service rather than from the settings API because the person setting up an account
     * holds {@code PAYMENT_TYPES_MANAGE}, not necessarily permission to read platform settings, and a form
     * that cannot answer its own first question would have to guess.
     */
    @Transactional(readOnly = true)
    public PaymentTypeDtos.AccountSetupContext setupContext() {
        return new PaymentTypeDtos.AccountSetupContext(
                organisationsMayCollect(), AuthContext.require().isPlatformStaff());
    }

    // ── writing ───────────────────────────────────────────────────────────────

    @Transactional
    public AccountResponse assign(SaveAccountRequest request) {
        UserPrincipal caller = AuthContext.require();
        Owner owner = ownerFor(caller, request.tenantId(), request.institutionId());
        requireMayCollect(owner);
        PaymentType type = requireType(request.paymentTypeId());
        if (!type.isAvailable()) {
            throw new HodiException(type.getName() + " is not available.", HttpStatus.BAD_REQUEST);
        }
        if (!type.configurable()) {
            throw new HodiException(type.getName() + " is an enquiry — it asks about payments rather "
                    + "than taking or sending them, so it has no account.", HttpStatus.BAD_REQUEST);
        }
        // A sending method is configured here and chosen elsewhere, so the receivable rule — which is
        // about being offered to a payer — does not apply to it.
        if (type.selectable() && !type.channelCategory().isReceivable()) {
            throw new HodiException(type.getName() + " sends money out; it is not a way to collect it.",
                    HttpStatus.BAD_REQUEST);
        }
        Long developmentId = requireDevelopment(request.developmentId(), owner, caller);

        // A channel that describes its own account is read through the descriptor; one that does not —
        // every channel predating it — keeps the four fixed columns, so live rows need no migration.
        Configured configured = describesItsAccount(type) ? configure(type, null, request) : null;
        String accountNo = configured != null ? configured.code() : requireAccountFields(request, type);
        String shortCode = requireShortCodeFree(request.shortCode(), null);
        requireAccountFree(accountNo, null, configured != null ? type.getId() : null);
        requireManualNotHeld(owner, type, developmentId);

        /*
         * The code is spent last, and every check above is deliberately answerable without one. A refusal
         * must never cost a text message.
         */
        consumeCode(request, caller);

        PaymentAccount account = new PaymentAccount();
        account.stampChannel(type);
        account.setTenantId(owner.tenantId());
        account.setInstitutionId(owner.institutionId());
        account.setDevelopmentId(developmentId);
        account.setPayBillNo(blankToNull(request.payBillNo()));
        account.setAccountNo(accountNo);
        account.setAccountName(type.needsAccount() ? blankToNull(request.accountName()) : null);
        account.setShortCode(shortCode);
        if (configured != null) account.setConfig(configured.values());
        account.setCreatedBy(AuthContext.username());
        account.setUpdatedBy(AuthContext.username());

        // Written, and deliberately not live. STATUS_NEW is excluded by every "live" query on the
        // repository, so nothing offers it and no inbound notification matches it until it is approved.
        account.setStatus(AppConstant.STATUS_NEW);
        account.setStatusFlag(AppConstant.FLAG_NEW);

        PaymentAccount saved = accounts.save(account);
        submitForApproval(saved, type, owner, AppConstant.APPROVAL_ACTION_CREATE, null);
        /*
         * Audited in full: this row decides which account a buyer's money lands in, so changing it is the
         * highest-consequence configuration change in the product.
         */
        audit.record(AppConstant.ACTION_CREATE, "PaymentAccount", saved.getId(), null, snapshot(saved, type));
        log.info("{} proposed {} for {}", AuthContext.username(), type.getName(), nameOf(owner));
        return toResponse(saved, names(List.of(saved)));
    }

    @Transactional
    public AccountResponse update(String hashId, SaveAccountRequest request) {
        UserPrincipal caller = AuthContext.require();
        PaymentAccount account = requireOwn(hashId);
        PaymentType type = types.findById(account.getPaymentTypeId())
                .orElseThrow(() -> new HodiException("That payment method no longer exists.",
                        HttpStatus.CONFLICT));

        Configured configured = describesItsAccount(type) ? configure(type, account, request) : null;
        String accountNo = configured != null ? configured.code() : requireAccountFields(request, type);
        String shortCode = requireShortCodeFree(request.shortCode(), account.getId());
        requireAccountFree(accountNo, account.getId(), configured != null ? type.getId() : null);

        consumeCode(request, caller);

        String before = snapshot(account, type);
        // Captured before anything is mutated: asking the entity afterwards would compare the new values
        // with themselves and show a checker an empty difference.
        ChangeSet.Snapshot beforeSnap = describe(account, type);
        account.setPayBillNo(blankToNull(request.payBillNo()));
        account.setAccountNo(accountNo);
        account.setAccountName(type.needsAccount() ? blankToNull(request.accountName()) : null);
        account.setShortCode(shortCode);
        if (configured != null) account.setConfig(configured.values());
        /*
         * An edited account stops collecting until somebody approves it.
         *
         * <p>Everywhere else in this codebase an edit stamps STATUS_EDITED and stays live, because the row
         * still describes the same thing. Not here: the field most likely to be edited is the account
         * number, and an unapproved destination that goes on collecting is the exact failure Maker/Checker
         * is for. A digit changed by somebody with a stolen session would otherwise route real money for as
         * long as it took anybody to look.
         *
         * <p>The cost is real and worth stating: correcting a callback URL takes the account out of service
         * until a second person acts. That is the trade, and the safe side of it is this one.
         */
        Integer wasStatus = account.getStatus();
        if (wasStatus == null || wasStatus != AppConstant.STATUS_INACTIVE) {
            account.setStatus(AppConstant.STATUS_NEW);
            account.setStatusFlag(AppConstant.FLAG_NEW);
        }
        account.setUpdatedBy(AuthContext.username());

        PaymentAccount saved = accounts.save(account);
        submitForApproval(saved, type,
                new Owner(saved.getTenantId(), saved.getInstitutionId()),
                AppConstant.ACTION_UPDATE, beforeSnap);
        audit.record(AppConstant.ACTION_UPDATE, "PaymentAccount", saved.getId(), before, snapshot(saved, type));
        log.info("{} changed the {} account {}", AuthContext.username(), type.getName(), saved.getId());
        return toResponse(saved, names(List.of(saved)));
    }

    /**
     * Withdraw an account from use, or restore it.
     *
     * <p>No code: withdrawing an account cannot send money anywhere. It stops that method being offered and
     * stops inbound credits matching it — which is what somebody does the moment they suspect an account has
     * been tampered with, and it must not be gated behind an SMS.
     */
    @Transactional
    public String setStatus(String hashId, boolean active) {
        PaymentAccount account = requireOwn(hashId);
        // Bringing a withdrawn organisation account back is the same act as attaching one, so it answers
        // to the same setting. Withdrawing one is always allowed: stopping collection needs no permission.
        if (active) {
            requireMayCollect(new Owner(account.getTenantId(), account.getInstitutionId()));
        }
        PaymentType type = types.findById(account.getPaymentTypeId()).orElse(null);
        String name = type == null ? "That account" : type.getName();
        String before = snapshot(account, type);

        account.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        account.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        account.setUpdatedBy(AuthContext.username());
        accounts.save(account);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "PaymentAccount", account.getId(), before, snapshot(account, type));

        return active
                ? name + " is available again."
                : name + " is withdrawn. It is no longer offered, and payments quoting it will not be matched.";
    }

    // ── the rules ─────────────────────────────────────────────────────────────

    /** Whose account this is. Platform staff say; everybody else's organisation is their own. */
    private record Owner(Long tenantId, Long institutionId) {
        boolean platform() { return tenantId == null && institutionId == null; }
        String kind() {
            return institutionId != null ? "INSTITUTION" : tenantId != null ? "TENANT" : "PLATFORM";
        }
    }

    /**
     * Puts the account in front of a second pair of eyes.
     *
     * <p>{@code submitOrRestate} rather than {@code submit}: two edits before anybody decides are one
     * difference from the last approved state, not two from each other.
     */
    private void submitForApproval(PaymentAccount account, PaymentType type, Owner owner,
                                   String action, ChangeSet.Snapshot before) {
        approvals.submitOrRestate(
                AppConstant.APPROVAL_ENTITY_PAYMENT_ACCOUNT, account.getId(), action,
                owner.tenantId(), owner.institutionId(),
                type.getName() + " — " + nameOf(owner),
                AppConstant.APPROVAL_ACTION_CREATE.equals(action)
                        ? "A new account for " + type.getName() + ". It collects nothing until approved."
                        : "Changed details on the " + type.getName() + " account. It is out of use until "
                                + "approved.",
                before, describe(account, type));
    }

    /**
     * The account as a checker should read it — every field the form asks for, by its own label.
     *
     * <p><b>A secret is never in here.</b> The queue is readable by everybody who may decide, and a
     * pending change that printed a consumer secret in plain text would be a worse leak than the control
     * is worth. What a checker needs to see is <em>that</em> a credential changed, which the mask says
     * perfectly well.
     */
    private ChangeSet.Snapshot describe(PaymentAccount account, PaymentType type) {
        ChangeSet.Snapshot snapshot = ChangeSet.of()
                .put("method", "Payment method", type == null ? null : type.getName())
                .put("developmentId", "Collects for",
                        account.getDevelopmentId() == null ? "Every development" : "One development");

        List<ChannelConfig.Field> fields = type == null ? List.of() : declaredFields(type, account);
        if (fields.isEmpty()) {
            snapshot.put("accountNo", "Account number", account.getAccountNo())
                    .put("accountName", "Name on the account", account.getAccountName())
                    .put("payBillNo", "Paybill", account.getPayBillNo())
                    .put("shortCode", "Short code", account.getShortCode());
        } else {
            snapshot.put("accountNo", "Matched on", account.getAccountNo());
            // describe() has already masked every secret, so this cannot leak one by construction.
            for (ChannelConfig.Field field : fields) {
                snapshot.put(field.key(), field.label(), field.value());
            }
        }
        return snapshot;
    }

    /** Approved: it may start collecting. */
    @Transactional
    public void applyApproval(Long accountId, String approvedBy) {
        PaymentAccount account = accounts.findById(accountId).orElse(null);
        if (account == null) return;
        account.setStatus(AppConstant.STATUS_ACTIVE);
        account.setStatusFlag(AppConstant.FLAG_ACTIVE);
        account.setUpdatedBy(approvedBy);
        accounts.save(account);
        log.info("Payment account {} approved by {}", accountId, approvedBy);
    }

    /**
     * Refused: it stays exactly where it was, switched off.
     *
     * <p>Not deleted. A rejected account is evidence — somebody proposed a destination and somebody else
     * refused it — and on an edit the row still holds details a person needs to see to correct them.
     */
    @Transactional
    public void applyRefusal(Long accountId, String decision, String reason) {
        PaymentAccount account = accounts.findById(accountId).orElse(null);
        if (account == null) return;
        account.setStatus(AppConstant.STATUS_INACTIVE);
        account.setStatusFlag(AppConstant.FLAG_INACTIVE);
        accounts.save(account);
        log.info("Payment account {} {}: {}", accountId, decision, reason);
    }

    /** What {@code payments.collection.scope} currently says. */
    private boolean organisationsMayCollect() {
        return SCOPE_ORGANISATION.equalsIgnoreCase(
                configs.getString(ConfigKey.PAYMENT_COLLECTION_SCOPE));
    }

    /**
     * Refuses an organisation-owned account while the platform collects everything.
     *
     * <p>Applied at set-up and nowhere else, because set-up is the only moment the answer can be acted
     * on: once an account exists, money has been routed through it and a payment already taken is not
     * unwound by a setting. So this stops a new one being attached; it does not strand an old one.
     *
     * <p>It binds platform staff too. They are the people who would otherwise attach an account on an
     * organisation's behalf, and a control the operator can step around is a control that only documents
     * an intention.
     */
    private void requireMayCollect(Owner owner) {
        if (owner.platform() || organisationsMayCollect()) return;
        throw new HodiException(
                "Payments are collected to the platform's own account, so an organisation cannot be given "
                        + "one. Change \"Who collects payments\" in platform settings first.",
                HttpStatus.CONFLICT);
    }

    private Owner ownerFor(UserPrincipal caller, String tenantHash, String institutionHash) {
        Long tenantId = HashIdUtil.decodeId(tenantHash);
        Long institutionId = HashIdUtil.decodeId(institutionHash);
        if (caller.isPlatformStaff()) {
            if (tenantId != null && institutionId != null) {
                throw new HodiException("An account belongs to one organisation, not two.",
                        HttpStatus.BAD_REQUEST);
            }
            return new Owner(tenantId, institutionId);
        }
        // Derived, never chosen: a form field that accepts the organisation is one somebody can change.
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
        throw new HodiException("You do not belong to an organisation that collects payments.",
                HttpStatus.FORBIDDEN);
    }

    /** An account narrowed to a development names one the owner has, and the caller may manage. */
    private Long requireDevelopment(String developmentHash, Owner owner, UserPrincipal caller) {
        Long developmentId = HashIdUtil.decodeId(developmentHash);
        if (developmentId == null) return null;
        if (owner.platform()) {
            throw new HodiException("The platform's own account collects for the platform, not for a "
                    + "development.", HttpStatus.BAD_REQUEST);
        }
        Development development = developments.findById(developmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHash));
        boolean owned = Objects.equals(development.getTenantId(), owner.tenantId())
                && Objects.equals(development.getInstitutionId(), owner.institutionId());
        if (!owned) {
            throw new HodiException(development.getName() + " does not belong to that organisation.",
                    HttpStatus.BAD_REQUEST);
        }
        visibility.assertMayManage(development, caller);
        return development.getId();
    }

    /**
     * What this channel asks of one of its accounts, with the values it already holds.
     *
     * <p>Empty for a channel whose descriptor has not been written — every pre-Co-op channel — and that
     * emptiness is what keeps the old four-column path in use for them rather than a migration of live rows.
     */
    private List<ChannelConfig.Field> declaredFields(PaymentType type, PaymentAccount account) {
        return ChannelConfig.describe(type.getAccountConfigFields(),
                account == null ? null : account.getConfig(), crypto, ourUrls());
    }

    /**
     * The addresses this platform tells Co-op to call, built rather than typed.
     *
     * <p>All of them, from {@link com.hodi.infra.coop.CoopRoutes} — the same constants the controllers
     * are mapped on, so the address on the screen and the address served cannot drift.
     *
     * <p>Including the biller's two, which are not served yet. They are shown anyway because the address
     * is a decision rather than an implementation: the bank asks for it in writing during onboarding, long
     * before anything posts to it, and deciding it late is how two systems end up disagreeing about where
     * it was. What the form does say is which of them are live.
     */
    private Map<String, String> ourUrls() {
        String base = configs.getString(ConfigKey.PUBLIC_URL);
        if (base == null || base.isBlank()) return Map.of();
        String trimmed = base.trim();
        String root = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return Map.of(
                "notificationUrl", root + com.hodi.infra.coop.CoopRoutes.NOTIFICATIONS,
                "validationUrl", root + com.hodi.infra.coop.CoopRoutes.BILLER_VALIDATION,
                "adviceUrl", root + com.hodi.infra.coop.CoopRoutes.BILLER_ADVICE);
    }

    /**
     * Reads the submitted values against the descriptor: merged, secrets encrypted, undeclared keys dropped.
     *
     * @return the map to store, and the composed code to match inbound notifications on
     */
    private record Configured(Map<String, Object> values, String code) {}

    /** True once somebody has written this channel's account descriptor. */
    private boolean describesItsAccount(PaymentType type) {
        return !ChannelConfig.describe(type.getAccountConfigFields(), null, crypto).isEmpty();
    }

    private Configured configure(PaymentType type, PaymentAccount existing, SaveAccountRequest request) {
        Map<String, Object> descriptor = type.getAccountConfigFields();
        Map<String, Object> values = ChannelConfig.merge(descriptor,
                existing == null ? null : existing.getConfig(), request.config(), crypto);

        List<String> missing = ChannelConfig.missing(descriptor, values);
        if (!missing.isEmpty()) {
            // Named, because "some fields are missing" on a nine-field biller form is not an answer.
            throw new HodiException(type.getName() + " needs " + String.join(", ", missing) + ".",
                    HttpStatus.BAD_REQUEST);
        }

        String code = ChannelConfig.accountKey(descriptor, values);
        if (code == null && type.needsAccount()) {
            throw new HodiException("Enter " + String.join(" and ",
                    ChannelConfig.accountKeyFields(descriptor))
                    + " — it is what an incoming payment is matched on.", HttpStatus.BAD_REQUEST);
        }
        return new Configured(values, code);
    }

    /**
     * The account fields, required or refused by the channel.
     *
     * <p>Both halves or neither. A mandatory column here is how a live system ends up with a placeholder in
     * every phone-prompt row — a value somebody typed to get past a field that channel has no use for.
     */
    private String requireAccountFields(SaveAccountRequest request, PaymentType type) {
        String accountNo = blankToNull(request.accountNo());
        String accountName = blankToNull(request.accountName());
        if (!type.needsAccount()) {
            if (accountNo != null || accountName != null) {
                throw new HodiException(type.getName() + " needs no account — it is recorded by hand.",
                        HttpStatus.BAD_REQUEST);
            }
            return null;
        }
        if (accountNo == null) {
            throw new HodiException("Enter the account, till or paybill the money lands in.",
                    HttpStatus.BAD_REQUEST);
        }
        if (accountName == null) {
            throw new HodiException("Enter the name on the account, as the bank has it.",
                    HttpStatus.BAD_REQUEST);
        }
        return accountNo;
    }

    /**
     * The code an inbound credit is matched on, and who else may hold it.
     *
     * <p>Platform-wide for the old fixed columns, because a till number is issued once and a second row
     * claiming it makes every notification on it unattributable.
     *
     * <p><b>Per channel</b> once the code comes from a descriptor, matching the index the migration added.
     * One organisation legitimately holds several codes resolving at the bank to the same account — an
     * operator code on the prompt, an institution code and service name composed on the biller — and a
     * platform-wide rule would refuse the second one. What must not collide is two accounts on one channel
     * answering to one code.
     *
     * @param scopedToChannel the payment type to scope the check to, or null for the platform-wide rule
     */
    private void requireAccountFree(String accountNo, Long excludingId, Long scopedToChannel) {
        if (accountNo == null) return;
        boolean taken = accounts.findByAccountNoNotArchived(accountNo).stream()
                .filter(a -> scopedToChannel == null
                        || scopedToChannel.equals(a.getPaymentTypeId()))
                .anyMatch(a -> excludingId == null || !excludingId.equals(a.getId()));
        if (taken) {
            throw new HodiException("Account " + accountNo + " is already registered"
                    + (scopedToChannel == null ? " on the platform." : " on this method."),
                    HttpStatus.CONFLICT);
        }
    }

    /** Optional, but never shared: a fallback match landing on two accounts would attribute money to nobody. */
    private String requireShortCodeFree(String shortCode, Long excludingId) {
        String value = blankToNull(shortCode);
        if (value == null) return null;
        boolean taken = accounts.findByShortCodeNotArchived(value).stream()
                .anyMatch(a -> excludingId == null || !excludingId.equals(a.getId()));
        if (taken) {
            throw new HodiException("Short code " + value + " is already in use.", HttpStatus.CONFLICT);
        }
        return value;
    }

    /** Cash and cheque once per owner and development. A gateway channel may be configured repeatedly. */
    private void requireManualNotHeld(Owner owner, PaymentType type, Long developmentId) {
        if (!type.isManual()) return;
        if (alreadyHeld(owner, type.getId(), developmentId)) {
            throw new HodiException(type.getName() + " is already set up for " + nameOf(owner) + ".",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Whether the owner already holds this channel. With a development, on that development or
     * organisation-wide; without one, anywhere at all.
     */
    private boolean alreadyHeld(Owner owner, Long paymentTypeId, Long developmentId) {
        return accounts.findByPaymentTypeIdNotArchived(paymentTypeId).stream()
                .filter(a -> a.belongsTo(owner.tenantId(), owner.institutionId()))
                .anyMatch(a -> developmentId == null || a.reaches(developmentId));
    }

    private void consumeCode(SaveAccountRequest request, UserPrincipal caller) {
        if (blankToNull(request.otp()) == null || blankToNull(request.challengeToken()) == null) {
            throw new HodiException("Enter the code sent to the organisation.", HttpStatus.BAD_REQUEST);
        }
        otps.consumeCode(request.challengeToken(), request.otp(),
                OtpChallengeService.PURPOSE_PAYMENT_ACCOUNT, caller.getUserId());
    }

    /**
     * The caller's own number, from their user record.
     *
     * <p>Refused rather than skipped when there is none: an account set up with no second factor at all is
     * a weaker thing than a person being told to add a phone number first.
     */
    private String phoneOfCaller(UserPrincipal caller) {
        String phone = users.findById(caller.getUserId())
                .map(com.hodi.modules.users.User::getPhone).orElse(null);
        if (phone == null || phone.isBlank()) {
            throw new HodiException(
                    "Your account has no phone number, so the confirmation code cannot be sent. Add one to "
                            + "your profile first.", HttpStatus.BAD_REQUEST);
        }
        return phone.trim();
    }

    private String phoneOf(Owner owner) {
        String phone;
        if (owner.institutionId() != null) {
            phone = institutions.findById(owner.institutionId()).map(Bank::getContactPhone)
                    .orElse(null);
        } else if (owner.tenantId() != null) {
            phone = tenants.findById(owner.tenantId()).map(Tenant::getContactPhone).orElse(null);
        } else {
            phone = configs.getString(ConfigKey.PLATFORM_SUPPORT_PHONE);
        }
        if (phone == null || phone.isBlank()) {
            throw new HodiException(owner.platform()
                    ? "The platform has no support phone number configured, so the code cannot be sent. Set "
                            + "it under Settings first."
                    : nameOf(owner) + " has no contact phone number on record, so the code cannot be sent. "
                            + "Add one to the organisation first.", HttpStatus.BAD_REQUEST);
        }
        return phone.trim();
    }

    private String nameOf(Owner owner) {
        if (owner.institutionId() != null) {
            return institutions.findById(owner.institutionId()).map(Bank::getName)
                    .orElse("the institution");
        }
        if (owner.tenantId() != null) {
            return tenants.findById(owner.tenantId()).map(Tenant::getName).orElse("the organisation");
        }
        return "the platform";
    }

    /**
     * The accounts money for this owner may be collected into.
     *
     * <h3>Whose account collects is a platform decision, not a property of the booking</h3>
     *
     * <p>{@code payments.collection.scope} already says who collects: PLATFORM means every payment
     * lands in the platform's own account. This looked only at the booking's organisation, so on a
     * platform-collecting deployment — which is the default, and how this one is configured — a seller's
     * booking was offered nothing at all, while an approved Co-op account sat there unused. The payment
     * form then said no method was set up, which was true of the organisation and false of the platform.
     *
     * <p>Under ORGANISATION an organisation's own accounts come first and the platform's stand behind
     * them, which is the fallback that setting promises: turning it on cannot leave somebody unable to
     * take money.
     */
    private List<PaymentAccount> liveFor(Owner owner) {
        List<PaymentAccount> platform = accounts.findLiveForPlatform();
        if (owner.platform() || !organisationsMayCollect()) return platform;

        List<PaymentAccount> own = owner.institutionId() != null
                ? accounts.findLiveForInstitution(owner.institutionId())
                : accounts.findLiveForTenant(owner.tenantId());
        if (own.isEmpty()) return platform;

        List<PaymentAccount> both = new java.util.ArrayList<>(own);
        both.addAll(platform);
        return both;
    }

    /** TENANT, INSTITUTION or PLATFORM as a predicate, or null when the filter was not supplied. */
    private static Specification<PaymentAccount> ownerKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        return switch (kind.trim().toUpperCase()) {
            case "TENANT" -> (root, q, cb) -> cb.isNotNull(root.get("tenantId"));
            case "INSTITUTION" -> (root, q, cb) -> cb.isNotNull(root.get("institutionId"));
            case "PLATFORM" -> (root, q, cb) -> cb.and(cb.isNull(root.get("tenantId")),
                    cb.isNull(root.get("institutionId")));
            default -> null;
        };
    }

    /** One account the caller may read and change. Somebody else's is not found rather than refused. */
    private PaymentAccount requireOwn(String hashId) {
        PaymentAccount account = accounts.findById(HashIdUtil.decodeId(hashId))
                .filter(a -> a.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Payment account", hashId));
        if (!scope.ownsAccount(account, AuthContext.require())) {
            throw new ResourceNotFoundException("Payment account", hashId);
        }
        return account;
    }

    private PaymentType requireType(String hashId) {
        return types.findById(HashIdUtil.decodeId(hashId))
                .filter(t -> t.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new HodiException("Choose a payment method.", HttpStatus.BAD_REQUEST));
    }

    // ── rows ──────────────────────────────────────────────────────────────────

    /** The lookups a page needs, fetched once rather than per row. */
    private record Names(Map<Long, String> tenants, Map<Long, String> institutions,
                         Map<Long, String> developments, Map<Long, PaymentType> types) {}

    private Names names(List<PaymentAccount> rows) {
        Map<Long, String> tenantNames = tenants.findAllById(ids(rows, PaymentAccount::getTenantId)).stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName));
        Map<Long, String> institutionNames = institutions
                .findAllById(ids(rows, PaymentAccount::getInstitutionId)).stream()
                .collect(Collectors.toMap(Bank::getId, Bank::getName));
        Map<Long, String> developmentNames = developments
                .findAllById(ids(rows, PaymentAccount::getDevelopmentId)).stream()
                .collect(Collectors.toMap(Development::getId, Development::getName));
        Map<Long, PaymentType> typeRows = types.findAllById(ids(rows, PaymentAccount::getPaymentTypeId))
                .stream().collect(Collectors.toMap(PaymentType::getId, Function.identity()));
        return new Names(tenantNames, institutionNames, developmentNames, typeRows);
    }

    private static Set<Long> ids(List<PaymentAccount> rows, Function<PaymentAccount, Long> field) {
        return rows.stream().map(field).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private AccountResponse toResponse(PaymentAccount a, Names names) {
        PaymentType type = names.types().get(a.getPaymentTypeId());
        Owner owner = new Owner(a.getTenantId(), a.getInstitutionId());
        String ownerName = owner.institutionId() != null ? names.institutions().get(owner.institutionId())
                : owner.tenantId() != null ? names.tenants().get(owner.tenantId())
                : "Platform";
        return new AccountResponse(
                HashIdUtil.encodeId(a.getId()),
                HashIdUtil.encodeId(a.getPaymentTypeId()),
                type == null ? "Unknown method" : type.getName(),
                type == null ? null : type.getProviderName(),
                a.getCategory(), a.channelCategory().renderAs(),
                type == null ? null : type.getMethod(),
                owner.kind(), ownerName,
                HashIdUtil.encodeId(a.getTenantId()), HashIdUtil.encodeId(a.getInstitutionId()),
                HashIdUtil.encodeId(a.getDevelopmentId()),
                PaymentTypeDtos.developmentLabel(a, names.developments().get(a.getDevelopmentId())),
                a.getPayBillNo(), a.getAccountNo(), a.getAccountName(), a.getShortCode(),
                type == null ? List.of() : declaredFields(type, a),
                type == null ? "Account" : ChannelConfig.accountsLabel(type.getAccountConfigFields()),
                a.getStatus(), a.getStatusFlag(), a.getCreatedAt(), a.getCreatedBy(),
                a.getUpdatedAt(), a.getUpdatedBy());
    }

    private static String snapshot(PaymentAccount a, PaymentType type) {
        return (type == null ? "?" : type.getName()) + " owner=" + new Owner(a.getTenantId(), a.getInstitutionId()).kind()
                + " development=" + (a.getDevelopmentId() == null ? "all" : a.getDevelopmentId())
                + " account=" + a.getAccountNo() + " paybill=" + a.getPayBillNo()
                + " shortCode=" + a.getShortCode() + " status=" + a.getStatus();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
