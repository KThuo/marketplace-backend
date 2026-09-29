package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.BookingDtos.*;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentInventoryService;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.payments.PaymentDtos.PaymentResponse;
import com.hodi.modules.payments.PaymentQueryService;
import com.hodi.modules.payments.PaymentRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.sellerops.CommissionService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.common.PagedResponse;
import com.hodi.common.util.SearchSpecs;
import com.hodi.security.principal.AuthContext;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Booking a home, agreeing it, and the money against it.
 *
 * <h2>A booking is on a property</h2>
 *
 * <p>A unit of a development or a house on its own: both are rows of {@code properties}, and a booking does
 * not care which. What differs is who may write — a unit answers to its development's rule, a house to its
 * seller's — and {@link BookingAccess} says which applies. Every method here is reachable by the booking's own
 * id; the development-scoped variants exist for the inventory screen and check the development matches first.
 *
 * <h2>Who writes a home's sale state</h2>
 *
 * <p>Two things could: this service and {@code DevelopmentUnitService.reserve/sell/release}. Two writers of one
 * field is how a home ends up sold with no booking, or booked while showing available. The boundary drawn: a
 * home with a live booking is this service's, and the unit service refuses to touch it. A house is always this
 * service's — marking one sold writes a completed booking, so the sale has a buyer, a price and a place for
 * money to land.
 *
 * <h2>The race, and why the index is the answer</h2>
 *
 * <p>Create asks whether a live booking exists and then writes one. Between those two statements another
 * request can do the same thing, and both would succeed. {@code uk_booking_live_property} makes the second
 * insert fail, and the failure is caught here and turned into the same message the check would have given.
 *
 * <h2>Money is the payments module's</h2>
 *
 * <p>Receiving and voiding live in {@code PaymentService}. This service reads the results through the balance
 * view and refuses to complete a booking while anything is outstanding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final int DEFAULT_HOLD_DAYS = 14;

    private final UnitBookingRepository repository;
    private final PayCodeAllocator payCodes;
    private final BookingInstalmentRepository instalments;
    private final PaymentRepository payments;
    private final PaymentQueryService paymentQueries;
    private final BookingBalanceReader balances;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitRepository units;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final BookingAccess access;
    private final com.hodi.modules.payments.PaymentScope scope;
    private final CommissionService commissions;
    private final com.hodi.modules.agents.AgentProfileRepository agents;
    private final com.hodi.modules.agents.IntroducerService introducers;
    private final AuditService audit;
    private final com.hodi.modules.valuations.LendingValueService lendingValues;

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BookingResponse> forUnit(String developmentHashId, String unitHashId) {
        Development development = requireVisible(developmentHashId);
        Property unit = requireUnit(development, unitHashId);
        return repository.findForUnit(unit.getId()).stream().map(this::toResponse).toList();
    }

    /**
     * Every booking this caller may see, newest first.
     *
     * <h3>Scoped through {@code PaymentScope}, not a rule of its own</h3>
     *
     * <p>A booking carries the same owner columns a payment does — tenant, institution, development — and
     * who may see one is the same question. Writing a second rule here would be two places to keep in
     * agreement about whose money is whose, and they would disagree the first time either changed.
     *
     * <p>Balances come from {@code v_booking_balances} row by row, as they do everywhere else: what has
     * been paid is derived from the payments, never stored, so a list cannot show a figure the booking
     * itself would contradict.
     */
    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> list(BookingDtos.BookingListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<UnitBooking> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                scope.byDevelopment(caller),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                // Still owing, expressed on the booking's own columns: a live booking whose payments do
                // not add up to the price. Kept in the query so the count is the truth.
                owing(request.getOwing()),
                anyOf(request.getSearch(), "reference", "buyerName", "buyerPhone"));

        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));

        /*
         * Owing is a derived figure — v_booking_balances, not a column — so it is applied to the page
         * rather than to the query. Teaching the specification about the view would tie this list to how
         * a balance happens to be stored, which is the one thing the view exists to keep out of callers.
         *
         * The total stays the page's own: filtering a page after the fact cannot honestly restate how
         * many rows matched, and a count that shrinks as you page through is worse than one that counts
         * something slightly wider than the filter.
         */
        return PagedResponse.from(page.map(this::toResponse));
    }

    /**
     * Bookings that still owe something.
     *
     * <p>A subquery over the payments rather than the balance view, so it composes with the rest of the
     * specification and the total count means what it says. Voided payments are excluded, exactly as the
     * view excludes them — a reversed payment never reduced anybody's balance.
     */
    private static Specification<UnitBooking> owing(Boolean wanted) {
        if (!Boolean.TRUE.equals(wanted)) return null;
        return (root, query, cb) -> {
            var paid = query.subquery(java.math.BigDecimal.class);
            var payment = paid.from(com.hodi.modules.payments.Payment.class);
            paid.select(cb.coalesce(cb.sum(payment.get("amount")), java.math.BigDecimal.ZERO))
                    .where(cb.equal(payment.get("bookingId"), root.get("id")),
                            cb.notEqual(payment.get("status"), AppConstant.PAYMENT_VOIDED),
                            cb.notEqual(payment.get("status"), AppConstant.STATUS_DELETED));
            return cb.and(
                    cb.isNotNull(root.get("priceAgreed")),
                    cb.greaterThan(root.get("priceAgreed"), paid));
        };
    }

    /** Free text across the columns somebody would actually search a booking by. */
    private static Specification<UnitBooking> anyOf(String search, String... fields) {
        if (search == null || search.isBlank()) return null;
        String like = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(java.util.Arrays.stream(fields)
                .map(f -> cb.like(cb.lower(root.get(f)), like))
                .toArray(jakarta.persistence.criteria.Predicate[]::new));
    }

    /** Every booking on a home, newest first — the house's own history, or a unit's. */
    @Transactional(readOnly = true)
    public List<BookingResponse> forProperty(String propertyHashId) {
        Property property = access.requireReadable(propertyHashId, AuthContext.require());
        return repository.findForUnit(property.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse find(String developmentHashId, String bookingHashId) {
        Development development = requireVisible(developmentHashId);
        return toResponse(requireBooking(development, bookingHashId));
    }

    @Transactional(readOnly = true)
    public BookingResponse find(String bookingHashId) {
        return toResponse(requireReadable(bookingHashId));
    }

    // ── the buyer's own ───────────────────────────────────────────────────────

    /** The signed-in buyer's bookings, newest first, with what each owes. */
    @Transactional(readOnly = true)
    public List<BookingResponse> mine() {
        UserPrincipal caller = AuthContext.require();
        return repository.findForBuyer(caller.getUserId()).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse mine(String bookingHashId) {
        return toResponse(requireMine(bookingHashId));
    }

    @Transactional(readOnly = true)
    public List<InstalmentResponse> mySchedule(String bookingHashId) {
        return scheduleOf(requireMine(bookingHashId));
    }

    /**
     * The booking, if it is the caller's own.
     *
     * <p>By identity, not by name: a booking carries the buyer's user id when they signed up, and that is
     * the only thing that makes "my bookings" mean anything. A booking taken over the counter for somebody
     * with no account is nobody's to see here until the sales office links it.
     */
    public UnitBooking requireMine(String bookingHashId) {
        UserPrincipal caller = AuthContext.require();
        return repository.findById(HashIdUtil.decodeId(bookingHashId))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .filter(b -> b.getBuyerUserId() != null && b.getBuyerUserId().equals(caller.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHashId));
    }

    @Transactional(readOnly = true)
    public List<InstalmentResponse> schedule(String developmentHashId, String bookingHashId) {
        Development development = requireVisible(developmentHashId);
        return scheduleOf(requireBooking(development, bookingHashId));
    }

    @Transactional(readOnly = true)
    public List<InstalmentResponse> schedule(String bookingHashId) {
        return scheduleOf(requireReadable(bookingHashId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> paymentsFor(String developmentHashId, String bookingHashId) {
        Development development = requireVisible(developmentHashId);
        return paymentQueries.forBooking(requireBooking(development, bookingHashId).getId());
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> paymentsFor(String bookingHashId) {
        return paymentQueries.forBooking(requireReadable(bookingHashId).getId());
    }

    // ── booking ───────────────────────────────────────────────────────────────

    /** The inventory screen's way in: a unit named inside its development. */
    @Transactional
    public BookingResponse create(String developmentHashId, CreateBookingRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);
        if (request.unitHashId() == null || request.unitHashId().isBlank()) {
            throw new HodiException("Say which unit.", HttpStatus.BAD_REQUEST);
        }
        Property unit = requireUnit(development, request.unitHashId());
        return toResponse(book(unit, development, request, AppConstant.BOOKING_RESERVED));
    }

    /** A home named directly: a house, or a unit reached from its own page. */
    @Transactional
    public BookingResponse createForProperty(String propertyHashId, CreateBookingRequest request) {
        UserPrincipal caller = AuthContext.require();
        Property property = access.requireReadable(propertyHashId, caller);
        access.assertMayWrite(property, caller);
        if (property.isUnitTypeListing()) {
            throw new HodiException("That is the card for a kind of home. Book one of its units.",
                    HttpStatus.CONFLICT);
        }
        return toResponse(book(property, access.developmentOf(property), request, AppConstant.BOOKING_RESERVED));
    }

    /**
     * A sale made off the platform, recorded as a completed booking.
     *
     * <p>Where a live booking exists it is completed instead, with the balance check that implies: the money
     * has to be in before the home is sold. Where none exists, a booking is written already completed, at the
     * price given or the home's own, so the sale has a buyer and a place for late money to land.
     */
    @Transactional
    public BookingResponse recordSale(Property property, MarkSoldRequest request) {
        UserPrincipal caller = AuthContext.require();
        access.assertMayWrite(property, caller);
        Optional<UnitBooking> live = repository.findLiveForUnit(property.getId());
        if (live.isPresent()) return toResponse(complete(live.get()));

        if (property.isSoldUnit() || AppConstant.LISTING_SOLD.equals(property.getListingState())) {
            throw new HodiException("That home is already sold.", HttpStatus.CONFLICT);
        }
        String buyer = request == null || request.buyerName() == null || request.buyerName().isBlank()
                ? "Buyer not recorded" : request.buyerName().trim();
        String phone = request == null || request.buyerPhone() == null || request.buyerPhone().isBlank()
                ? "-" : request.buyerPhone().trim();
        CreateBookingRequest asBooking = new CreateBookingRequest(null, buyer, phone,
                request == null ? null : request.buyerEmail(), null,
                request == null ? null : request.price(), null, AppConstant.PLAN_LUMP_SUM, null,
                request == null ? null : request.note(), null);
        UnitBooking booking = book(property, access.developmentOf(property), asBooking, AppConstant.BOOKING_COMPLETED);
        return toResponse(booking);
    }

    private UnitBooking book(Property property, Development development, CreateBookingRequest request,
                             String state) {
        assertBookable(property);
        DevelopmentUnitType type = property.getUnitTypeId() == null ? null
                : unitTypes.findById(property.getUnitTypeId()).orElse(null);
        BigDecimal price = request.priceAgreed() != null ? request.priceAgreed()
                : property.getPrice() != null ? property.getPrice()
                : type != null ? type.getListPrice() : null;
        if (AppConstant.BOOKING_COMPLETED.equals(state) && price == null) {
            throw new HodiException("Say what it sold for — the home has no price of its own.",
                    HttpStatus.BAD_REQUEST);
        }
        int days = request.holdDays() == null ? DEFAULT_HOLD_DAYS : request.holdDays();
        boolean completed = AppConstant.BOOKING_COMPLETED.equals(state);
        OffsetDateTime now = OffsetDateTime.now();
        UnitBooking booking = UnitBooking.builder()
                .reference(RrnGenerator.generate("BK"))
                // Allocated here, in the transaction that inserts the row: a booking never exists without the
                // code a buyer will be told to pay against.
                .payReference(payCodes.next())
                .developmentId(development == null ? null : development.getId())
                .propertyId(property.getId())
                .unitTypeId(property.getUnitTypeId())
                /*
                 * From the development where there is one, not the caller: a collaborator booking on a bank's
                 * project must not make the row theirs, or their organisation would inherit sight of the buyer.
                 * A house has only its seller.
                 */
                .tenantId(development == null ? property.getTenantId() : development.getTenantId())
                .institutionId(development == null ? property.getInstitutionId() : development.getInstitutionId())
                .buyerName(request.buyerName().trim())
                .buyerPhone(request.buyerPhone().trim())
                .buyerEmail(blankToNull(request.buyerEmail()))
                .buyerIdNumber(blankToNull(request.buyerIdNumber()))
                .state(state)
                .priceAgreed(price)
                .currency(property.getCurrency() == null ? "KES" : property.getCurrency())
                .depositDue(request.depositDue())
                .paymentPlan(plan(request.paymentPlan()))
                .bookedOn(LocalDate.now())
                .expiresAt(completed ? null : now.plusDays(days))
                .agreedAt(completed ? now : null)
                .completedAt(completed ? now : null)
                .notes(blankToNull(request.notes()))
                .introducedByAgentId(introducer(request.introducedByAgentRef()).map(a -> a.getId()).orElse(null))
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();
        UnitBooking saved = save(booking, property);
        if (request.instalments() != null && !request.instalments().isEmpty()) {
            writePlan(saved, request.instalments(), (short) 1);
        }
        applyToProperty(property, saved);
        audit.record(AppConstant.ACTION_CREATE, "UnitBooking", saved.getId(), null, snapshot(saved));
        log.info("{} booked for {} as {} ({})", labelOf(property), saved.getBuyerName(), saved.getReference(),
                state.toLowerCase());
        return saved;
    }

    /**
     * Names, or clears, the agent who brought this buyer.
     *
     * <p>While the booking is live only. Once it has completed a commission line has been raised against
     * whoever was named, and changing the name would leave the line saying one thing and the booking another;
     * a correction after that voids the line and raises another, which is a decision, not an edit.
     */
    @Transactional
    public BookingResponse setIntroducer(String bookingHashId, IntroducerRequest request) {
        UnitBooking booking = requireWritable(bookingHashId);
        if (!booking.isLive()) {
            throw new HodiException("Who brought the buyer is set while the booking is live; this one is "
                    + booking.getState().toLowerCase() + ".", HttpStatus.CONFLICT);
        }
        Long agentId = introducer(request == null ? null : request.agentRef())
                .map(com.hodi.modules.agents.AgentProfile::getId).orElse(null);
        String before = snapshot(booking) + " introducer=" + booking.getIntroducedByAgentId();
        booking.setIntroducedByAgentId(agentId);
        booking.setUpdatedBy(AuthContext.username());
        UnitBooking saved = repository.save(booking);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before,
                snapshot(saved) + " introducer=" + agentId);
        return toResponse(saved);
    }

    /** The agent a reference names, if it names an approved one. Blank means nobody. One rule for everybody. */
    private Optional<com.hodi.modules.agents.AgentProfile> introducer(String agentRef) {
        return introducers.resolve(agentRef);
    }

    /**
     * The buyer has committed: the hold stops expiring.
     *
     * <p>{@code expires_at} is cleared rather than pushed out, because a commitment has no expiry and a column
     * still counting down would be read as though it did — by a person and by the sweep.
     */
    @Transactional
    public BookingResponse agree(String developmentHashId, String bookingHashId) {
        return agree(HashIdUtil.encodeId(requireBooking(requireVisible(developmentHashId), bookingHashId).getId()));
    }

    @Transactional
    public BookingResponse agree(String bookingHashId) {
        UnitBooking booking = requireWritable(bookingHashId);
        if (!AppConstant.BOOKING_RESERVED.equals(booking.getState())) {
            throw new HodiException("Only a reserved booking can be agreed; this one is "
                    + booking.getState().toLowerCase() + ".", HttpStatus.CONFLICT);
        }
        return toResponse(markAgreed(booking, AuthContext.username()));
    }

    /**
     * The first payment agrees the booking.
     *
     * <p>A hold is a name and a clock; paying against it is the buyer saying yes. So the moment money lands
     * on a reserved booking it becomes agreed — the clock stops, the home is reserved rather than held — and
     * nobody has to remember to press Agree after taking the deposit. A booking already agreed, completed or
     * closed is left as it is: a late payment on a cancelled booking is the queue's problem, not a
     * reopening. Same transaction as the payment, so the two commit together.
     */
    @org.springframework.context.event.EventListener
    public void onPaymentReceived(com.hodi.modules.payments.PaymentReceived event) {
        UnitBooking booking = repository.findById(event.bookingId()).orElse(null);
        if (booking == null || !AppConstant.BOOKING_RESERVED.equals(booking.getState())) return;
        markAgreed(booking, event.by() == null ? AppConstant.USERNAME_SYSTEM : event.by());
        log.info("Booking {} agreed by its first payment", booking.getReference());
    }

    /** Reserved → agreed: the clock stops, the home is reserved rather than held, and the change is audited. */
    private UnitBooking markAgreed(UnitBooking booking, String by) {
        String before = snapshot(booking);
        booking.setState(AppConstant.BOOKING_AGREED);
        booking.setExpiresAt(null);
        booking.setAgreedAt(OffsetDateTime.now());
        booking.setUpdatedBy(by);
        UnitBooking saved = repository.save(booking);
        units.findById(saved.getPropertyId()).ifPresent(home -> applyToProperty(home, saved));
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before, snapshot(saved));
        return saved;
    }

    /**
     * Paid up and handed over. The home becomes SOLD.
     *
     * <p>Refused while anything is outstanding, and the message says how much: completing a booking with a
     * balance is how a home stops appearing on any chase list while still being owed for.
     */
    @Transactional
    public BookingResponse complete(String developmentHashId, String bookingHashId) {
        return complete(HashIdUtil.encodeId(requireBooking(requireVisible(developmentHashId), bookingHashId).getId()));
    }

    @Transactional
    public BookingResponse complete(String bookingHashId) {
        return toResponse(complete(requireWritable(bookingHashId)));
    }

    private UnitBooking complete(UnitBooking booking) {
        if (!booking.isLive()) {
            throw new HodiException("That booking is " + booking.getState().toLowerCase()
                    + ", so it cannot be completed.", HttpStatus.CONFLICT);
        }
        BigDecimal outstanding = balances.forBooking(booking.getId())
                .map(BalanceRow::balance).orElse(BigDecimal.ZERO);
        if (outstanding.compareTo(BigDecimal.ZERO) > 0) {
            throw new HodiException("There is still " + outstanding.toPlainString()
                    + " outstanding. Record the payments, or reduce the price agreed.", HttpStatus.CONFLICT);
        }
        String before = snapshot(booking);
        booking.setState(AppConstant.BOOKING_COMPLETED);
        booking.setExpiresAt(null);
        booking.setCompletedAt(OffsetDateTime.now());
        booking.setUpdatedBy(AuthContext.username());
        UnitBooking saved = repository.save(booking);
        applyToProperty(access.propertyOf(saved), saved);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before, snapshot(saved));
        return saved;
    }

    /** Called off, with a reason. The home goes back on the market. */
    @Transactional
    public BookingResponse cancel(String developmentHashId, String bookingHashId, CloseBookingRequest request) {
        return cancel(HashIdUtil.encodeId(requireBooking(requireVisible(developmentHashId), bookingHashId).getId()),
                request);
    }

    @Transactional
    public BookingResponse cancel(String bookingHashId, CloseBookingRequest request) {
        UnitBooking booking = requireWritable(bookingHashId);
        if (!booking.isLive()) {
            throw new HodiException("That booking is already " + booking.getState().toLowerCase() + ".",
                    HttpStatus.CONFLICT);
        }
        return toResponse(close(booking, AppConstant.BOOKING_CANCELLED, request.reason().trim()));
    }

    // ── the schedule ──────────────────────────────────────────────────────────

    @Transactional
    public List<InstalmentResponse> reschedule(String developmentHashId, String bookingHashId,
                                               RescheduleRequest request) {
        return reschedule(HashIdUtil.encodeId(requireBooking(requireVisible(developmentHashId), bookingHashId).getId()),
                request);
    }

    @Transactional
    public List<InstalmentResponse> reschedule(String bookingHashId, RescheduleRequest request) {
        UnitBooking booking = requireWritable(bookingHashId);
        if (booking.isCompleted()) {
            throw new HodiException("That booking is completed; its schedule is history now.", HttpStatus.CONFLICT);
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
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", booking.getId(), "plan " + (next - 1), "plan " + next);
        return scheduleOf(booking);
    }

    // ── the sweep ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Long> findLapsedIds() {
        return repository.findLapsed(OffsetDateTime.now()).stream().map(UnitBooking::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean lapseOne(Long bookingId) {
        UnitBooking booking = repository.findById(bookingId).orElse(null);
        if (booking == null || !booking.isExpired()) return false;
        close(booking, AppConstant.BOOKING_LAPSED, "The reservation window passed.");
        return true;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private UnitBooking close(UnitBooking booking, String state, String reason) {
        String before = snapshot(booking);
        booking.setState(state);
        booking.setExpiresAt(null);
        booking.setClosedAt(OffsetDateTime.now());
        booking.setCloseReason(reason);
        booking.setUpdatedBy(AuthContext.username());
        UnitBooking saved = repository.save(booking);
        units.findById(saved.getPropertyId()).ifPresent(home -> applyToProperty(home, saved));
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", saved.getId(), before, snapshot(saved));
        return saved;
    }

    private UnitBooking save(UnitBooking booking, Property property) {
        try {
            return repository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            // Two constraints can refuse the row: one live booking per home, and one pay code per booking. The
            // second is a random draw two bookings made at the same instant, and trying again will succeed.
            String cause = e.getMostSpecificCause() == null ? "" : String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("uk_booking_pay_reference")) {
                log.info("Pay code {} was taken between the check and the insert", booking.getPayReference());
                throw new HodiException("The payment code drawn for this booking was taken a moment ago. Try again.",
                        HttpStatus.CONFLICT);
            }
            log.info("Concurrent booking refused for {}", labelOf(property));
            throw new HodiException("Somebody booked " + labelOf(property)
                    + " a moment ago. Refresh to see who has it.", HttpStatus.CONFLICT);
        }
    }

    private void assertBookable(Property property) {
        if (property.isSoldUnit() || AppConstant.LISTING_SOLD.equals(property.getListingState())) {
            throw new HodiException("That home is already sold.", HttpStatus.CONFLICT);
        }
        if (AppConstant.UNIT_NOT_FOR_SALE.equals(property.getSaleState())
                || AppConstant.UNIT_RETAINED.equals(property.getSaleState())) {
            throw new HodiException("That unit is not on offer.", HttpStatus.CONFLICT);
        }
        repository.findLiveForUnit(property.getId()).ifPresent(live -> {
            throw new HodiException(labelOf(property) + " is booked by " + live.getBuyerName()
                    + " under " + live.getReference() + ".", HttpStatus.CONFLICT);
        });
    }

    /**
     * The booking's state, written onto the home.
     *
     * <p>One writer for the sale columns on a booked home. RESERVED is a hold with an expiry; AGREED is a
     * commitment with none; COMPLETED is sold; anything closed puts the home back on offer with the buyer's
     * details gone — keeping them on a freed home would show the next enquirer somebody else's name.
     *
     * <p>A unit's listing state follows its project except when sold. A house's is untouched until sold: a
     * house under a live booking stays on the marketplace with its sale state saying so.
     */
    private void applyToProperty(Property home, UnitBooking booking) {
        switch (booking.getState()) {
            case AppConstant.BOOKING_RESERVED -> {
                home.setSaleState(AppConstant.UNIT_HELD);
                home.setReservedAt(booking.getCreatedAt() == null ? OffsetDateTime.now() : booking.getCreatedAt());
                home.setReservedUntil(booking.getExpiresAt());
                copyBuyer(home, booking);
            }
            case AppConstant.BOOKING_AGREED -> {
                home.setSaleState(AppConstant.UNIT_RESERVED);
                home.setReservedAt(booking.getCreatedAt() == null ? OffsetDateTime.now() : booking.getCreatedAt());
                home.setReservedUntil(null);
                copyBuyer(home, booking);
            }
            case AppConstant.BOOKING_COMPLETED -> {
                home.setSaleState(AppConstant.UNIT_SOLD);
                home.setReservedUntil(null);
                home.setSoldAt(OffsetDateTime.now());
                home.setSoldPrice(booking.getPriceAgreed());
                home.setListingState(AppConstant.LISTING_SOLD);
                copyBuyer(home, booking);
            }
            default -> {
                home.setSaleState(AppConstant.UNIT_AVAILABLE);
                home.setReservedAt(null);
                home.setReservedUntil(null);
                home.setBuyerName(null);
                home.setBuyerPhone(null);
                home.setBuyerEmail(null);
                home.setBuyerUserId(null);
            }
        }
        Development development = home.isUnit() && home.getDevelopmentId() != null
                ? developments.findById(home.getDevelopmentId()).orElse(null) : null;
        if (development != null) DevelopmentInventoryService.applyListingState(home, development);
        home.setUpdatedBy(AuthContext.username());
        Property saved = units.save(home);
        if (saved.getUnitTypeId() != null) inventory.recountUnitType(saved.getUnitTypeId());
        // What the sale pays the bank, and the agent who brought the buyer — a unit or a house alike. Raised
        // from the rates in force and copied onto the lines; never thrown back into here, because the sale is
        // the fact and what it pays is a consequence.
        if (AppConstant.BOOKING_COMPLETED.equals(booking.getState())) {
            commissions.raiseFor(booking, saved, development);
        }
    }

    private void copyBuyer(Property home, UnitBooking booking) {
        home.setBuyerName(booking.getBuyerName());
        home.setBuyerPhone(booking.getBuyerPhone());
        home.setBuyerEmail(booking.getBuyerEmail());
        home.setBuyerUserId(booking.getBuyerUserId());
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

    private List<InstalmentResponse> scheduleOf(UnitBooking booking) {
        return instalments.findCurrentPlan(booking.getId()).stream().map(this::toInstalment).toList();
    }

    private Development requireVisible(String hashId) {
        Development development = developments.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", hashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", hashId);
        }
        return development;
    }

    private Property requireUnit(Development development, String hashId) {
        Property unit = units.findById(HashIdUtil.decodeId(hashId))
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

    /** A booking the caller may see, through the home it is on. Not found otherwise. */
    private UnitBooking requireReadable(String hashId) {
        UnitBooking booking = repository.findById(HashIdUtil.decodeId(hashId))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", hashId));
        if (!access.mayRead(access.propertyOf(booking), AuthContext.require())) {
            throw new ResourceNotFoundException("Booking", hashId);
        }
        return booking;
    }

    private UnitBooking requireWritable(String hashId) {
        UnitBooking booking = requireReadable(hashId);
        access.assertMayWrite(access.propertyOf(booking), AuthContext.require());
        return booking;
    }

    private String plan(String requested) {
        if (requested == null || requested.isBlank()) return AppConstant.PLAN_INSTALMENTS;
        String value = requested.trim().toUpperCase();
        if (!AppConstant.PLAN_LUMP_SUM.equals(value) && !AppConstant.PLAN_INSTALMENTS.equals(value)) {
            throw new HodiException("A payment plan is either a lump sum or instalments.", HttpStatus.BAD_REQUEST);
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

    /** "B-14" for a unit, the title for a house — what a message should call the home. */
    private static String labelOf(Property home) {
        return home.getUnitLabel() != null ? home.getUnitLabel() : home.getTitle();
    }

    private BookingResponse toResponse(UnitBooking b) {
        Optional<BalanceRow> balance = balances.forBooking(b.getId());
        Property home = units.findById(b.getPropertyId()).orElse(null);
        String typeName = b.getUnitTypeId() == null ? null
                : unitTypes.findById(b.getUnitTypeId()).map(DevelopmentUnitType::getName).orElse(null);
        String developmentName = b.getDevelopmentId() == null ? null
                : developments.findById(b.getDevelopmentId()).map(Development::getName).orElse(null);
        Optional<com.hodi.modules.agents.AgentProfile> introducer = b.getIntroducedByAgentId() == null
                ? Optional.empty() : agents.findById(b.getIntroducedByAgentId());
        return new BookingResponse(
                HashIdUtil.encodeId(b.getId()), b.getReference(), developmentName,
                home == null ? null : home.getUnitLabel(),
                b.getPayReference(),
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
                (int) payments.countReceived(b.getId()),
                b.getCreatedAt(), b.getCreatedBy(),
                HashIdUtil.encodeId(b.getPropertyId()),
                home == null ? null : home.getTitle(),
                home == null ? null : home.getListingKind(),
                introducer.map(com.hodi.modules.agents.AgentProfile::getReference).orElse(null),
                introducer.map(com.hodi.modules.agents.AgentProfile::getFullName).orElse(null),
                lendingValues.latestFor(b.getPropertyId(),
                        b.getPriceAgreed() != null ? b.getPriceAgreed() : home == null ? null : home.getPrice()).orElse(null));
    }

    private InstalmentResponse toInstalment(BookingInstalment i) {
        return new InstalmentResponse(HashIdUtil.encodeId(i.getId()), i.getPlanNo(), i.getSequenceNo(),
                i.getLabel(), i.getDueOn(), i.getAmount(), i.getCurrency());
    }

    private String snapshot(UnitBooking b) {
        return b.getReference() + " " + b.getState() + " " + b.getBuyerName()
                + " " + (b.getPriceAgreed() == null ? "-" : b.getPriceAgreed().toPlainString());
    }
}
