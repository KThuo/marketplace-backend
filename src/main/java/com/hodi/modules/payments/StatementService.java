package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.BookingAccess;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.payments.StatementDtos.AttachRequest;
import com.hodi.modules.payments.StatementDtos.SetAsideRequest;
import com.hodi.modules.payments.StatementDtos.StatementDetail;
import com.hodi.modules.payments.StatementDtos.StatementListRequest;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.Waiting;
import com.hodi.modules.properties.Property;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The bank's side of the ledger, and a person's decisions about the rows the matcher could not place.
 *
 * <h2>Money in the bank that the platform cannot explain</h2>
 *
 * <p>Every notification is stored, matched or not, with a sentence saying why not. Until this class those
 * rows had no reader: the system was better at recognising it could not place a payment than at letting
 * anybody do something about it. This is the reader, and the three things a person may do — apply the
 * credit to a booking, set it aside as not a booking's money, or bring a set-aside one back.
 *
 * <h2>The amount is the bank's</h2>
 *
 * <p>{@link #attach} takes a booking and nothing else. A request that carried an amount would invite
 * somebody to credit a figure the bank did not confirm, which is the hole the statement rule closed.
 * The credit goes through the same writer an automatic match uses, so a payment placed by hand is
 * indistinguishable from one that placed itself.
 *
 * <h2>Nothing is deleted</h2>
 *
 * <p>Set aside is a state, not a delete. The bank still says the money arrived, and the reference must
 * keep blocking a second arrival of the same money while a person is deciding what the first one was.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementService {

    private final CoopStatementRepository statements;
    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final PaymentRepository payments;
    private final UnitBookingRepository bookings;
    private final DevelopmentRepository developments;
    private final BookingAccess access;
    private final PaymentScope scope;
    private final PaymentService paymentService;
    private final AuditService audit;

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<StatementResponse> list(StatementListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Collection<Long> narrowedToAccounts = accountsFor(request);
        Specification<CoopStatement> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", upperOrNull(request.getState())),
                narrowedToAccounts == null ? null : in("paymentAccountId", narrowedToAccounts),
                SearchSpecs.betweenDays("paidAt", request.getFrom(), request.getTo()),
                scope.statements(caller));
        Page<CoopStatement> page = statements.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.DESC, "paidAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        Lookups lookups = lookups(page.getContent());
        return PagedResponse.from(page, s -> toResponse(s, lookups));
    }

    /** How much is waiting to be placed, for the people whose money it is. */
    @Transactional(readOnly = true)
    public Waiting waiting() {
        UserPrincipal caller = AuthContext.require();
        List<Object[]> rows;
        if (caller.isPlatformStaff()) {
            rows = statements.waiting();
        } else if (caller.getInstitutionId() != null) {
            rows = statements.waitingForInstitution(caller.getInstitutionId());
        } else if (caller.getTenantId() != null) {
            rows = statements.waitingForTenant(caller.getTenantId());
        } else {
            return new Waiting(0, BigDecimal.ZERO, null);
        }
        if (rows.isEmpty() || rows.get(0) == null) return new Waiting(0, BigDecimal.ZERO, null);
        Object[] row = rows.get(0);
        long count = row[0] == null ? 0 : ((Number) row[0]).longValue();
        BigDecimal total = row[1] == null ? BigDecimal.ZERO : new BigDecimal(row[1].toString());
        OffsetDateTime oldest = row[2] instanceof OffsetDateTime at ? at : null;
        return new Waiting(count, total, oldest);
    }

    @Transactional(readOnly = true)
    public StatementDetail find(String hashId) {
        UserPrincipal caller = AuthContext.require();
        CoopStatement statement = readable(hashId, caller);
        StatementResponse response = toResponse(statement, lookups(List.of(statement)));
        // The payload is the bank's message verbatim — account numbers, balances, a payer's name — and it
        // is the platform's to read, not a seller's.
        return new StatementDetail(response, caller.isPlatformStaff() ? statement.getRawPayload() : null);
    }

    // ── deciding ─────────────────────────────────────────────────────────────

    /**
     * Applies an unplaced credit to a booking.
     *
     * <p>Locked while it decides, because a notification or a sweep can be crediting the same money in the
     * same second — the operator has just validated the slip, and the bank's own callback lands. Whoever
     * commits second must see the first one's decision and stop, not credit again.
     */
    @Transactional
    public StatementResponse attach(String hashId, AttachRequest request) {
        UserPrincipal caller = AuthContext.require();
        CoopStatement statement = statements.lockById(HashIdUtil.decodeId(hashId))
                .filter(s -> scope.readsStatement(s, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Statement", hashId));
        String before = snapshot(statement);

        if (statement.isMapped()) {
            throw new HodiException("This money has already been applied to booking "
                    + bookingReference(statement.getMappedBookingId()) + " as receipt "
                    + paymentReference(statement.getMappedPaymentId()) + ".", HttpStatus.CONFLICT);
        }
        if (statement.isIgnored()) {
            throw new HodiException("This credit was set aside" + reasonSuffix(statement)
                    + " Restore it before applying it to a booking.", HttpStatus.CONFLICT);
        }

        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(request.bookingId()))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", request.bookingId()));
        Property home = access.propertyOf(booking);
        if (!access.mayRead(home, caller)) {
            throw new ResourceNotFoundException("Booking", request.bookingId());
        }
        assertOpen(booking);

        Development development = access.developmentOf(home);
        PaymentAccount account = statement.getPaymentAccountId() == null ? null
                : accounts.findById(statement.getPaymentAccountId()).orElse(null);
        if (account != null) {
            /*
             * The money landed in a particular account, and that account collects for particular listings.
             * Applying a seller's credit to another seller's booking is not reconciliation, it is moving
             * money between organisations, and a person with a form should not be able to do that by
             * choosing the wrong row. Money in an account nobody registered has no owner yet, and placing
             * it is exactly the decision this screen exists for.
             */
            Long ownerTenant = development == null ? home.getTenantId() : development.getTenantId();
            Long ownerInstitution = development == null ? home.getInstitutionId() : development.getInstitutionId();
            if (!account.belongsTo(ownerTenant, ownerInstitution)
                    || !account.reaches(development == null ? null : development.getId())) {
                throw new HodiException("This money landed in " + accountLabel(account)
                        + ", which does not collect for "
                        + (development == null ? home.getTitle() : development.getName()) + ".",
                        HttpStatus.CONFLICT);
            }
        }

        // The same writer an automatic match uses, so a payment placed by hand carries the same names,
        // snapshots, channel and statement id as one that placed itself.
        Payment payment = paymentService.recordFromGateway(booking, statement, account);
        statement.placedOn(payment.getId(), booking.getId(), AuthContext.username());
        CoopStatement saved = statements.save(statement);

        audit.record(AppConstant.AUDIT_STATEMENT_ATTACHED, "CoopStatement", saved.getId(), before,
                snapshot(saved));
        log.info("Statement {} applied to booking {} as {} by {}", saved.getRefNo(), booking.getReference(),
                payment.getReference(), AuthContext.username());
        return toResponse(saved, lookups(List.of(saved)));
    }

    /**
     * Marks an unplaced credit as not a booking's money.
     *
     * <p>A placed one cannot be: the payment it was applied to is the fact on the buyer's balance, and
     * voiding that is a separate decision with its own reason. The void puts the statement back here.
     */
    @Transactional
    public StatementResponse setAside(String hashId, SetAsideRequest request) {
        UserPrincipal caller = AuthContext.require();
        CoopStatement statement = statements.lockById(HashIdUtil.decodeId(hashId))
                .filter(s -> scope.readsStatement(s, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Statement", hashId));
        if (statement.isMapped()) {
            throw new HodiException("This money is applied to booking "
                    + bookingReference(statement.getMappedBookingId()) + ". Void receipt "
                    + paymentReference(statement.getMappedPaymentId())
                    + " first; the credit comes back here when you do.", HttpStatus.CONFLICT);
        }
        if (statement.isIgnored()) return toResponse(statement, lookups(List.of(statement)));

        String before = snapshot(statement);
        statement.setAside(request.reason().trim(), AuthContext.username());
        CoopStatement saved = statements.save(statement);
        audit.record(AppConstant.AUDIT_STATEMENT_SET_ASIDE, "CoopStatement", saved.getId(), before,
                snapshot(saved));
        log.info("Statement {} set aside by {}: {}", saved.getRefNo(), AuthContext.username(),
                request.reason().trim());
        return toResponse(saved, lookups(List.of(saved)));
    }

    /** Brings a set-aside credit back to the queue. */
    @Transactional
    public StatementResponse restore(String hashId) {
        UserPrincipal caller = AuthContext.require();
        CoopStatement statement = statements.lockById(HashIdUtil.decodeId(hashId))
                .filter(s -> scope.readsStatement(s, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Statement", hashId));
        if (!statement.isIgnored()) {
            throw new HodiException("Only a credit that was set aside can be restored.", HttpStatus.CONFLICT);
        }
        String before = snapshot(statement);
        statement.restore(AuthContext.username());
        CoopStatement saved = statements.save(statement);
        audit.record(AppConstant.AUDIT_STATEMENT_RESTORED, "CoopStatement", saved.getId(), before,
                snapshot(saved));
        return toResponse(saved, lookups(List.of(saved)));
    }

    // ── rules ─────────────────────────────────────────────────────────────────

    private void assertOpen(UnitBooking booking) {
        String state = booking.getState();
        if (AppConstant.BOOKING_RESERVED.equals(state) || AppConstant.BOOKING_AGREED.equals(state)) return;
        throw new HodiException("That booking is " + state.toLowerCase(Locale.ROOT)
                + ", so there is nothing to credit. Choose a live booking, or re-book the unit first.",
                HttpStatus.CONFLICT);
    }

    private CoopStatement readable(String hashId, UserPrincipal caller) {
        return statements.findById(HashIdUtil.decodeId(hashId))
                .filter(s -> s.getStatus() != AppConstant.STATUS_DELETED)
                .filter(s -> scope.readsStatement(s, caller))
                .orElseThrow(() -> new ResourceNotFoundException("Statement", hashId));
    }

    /**
     * The accounts a channel or development filter narrows the list to, or null for no narrowing.
     *
     * <p>A statement knows the account it landed in and nothing about developments; the account knows
     * which development it collects for. So both filters become "these accounts".
     */
    private Collection<Long> accountsFor(StatementListRequest request) {
        Long typeId = HashIdUtil.decodeId(request.getPaymentTypeId());
        Long developmentId = HashIdUtil.decodeId(request.getDevelopmentId());
        if (typeId == null && developmentId == null) return null;

        List<PaymentAccount> candidates;
        if (developmentId != null) {
            Development development = developments.findById(developmentId).orElse(null);
            if (development == null) return List.of();
            List<PaymentAccount> owners = development.getInstitutionId() != null
                    ? accounts.findLiveForInstitution(development.getInstitutionId())
                    : development.getTenantId() != null
                    ? accounts.findLiveForTenant(development.getTenantId())
                    : accounts.findLiveForPlatform();
            candidates = owners.stream().filter(a -> a.reaches(developmentId)).toList();
        } else {
            candidates = accounts.findByPaymentTypeIdNotArchived(typeId);
        }
        return candidates.stream()
                .filter(a -> typeId == null || typeId.equals(a.getPaymentTypeId()))
                .map(PaymentAccount::getId)
                .toList();
    }

    private static <T> Specification<T> in(String field, Collection<Long> ids) {
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction() : root.get(field).in(ids);
    }

    // ── shaping ───────────────────────────────────────────────────────────────

    private record Lookups(Map<Long, PaymentAccount> accounts, Map<Long, PaymentType> types,
                           Map<Long, Payment> payments, Map<Long, UnitBooking> bookings) {}

    private Lookups lookups(List<CoopStatement> rows) {
        if (rows.isEmpty()) return new Lookups(Map.of(), Map.of(), Map.of(), Map.of());
        Map<Long, PaymentAccount> accs = accounts.findAllById(ids(rows, CoopStatement::getPaymentAccountId))
                .stream().collect(Collectors.toMap(PaymentAccount::getId, Function.identity()));
        Map<Long, PaymentType> tps = types.findAllById(
                        accs.values().stream().map(PaymentAccount::getPaymentTypeId)
                                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(PaymentType::getId, Function.identity()));
        Map<Long, Payment> pays = payments.findAllById(ids(rows, CoopStatement::getMappedPaymentId))
                .stream().collect(Collectors.toMap(Payment::getId, Function.identity()));
        Map<Long, UnitBooking> bks = bookings.findAllById(ids(rows, CoopStatement::getMappedBookingId))
                .stream().collect(Collectors.toMap(UnitBooking::getId, Function.identity()));
        return new Lookups(accs, tps, pays, bks);
    }

    private static List<Long> ids(List<CoopStatement> rows, Function<CoopStatement, Long> of) {
        return rows.stream().map(of).filter(Objects::nonNull).distinct().toList();
    }

    private StatementResponse toResponse(CoopStatement s, Lookups lookups) {
        PaymentAccount account = lookups.accounts().get(s.getPaymentAccountId());
        PaymentType type = account == null ? null : lookups.types().get(account.getPaymentTypeId());
        Payment payment = lookups.payments().get(s.getMappedPaymentId());
        UnitBooking booking = lookups.bookings().get(s.getMappedBookingId());
        String ownerKind = s.getInstitutionId() != null ? "INSTITUTION"
                : s.getTenantId() != null ? "TENANT" : "PLATFORM";
        return new StatementResponse(
                HashIdUtil.encodeId(s.getId()), s.getRefNo(), s.getOurReference(), s.getTraceId(),
                s.getState(), stateLabel(s.getState()),
                s.getAmount(), s.getCurrency(), s.getPaidAt(), s.getCreatedAt(),
                s.getReference(), s.getCustomerName(), s.getPhoneNo(), s.getTransType(),
                HashIdUtil.encodeId(s.getPaymentAccountId()),
                account == null ? s.getAccountIdentifier() : account.getAccountNo(),
                account == null ? null : account.getAccountName(),
                type == null ? null : HashIdUtil.encodeId(type.getId()),
                type == null ? null : type.getName(),
                account == null ? null : account.getCategory(),
                ownerKind,
                HashIdUtil.encodeId(s.getMappedPaymentId()),
                payment == null ? null : payment.getReference(),
                HashIdUtil.encodeId(s.getMappedBookingId()),
                booking == null ? null : booking.getReference(),
                payment == null ? null : payment.getUnitLabel(),
                s.getMappedAt(), s.getMappedBy(), s.getUnmappedReason());
    }

    static String stateLabel(String state) {
        if (state == null) return "—";
        return switch (state) {
            case AppConstant.STATEMENT_MAPPED -> "Used";
            case AppConstant.STATEMENT_UNMAPPED -> "Unused";
            case AppConstant.STATEMENT_IGNORED -> "Set aside";
            default -> state;
        };
    }

    private String bookingReference(Long bookingId) {
        return bookingId == null ? "—"
                : bookings.findById(bookingId).map(UnitBooking::getReference).orElse("—");
    }

    private String paymentReference(Long paymentId) {
        return paymentId == null ? "—"
                : payments.findById(paymentId).map(Payment::getReference).orElse("—");
    }

    private static String accountLabel(PaymentAccount account) {
        return account.getAccountName() == null ? "account " + account.getAccountNo()
                : account.getAccountName() + " (" + account.getAccountNo() + ")";
    }

    private static String reasonSuffix(CoopStatement statement) {
        return statement.getUnmappedReason() == null ? "." : ": " + statement.getUnmappedReason();
    }

    private static String snapshot(CoopStatement s) {
        return s.getRefNo() + " " + s.getCurrency() + " " + s.getAmount().toPlainString()
                + " quoting \"" + s.getReference() + "\" state=" + s.getState()
                + (s.getMappedPaymentId() == null ? "" : " payment=" + s.getMappedPaymentId())
                + (s.getMappedBookingId() == null ? "" : " booking=" + s.getMappedBookingId())
                + (s.getUnmappedReason() == null ? "" : " — " + s.getUnmappedReason());
    }

    private static String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
