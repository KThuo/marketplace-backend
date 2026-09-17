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
        /*
         * The same allow-list the catalogue screen applies, for the reason it applies it: this deployment
         * banks with one of the four providers Pesi fronts, and a form offering the other three is a form
         * somebody attaches an account to the wrong bank from. A channel with no Pesi provider — cash,
         * cheque — is never filtered out, and an empty setting restricts nothing.
         */
        List<String> offered = PaymentTypeService.offeredProviderNames(configs);
        return types.findAvailable().stream()
                .filter(t -> offered.isEmpty()
                        || t.getPesiProviderType() == null
                        || offered.contains(t.getProviderName()))
                .filter(t -> t.channelCategory().isReceivable())
                .filter(t -> !(t.isManual() && alreadyHeld(owner, t.getId(), null)))
                .map(t -> new AssignableChannel(HashIdUtil.encodeId(t.getId()), t.getName(),
                        t.getDescription(), t.getProviderName(), t.getCategory(), t.getMethod(),
                        t.needsAccount(), t.isRequiresShortCode()))
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
        Development development = developments.findById(booking.getDevelopmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHash));
        if (!visibility.mayRead(development, caller)) {
            throw new ResourceNotFoundException("Booking", bookingHash);
        }
        return offeredFor(development);
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
                .filter(a -> catalogue.containsKey(a.getPaymentTypeId()) && a.channelCategory().isReceivable())
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
     * The methods this caller's organisation can actually take money by.
     *
     * <p>The receive form used to be handed {@code PaymentMethods.ALL} — six of them, the same six for
     * everybody — so an organisation with no card channel was offered Card, and a payment could be recorded
     * through a route the platform cannot collect on. What is offered has to be what is configured.
     *
     * <p>Cash and cheque are the exception and are always offered: money over a counter needs no gateway,
     * no account and nothing switched on, and an organisation that could not record it would simply stop
     * writing it down. Everything else has to be earned by an account that exists and is live.
     *
     * <p>Platform staff see every configured method on the platform rather than none: they hold no
     * organisation, and an empty list would make the form unusable for the people who operate it.
     */
    @Transactional(readOnly = true)
    public List<PaymentDtos.MethodOption> methodsOnOffer() {
        UserPrincipal caller = AuthContext.require();
        Map<Long, PaymentType> catalogue = types.findAllLive().stream()
                .collect(Collectors.toMap(PaymentType::getId, Function.identity()));

        java.util.LinkedHashSet<String> offered = new java.util.LinkedHashSet<>();
        // Over a counter: no channel to configure, so never withheld.
        offered.add(AppConstant.PAY_CASH);
        offered.add(AppConstant.PAY_CHEQUE);

        liveFor(ownerFor(caller, null, null)).stream()
                .map(a -> catalogue.get(a.getPaymentTypeId()))
                .filter(java.util.Objects::nonNull)
                .filter(t -> t.channelCategory().isReceivable())
                .sorted(Comparator.comparingInt(PaymentType::getSortOrder))
                .map(PaymentType::getMethod)
                .filter(m -> m != null && PaymentMethods.isKnown(m))
                .forEach(offered::add);

        return offered.stream()
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
        OtpChallengeService.IssuedCode issued = otps.issueToPhone(caller.getUserId(),
                OtpChallengeService.PURPOSE_PAYMENT_ACCOUNT, phoneOf(owner), nameOf(owner),
                "Somebody is changing how " + nameOf(owner) + " collects payments on Hodi.");
        return new OtpIssued(issued.challengeToken(), issued.sentToMasked(), issued.expiresAt(),
                issued.validForMinutes());
    }

    // ── writing ───────────────────────────────────────────────────────────────

    @Transactional
    public AccountResponse assign(SaveAccountRequest request) {
        UserPrincipal caller = AuthContext.require();
        Owner owner = ownerFor(caller, request.tenantId(), request.institutionId());
        PaymentType type = requireType(request.paymentTypeId());
        if (!type.isAvailable()) {
            throw new HodiException(type.getName() + " is not available.", HttpStatus.BAD_REQUEST);
        }
        if (!type.channelCategory().isReceivable()) {
            throw new HodiException(type.getName() + " sends money out; it is not a way to collect it.",
                    HttpStatus.BAD_REQUEST);
        }
        Long developmentId = requireDevelopment(request.developmentId(), owner, caller);
        String accountNo = requireAccountFields(request, type);
        String shortCode = requireShortCodeFree(request.shortCode(), null);
        requireAccountFree(accountNo, null);
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
        account.setCreatedBy(AuthContext.username());
        account.setUpdatedBy(AuthContext.username());

        PaymentAccount saved = accounts.save(account);
        /*
         * Audited in full: this row decides which account a buyer's money lands in, so changing it is the
         * highest-consequence configuration change in the product.
         */
        audit.record(AppConstant.ACTION_CREATE, "PaymentAccount", saved.getId(), null, snapshot(saved, type));
        log.info("{} set up {} for {}", AuthContext.username(), type.getName(), nameOf(owner));
        return toResponse(saved, names(List.of(saved)));
    }

    @Transactional
    public AccountResponse update(String hashId, SaveAccountRequest request) {
        UserPrincipal caller = AuthContext.require();
        PaymentAccount account = requireOwn(hashId);
        PaymentType type = types.findById(account.getPaymentTypeId())
                .orElseThrow(() -> new HodiException("That payment method no longer exists.",
                        HttpStatus.CONFLICT));

        String accountNo = requireAccountFields(request, type);
        String shortCode = requireShortCodeFree(request.shortCode(), account.getId());
        requireAccountFree(accountNo, account.getId());

        consumeCode(request, caller);

        String before = snapshot(account, type);
        account.setPayBillNo(blankToNull(request.payBillNo()));
        account.setAccountNo(accountNo);
        account.setAccountName(type.needsAccount() ? blankToNull(request.accountName()) : null);
        account.setShortCode(shortCode);
        // Live, and edited. The status flag says so, and the account stays offered.
        if (account.getStatus() != null && account.getStatus() == AppConstant.STATUS_ACTIVE) {
            account.setStatus(AppConstant.STATUS_EDITED);
            account.setStatusFlag(AppConstant.FLAG_EDITED);
        }
        account.setUpdatedBy(AuthContext.username());

        PaymentAccount saved = accounts.save(account);
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

    /** The account number is what an inbound credit is matched on, so it is unique across the platform. */
    private void requireAccountFree(String accountNo, Long excludingId) {
        if (accountNo == null) return;
        boolean taken = accounts.findByAccountNoNotArchived(accountNo).stream()
                .anyMatch(a -> excludingId == null || !excludingId.equals(a.getId()));
        if (taken) {
            throw new HodiException("Account " + accountNo + " is already registered on the platform.",
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

    private List<PaymentAccount> liveFor(Owner owner) {
        if (owner.institutionId() != null) return accounts.findLiveForInstitution(owner.institutionId());
        if (owner.tenantId() != null) return accounts.findLiveForTenant(owner.tenantId());
        return accounts.findLiveForPlatform();
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
