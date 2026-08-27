package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.*;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentInventoryService;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnit;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Booking a unit, agreeing it, and the money against it.
 *
 * <h2>Who writes a unit's sale state</h2>
 *
 * <p>Two things could: this service and {@code DevelopmentUnitService.reserve/sell/release}. Two writers of one
 * field is how a unit ends up sold with no booking, or booked while showing available.
 *
 * <p>The boundary drawn: a unit with a live booking is this service's, and the unit service refuses to touch
 * it — pointing at the booking. A unit without one keeps the simple hold path, which is what a sales agent
 * taking a name over the phone actually wants and which involves no money at all. So there is exactly one
 * writer per unit at any moment, decided by whether a booking exists.
 *
 * <h2>The race, and why the index is the answer</h2>
 *
 * <p>{@link #create} asks whether a live booking exists and then writes one. Between those two statements
 * another request can do the same thing, and both would succeed: this is the textbook lost update, and no
 * amount of checking inside one transaction prevents it. {@code uk_booking_live_unit} makes the second insert
 * fail, and the failure is caught here and turned into the same message the check would have given.
 *
 * <h2>Balances come from the view</h2>
 *
 * <p>Never computed here. A screen and a report that each did their own arithmetic would be two balances, and
 * the day they disagreed nobody could say which was right.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /** The reservation window when the caller does not say. Matches the unit hold's default. */
    private static final int DEFAULT_HOLD_DAYS = 14;

    private final UnitBookingRepository repository;
    private final BookingInstalmentRepository instalments;
    private final BookingPaymentRepository payments;
    private final BookingBalanceReader balances;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitRepository units;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final AuditService audit;

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BookingResponse> forUnit(String developmentHashId, String unitHashId) {
        Development development = requireVisible(developmentHashId);
        DevelopmentUnit unit = requireUnit(development, unitHashId);
        return repository.findForUnit(unit.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse find(String developmentHashId, String bookingHashId) {
        Development development = requireVisible(developmentHashId);
        return toResponse(requireBooking(development, bookingHashId));
    }

    @Transactional(readOnly = true)
    public List<InstalmentResponse> schedule(String developmentHashId, String bookingHashId) {
        Development development = requireVisible(developmentHashId);
        UnitBooking booking = requireBooking(development, bookingHashId);
        return instalments.findCurrentPlan(booking.getId()).stream().map(this::toInstalment).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> paymentsFor(String developmentHashId, String bookingHashId) {
        Development development = requireVisible(developmentHashId);
        UnitBooking booking = requireBooking(development, bookingHashId);
        return payments.findForBooking(booking.getId()).stream().map(this::toPayment).toList();
    }

    // ── booking ───────────────────────────────────────────────────────────────

    /**
     * Books a unit for a buyer.
     *
     * <p>Refused when the unit already has a live booking, is sold, or was never inventory. The first of those
     * is checked here for the message and guaranteed by the index for the race.
     */
    @Transactional
    public BookingResponse create(String developmentHashId, CreateBookingRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = requireUnit(development, request.unitHashId());
        assertBookable(unit);

        DevelopmentUnitType type = unit.getUnitTypeId() == null ? null
                : unitTypes.findById(unit.getUnitTypeId()).orElse(null);

        BigDecimal price = request.priceAgreed() != null ? request.priceAgreed()
                : unit.getListPrice() != null ? unit.getListPrice()
                : type != null ? type.getListPrice() : null;

        int days = request.holdDays() == null ? DEFAULT_HOLD_DAYS : request.holdDays();

        UnitBooking booking = UnitBooking.builder()
                .reference(RrnGenerator.generate("BK"))
                .developmentId(development.getId())
                .unitId(unit.getId())
                .unitTypeId(unit.getUnitTypeId())
                // From the development, not the caller: a collaborator booking on a bank's project must not
                // make the row theirs, or their organisation would inherit sight of the buyer.
                .tenantId(development.getTenantId())
                .institutionId(development.getInstitutionId())
                .buyerName(request.buyerName().trim())
                .buyerPhone(request.buyerPhone().trim())
                .buyerEmail(blankToNull(request.buyerEmail()))
                .buyerIdNumber(blankToNull(request.buyerIdNumber()))
                .state(AppConstant.BOOKING_RESERVED)
                .priceAgreed(price)
                .currency(unit.getCurrency() == null ? "KES" : unit.getCurrency())
                .depositDue(request.depositDue())
                .paymentPlan(plan(request.paymentPlan()))
                .bookedOn(LocalDate.now())
                .expiresAt(OffsetDateTime.now().plusDays(days))
                .notes(blankToNull(request.notes()))
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();

        UnitBooking saved = save(booking, unit);

        if (request.instalments() != null && !request.instalments().isEmpty()) {
            writePlan(saved, request.instalments(), (short) 1);
        }

        applyToUnit(unit, saved);
        audit.record(AppConstant.ACTION_CREATE, "UnitBooking", saved.getId(), null, snapshot(saved));
        log.info("Unit {} booked for {} as {}", unit.getUnitLabel(), saved.getBuyerName(),
                saved.getReference());
        return toResponse(saved);
    }

    /**
     * The buyer has committed: the hold stops expiring.
     *
     * <p>{@code expires_at} is cleared rather than pushed out, because a commitment has no expiry and a column
     * still counting down would be read as though it did — by a person and by the sweep.
     */
    @Transactional
    public BookingResponse agree(String developmentHashId, String bookingHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        UnitBooking booking = requireBooking(development, bookingHashId);
        if (!booking.isReserved()) {
            throw new HodiException("Only a reserved booking can be agreed. That one is "
                    + booking.getState().toLowerCase() + ".", HttpStatus.CONFLICT);
        }
        String before = snapshot(booking);
        booking.setState(AppConstant.BOOKING_AGREED);
        booking.setExpiresAt(null);
        booking.setAgreedAt(OffsetDateTime.now());
        booking.setUpdatedBy(AuthContext.username());

        UnitBooking saved = repository.save(booking);
        DevelopmentUnit unit = units.findById(saved.getUnitId()).orElseThrow();
        applyToUnit(unit, saved);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Paid up and handed over. The unit becomes SOLD.
     *
     * <p>Refused while anything is outstanding, and the message says how much: completing a booking with a
     * balance is how a unit stops appearing on any chase list while still being owed for.
     */
    @Transactional
    public BookingResponse complete(String developmentHashId, String bookingHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        UnitBooking booking = requireBooking(development, bookingHashId);
        if (!booking.isLive()) {
            throw new HodiException("That booking is " + booking.getState().toLowerCase()
                    + ", so it cannot be completed.", HttpStatus.CONFLICT);
        }

        BigDecimal outstanding = balances.forBooking(booking.getId())
                .map(BalanceRow::balance).orElse(BigDecimal.ZERO);
        if (outstanding.compareTo(BigDecimal.ZERO) > 0) {
            throw new HodiException("There is still " + outstanding.toPlainString()
                    + " outstanding. Record the payments, or reduce the price agreed.",
                    HttpStatus.CONFLICT);
        }

        String before = snapshot(booking);
        booking.setState(AppConstant.BOOKING_COMPLETED);
        booking.setExpiresAt(null);
        booking.setCompletedAt(OffsetDateTime.now());
        booking.setUpdatedBy(AuthContext.username());

        UnitBooking saved = repository.save(booking);
        DevelopmentUnit unit = units.findById(saved.getUnitId()).orElseThrow();
        applyToUnit(unit, saved);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /** Called off, with a reason. The unit goes back on the market. */
    @Transactional
    public BookingResponse cancel(String developmentHashId, String bookingHashId,
                                  CloseBookingRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        UnitBooking booking = requireBooking(development, bookingHashId);
        if (!booking.isLive()) {
            throw new HodiException("That booking is already " + booking.getState().toLowerCase() + ".",
                    HttpStatus.CONFLICT);
        }
        return toResponse(close(booking, AppConstant.BOOKING_CANCELLED, request.reason().trim()));
    }

    // ── the schedule ──────────────────────────────────────────────────────────

    /**
     * Replaces the schedule with a new plan, leaving the old one readable.
     *
     * <p>A new {@code plan_no} rather than edits in place. What a buyer agreed to in March is still on the
     * record in June, which is the point of having agreed to it — and the conversation about moving dates
     * needs both versions in front of it.
     */
    @Transactional
    public List<InstalmentResponse> reschedule(String developmentHashId, String bookingHashId,
                                               RescheduleRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        UnitBooking booking = requireBooking(development, bookingHashId);
        if (booking.isCompleted()) {
            throw new HodiException("That booking is completed; its schedule is history now.",
                    HttpStatus.CONFLICT);
        }
        if (request.instalments().isEmpty()) {
            throw new HodiException("A schedule needs at least one instalment.", HttpStatus.BAD_REQUEST);
        }

        short next = (short) (instalments.currentPlanNo(booking.getId()) + 1);
        writePlan(booking, request.instalments(), next);

        booking.setNotes(appendNote(booking.getNotes(),
                "Schedule revised (plan " + next + "): " + request.reason().trim()));
        booking.setUpdatedBy(AuthContext.username());
        repository.save(booking);

        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", booking.getId(),
                "plan " + (next - 1), "plan " + next);
        return instalments.findCurrentPlan(booking.getId()).stream().map(this::toInstalment).toList();
    }

    // ── money ─────────────────────────────────────────────────────────────────

    /**
     * Records money received.
     *
     * <p>The manual path, and it is not a placeholder: a bank reconciling a cheque or an RTGS transfer needs
     * this in production permanently, and it means the figures do not wait on anybody's API key.
     */
    @Transactional
    public PaymentResponse recordPayment(String developmentHashId, String bookingHashId,
                                         RecordPaymentRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        UnitBooking booking = requireBooking(development, bookingHashId);
        if (AppConstant.BOOKING_CANCELLED.equals(booking.getState())
                || AppConstant.BOOKING_LAPSED.equals(booking.getState())) {
            // Not refused because money cannot arrive against a dead booking — it can, and often does, which
            // is exactly why the message says what to do rather than just saying no.
            throw new HodiException("That booking is " + booking.getState().toLowerCase()
                    + ". Re-book the unit before recording money against it.", HttpStatus.CONFLICT);
        }

        BookingPayment payment = BookingPayment.builder()
                .reference(RrnGenerator.generate("PY"))
                .bookingId(booking.getId())
                .tenantId(booking.getTenantId())
                .institutionId(booking.getInstitutionId())
                .paidOn(request.paidOn() == null ? LocalDate.now() : request.paidOn())
                .amount(request.amount())
                .currency(booking.getCurrency())
                .source(AppConstant.PAY_MANUAL)
                .method(method(request.method()))
                .quotedReference(blankToNull(request.quotedReference()))
                .externalReference(blankToNull(request.externalReference()))
                .payerName(blankToNull(request.payerName()))
                .payerPhone(blankToNull(request.payerPhone()))
                .notes(blankToNull(request.notes()))
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();

        BookingPayment saved = payments.save(payment);
        audit.record(AppConstant.ACTION_CREATE, "BookingPayment", saved.getId(), null,
                saved.getReference() + " " + saved.getAmount());
        log.info("Payment {} of {} recorded against booking {}", saved.getReference(), saved.getAmount(),
                booking.getReference());
        return toPayment(saved);
    }

    /**
     * Reverses a payment with a second, negative row.
     *
     * <p>Never an edit and never a delete. A balance a buyer has already been told changes only by an entry
     * that says why it changed; silently correcting the first row leaves their statement and ours disagreeing
     * with nothing to explain it.
     */
    @Transactional
    public PaymentResponse reversePayment(String developmentHashId, String bookingHashId,
                                          String paymentHashId, ReversePaymentRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        UnitBooking booking = requireBooking(development, bookingHashId);
        BookingPayment original = payments.findById(HashIdUtil.decodeId(paymentHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentHashId));
        if (!booking.getId().equals(original.getBookingId())) {
            throw new ResourceNotFoundException("Payment", paymentHashId);
        }
        if (original.isReversal()) {
            throw new HodiException("That row is itself a reversal.", HttpStatus.CONFLICT);
        }
        if (payments.reversalCount(original.getId()) > 0) {
            throw new HodiException("That payment has already been reversed.", HttpStatus.CONFLICT);
        }

        BookingPayment reversal = BookingPayment.builder()
                .reference(RrnGenerator.generate("PY"))
                .bookingId(booking.getId())
                .tenantId(booking.getTenantId())
                .institutionId(booking.getInstitutionId())
                .paidOn(LocalDate.now())
                .amount(original.getAmount().negate())
                .currency(original.getCurrency())
                .source(original.getSource())
                .method(original.getMethod())
                .externalReference(original.getExternalReference())
                .reversalOfId(original.getId())
                .reversalReason(request.reason().trim())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();

        BookingPayment saved = payments.save(reversal);
        audit.record(AppConstant.ACTION_CREATE, "BookingPayment", saved.getId(), null,
                "reversal of " + original.getReference() + ": " + request.reason().trim());
        return toPayment(saved);
    }

    // ── the sweep ─────────────────────────────────────────────────────────────

    /** Reservations already past their window. The sweeper's query; this service does not schedule itself. */
    @Transactional(readOnly = true)
    public List<Long> findLapsedIds() {
        return repository.findLapsed(OffsetDateTime.now()).stream().map(UnitBooking::getId).toList();
    }

    /**
     * Closes one expired reservation and frees its unit.
     *
     * <p>{@code REQUIRES_NEW}, and called from {@link BookingExpirySweeper} rather than from a loop in this
     * class. A self-invocation would not pass through the proxy: the annotation would be present and mean
     * nothing, every booking in a pass would share one transaction, and one bad row would roll back the whole
     * sweep. Two beans is what makes the boundary real — the same arrangement the saved-search dispatcher
     * uses, and for the same reason.
     *
     * <p>No session here. {@code AuthContext.username()} falls back to the system name, and every other value
     * needed is on the row — a scheduled job that read {@code TenantContext} would find it empty.
     *
     * <p>Returns false rather than throwing when the booking is no longer expired: between the query and this
     * call somebody may have agreed it, and that is a race with a correct outcome rather than an error.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean lapseOne(Long bookingId) {
        UnitBooking booking = repository.findById(bookingId).orElse(null);
        if (booking == null || !booking.isExpired()) return false;
        close(booking, AppConstant.BOOKING_LAPSED, "The reservation window passed.");
        return true;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Saves a new booking, turning the unique-index violation into the message the check would have given.
     *
     * <p>The check in {@link #assertBookable} handles the ordinary case and gives a good message. This handles
     * the case the check cannot: two requests arriving together, both seeing an unbooked unit. Without it the
     * loser gets a 500 and a stack trace about a constraint name.
     */
    private UnitBooking save(UnitBooking booking, DevelopmentUnit unit) {
        try {
            return repository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent booking refused for unit {}", unit.getUnitLabel());
            throw new HodiException("Somebody booked " + unit.getUnitLabel()
                    + " a moment ago. Refresh to see who has it.", HttpStatus.CONFLICT);
        }
    }

    private void assertBookable(DevelopmentUnit unit) {
        if (AppConstant.UNIT_SOLD.equals(unit.getSaleState())) {
            throw new HodiException("That unit is already sold.", HttpStatus.CONFLICT);
        }
        if (AppConstant.UNIT_NOT_FOR_SALE.equals(unit.getSaleState())
                || AppConstant.UNIT_RETAINED.equals(unit.getSaleState())) {
            throw new HodiException("That unit is not on offer.", HttpStatus.CONFLICT);
        }
        repository.findLiveForUnit(unit.getId()).ifPresent(live -> {
            throw new HodiException("That unit is booked by " + live.getBuyerName()
                    + " under " + live.getReference() + ".", HttpStatus.CONFLICT);
        });
    }

    /** Moves a booking to a closed state and frees its unit. Shared by cancel and the sweep. */
    private UnitBooking close(UnitBooking booking, String state, String reason) {
        String before = snapshot(booking);
        booking.setState(state);
        booking.setExpiresAt(null);
        booking.setClosedAt(OffsetDateTime.now());
        booking.setCloseReason(reason);
        booking.setUpdatedBy(AuthContext.username());

        UnitBooking saved = repository.save(booking);
        units.findById(saved.getUnitId()).ifPresent(unit -> applyToUnit(unit, saved));
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before, snapshot(saved));
        return saved;
    }

    /**
     * Puts the booking's state onto the unit it holds.
     *
     * <p>The unit's sale state and buyer fields are a cache of whatever booking currently holds it — the same
     * arrangement as the counters on a development, and for the same reason: a card and a table read the unit,
     * and neither should have to join to a booking to find out whether it is available.
     */
    private void applyToUnit(DevelopmentUnit unit, UnitBooking booking) {
        switch (booking.getState()) {
            /*
             * The two words mean different things, and the mapping is the point.
             *
             * HELD is a hold with a deadline; RESERVED is a commitment without one. Mapping both booking
             * states onto RESERVED put a unit in a state whose constraint demands an expiry while the agreed
             * booking had deliberately cleared it — so agreeing a booking could not be saved at all. It also
             * meant the inventory screen's "hold lapsed" marker would have fired on a committed sale.
             */
            case AppConstant.BOOKING_RESERVED -> {
                unit.setSaleState(AppConstant.UNIT_HELD);
                unit.setReservedAt(booking.getCreatedAt() == null
                        ? OffsetDateTime.now() : booking.getCreatedAt());
                unit.setReservedUntil(booking.getExpiresAt());
                copyBuyer(unit, booking);
            }
            case AppConstant.BOOKING_AGREED -> {
                unit.setSaleState(AppConstant.UNIT_RESERVED);
                unit.setReservedAt(booking.getCreatedAt() == null
                        ? OffsetDateTime.now() : booking.getCreatedAt());
                // No deadline: the buyer has committed, and a date here would be read as one.
                unit.setReservedUntil(null);
                copyBuyer(unit, booking);
            }
            case AppConstant.BOOKING_COMPLETED -> {
                unit.setSaleState(AppConstant.UNIT_SOLD);
                unit.setReservedUntil(null);
                unit.setSoldAt(OffsetDateTime.now());
                unit.setSoldPrice(booking.getPriceAgreed());
            }
            // Cancelled or lapsed: back on the market, and the buyer's details go with it. Keeping them on a
            // freed unit would show the next enquirer somebody else's name.
            default -> {
                unit.setSaleState(AppConstant.UNIT_AVAILABLE);
                unit.setReservedAt(null);
                unit.setReservedUntil(null);
                unit.setBuyerName(null);
                unit.setBuyerPhone(null);
                unit.setBuyerEmail(null);
                unit.setBuyerUserId(null);
            }
        }
        unit.setUpdatedBy(AuthContext.username());
        DevelopmentUnit saved = units.save(unit);
        inventory.recountUnitType(saved.getUnitTypeId());
    }

    private void copyBuyer(DevelopmentUnit unit, UnitBooking booking) {
        unit.setBuyerName(booking.getBuyerName());
        unit.setBuyerPhone(booking.getBuyerPhone());
        unit.setBuyerEmail(booking.getBuyerEmail());
        unit.setBuyerUserId(booking.getBuyerUserId());
    }

    private void writePlan(UnitBooking booking, List<InstalmentLine> lines, short planNo) {
        short seq = 1;
        for (InstalmentLine line : lines) {
            instalments.save(BookingInstalment.builder()
                    .bookingId(booking.getId())
                    .planNo(planNo)
                    .sequenceNo(seq++)
                    .label(blankToNull(line.label()))
                    .dueOn(line.dueOn())
                    .amount(line.amount())
                    .currency(booking.getCurrency())
                    .createdBy(AuthContext.username())
                    .updatedBy(AuthContext.username())
                    .build());
        }
    }

    private Development requireVisible(String hashId) {
        Development development = developments.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", hashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", hashId);
        }
        return development;
    }

    private DevelopmentUnit requireUnit(Development development, String hashId) {
        DevelopmentUnit unit = units.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Unit", hashId));
        // The development's id leads, so a unit belonging to nothing fails rather than throwing.
        if (!development.getId().equals(unit.getDevelopmentId())) {
            throw new ResourceNotFoundException("Unit", hashId);
        }
        return unit;
    }

    private UnitBooking requireBooking(Development development, String hashId) {
        UnitBooking booking = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", hashId));
        if (!development.getId().equals(booking.getDevelopmentId())) {
            throw new ResourceNotFoundException("Booking", hashId);
        }
        return booking;
    }

    private String plan(String requested) {
        if (requested == null || requested.isBlank()) return AppConstant.PLAN_INSTALMENTS;
        String value = requested.trim().toUpperCase();
        if (!AppConstant.PLAN_LUMP_SUM.equals(value) && !AppConstant.PLAN_INSTALMENTS.equals(value)) {
            throw new HodiException("A payment plan is either a lump sum or instalments.",
                    HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static final List<String> METHODS = List.of(
            AppConstant.PAY_CASH, AppConstant.PAY_CHEQUE, AppConstant.PAY_BANK_TRANSFER,
            AppConstant.PAY_MOBILE_MONEY, AppConstant.PAY_CARD, AppConstant.PAY_OTHER);

    private String method(String requested) {
        if (requested == null || requested.isBlank()) return AppConstant.PAY_BANK_TRANSFER;
        String value = requested.trim().toUpperCase();
        if (!METHODS.contains(value)) {
            throw new HodiException("That is not a way money arrives here.", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /** Appended rather than replaced: a note about a reschedule should not erase the one before it. */
    private String appendNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) return addition;
        return existing + "\n" + addition;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BookingResponse toResponse(UnitBooking b) {
        Optional<BalanceRow> balance = balances.forBooking(b.getId());
        DevelopmentUnit unit = units.findById(b.getUnitId()).orElse(null);
        String typeName = b.getUnitTypeId() == null ? null
                : unitTypes.findById(b.getUnitTypeId()).map(DevelopmentUnitType::getName).orElse(null);
        String developmentName = developments.findById(b.getDevelopmentId())
                .map(Development::getName).orElse(null);

        return new BookingResponse(
                HashIdUtil.encodeId(b.getId()), b.getReference(), developmentName,
                unit == null ? null : unit.getUnitLabel(),
                unit == null ? null : unit.getPayReference(),
                typeName,
                b.getBuyerName(), b.getBuyerPhone(), b.getBuyerEmail(), b.getBuyerIdNumber(),
                b.getState(), b.getPriceAgreed(), b.getDepositDue(), b.getCurrency(), b.getPaymentPlan(),
                b.getBookedOn(), b.getExpiresAt(), b.isExpired(), b.getAgreedAt(), b.getCompletedAt(),
                b.getClosedAt(), b.getCloseReason(), b.getNotes(),
                balance.map(BalanceRow::scheduled).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::paid).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::balance).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::overdue).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::nextDueOn).orElse(null),
                instalments.findCurrentPlan(b.getId()).size(),
                payments.findForBooking(b.getId()).size(),
                b.getCreatedAt(), b.getCreatedBy());
    }

    private InstalmentResponse toInstalment(BookingInstalment i) {
        return new InstalmentResponse(HashIdUtil.encodeId(i.getId()), i.getPlanNo(), i.getSequenceNo(),
                i.getLabel(), i.getDueOn(), i.getAmount(), i.getCurrency());
    }

    private PaymentResponse toPayment(BookingPayment p) {
        return new PaymentResponse(
                HashIdUtil.encodeId(p.getId()), p.getReference(), p.getPaidOn(), p.getAmount(),
                p.getCurrency(), p.getSource(), p.getMethod(), p.getQuotedReference(),
                p.getExternalReference(), p.getPayerName(), p.getPayerPhone(), p.isReversal(),
                p.getReversalReason(), payments.reversalCount(p.getId()) > 0, p.getNotes(),
                p.getCreatedAt(), p.getCreatedBy());
    }

    private String snapshot(UnitBooking b) {
        return b.getReference() + " " + b.getState() + " " + b.getBuyerName()
                + " " + (b.getPriceAgreed() == null ? "-" : b.getPriceAgreed().toPlainString());
    }
}
