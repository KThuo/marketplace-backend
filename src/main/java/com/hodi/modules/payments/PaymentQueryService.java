package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.bookings.BookingBalanceReader;
import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import com.hodi.modules.bookings.BookingInstalment;
import com.hodi.modules.bookings.BookingInstalmentRepository;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.payments.PaymentDtos.*;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading payments.
 *
 * <p>Separate from {@link PaymentService} because receiving money is transactional and reading is scoped and
 * paged, and mixing them is how a payment service reaches a thousand lines.
 *
 * <p>Every list goes through {@link PaymentScope}: a payment follows its development, and a lending
 * institution may own one outright, so the shared tenant scope cannot be used. Guessing an id for another
 * organisation's payment gets the same answer as one that does not exist.
 */
@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    /** Enough live bookings for a picker to be useful. Beyond this it is a search, not a list. */
    private static final int PICKER_LIMIT = 20;

    private final PaymentRepository payments;
    private final UnitBookingRepository bookings;
    private final BookingInstalmentRepository instalments;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitRepository units;
    private final DevelopmentVisibility visibility;
    private final com.hodi.modules.bookings.BookingAccess access;
    private final BookingBalanceReader balances;
    private final PaymentScope scope;

    @Transactional(readOnly = true)
    public PagedResponse<PaymentResponse> list(PaymentListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<Payment> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("developmentId", HashIdUtil.decodeId(request.getDevelopmentId())),
                SearchSpecs.eq("bookingId", HashIdUtil.decodeId(request.getBookingId())),
                SearchSpecs.eq("paymentTypeId", HashIdUtil.decodeId(request.getPaymentTypeId())),
                SearchSpecs.eq("method", upperOrNull(request.getMethod())),
                SearchSpecs.eq("source", upperOrNull(request.getSource())),
                SearchSpecs.between("paidOn", request.getFrom(), request.getTo()),
                scope.byDevelopment(caller));
        var page = payments.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.DESC, "paidOn").and(Sort.by(Sort.Direction.DESC, "id"))));
        Lookups lookups = lookups(page.getContent());
        return PagedResponse.from(page, p -> toResponse(p, lookups));
    }

    /** A booking's payments, newest first, voided ones included and marked. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> forBooking(Long bookingId) {
        List<Payment> rows = payments.findForBooking(bookingId);
        Lookups lookups = lookups(rows);
        return rows.stream().map(p -> toResponse(p, lookups)).toList();
    }

    /** One payment with the booking it was applied to as it stands now — the receipt. */
    @Transactional(readOnly = true)
    public PaymentDetail detail(String hashId) {
        Payment payment = scoped(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment", hashId));
        return detailOf(payment);
    }

    /** By receipt number, which is how somebody arrives holding a printout. */
    @Transactional(readOnly = true)
    public PaymentDetail byReference(String reference) {
        Payment payment = payments.findByReference(reference)
                .flatMap(p -> scoped(p.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment", reference));
        return detailOf(payment);
    }

    /**
     * What a booking owes and what has arrived, for the receive form.
     *
     * <p>Shown before anything is submitted. Keying money against the wrong booking is the mistake this module
     * can actually make, and naming the home, the buyer and the balance is what catches it.
     */
    @Transactional(readOnly = true)
    public BookingBalance balanceOf(String bookingHash) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(bookingHash))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHash));
        Property home = units.findById(booking.getPropertyId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHash));
        if (!access.mayRead(home, caller)) throw new ResourceNotFoundException("Booking", bookingHash);
        return balanceOf(booking, access.developmentOf(home), home);
    }

    /**
     * The live bookings the caller may record money against, narrowed by what they typed.
     *
     * <p>The receive form's picker. Live only — reserved or agreed — because money against a lapsed or
     * cancelled booking is refused anyway, and offering one would be offering a refusal.
     */
    @Transactional(readOnly = true)
    public List<BookingOption> bookingOptions(String search) {
        UserPrincipal caller = AuthContext.require();
        Specification<UnitBooking> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                (root, query, cb) -> root.get("state").in(
                        AppConstant.BOOKING_RESERVED, AppConstant.BOOKING_AGREED),
                SearchSpecs.fuzzy("searchText", search),
                scope.byDevelopment(caller));
        List<UnitBooking> rows = bookings.findAll(spec,
                PageRequest.of(0, PICKER_LIMIT, Sort.by(Sort.Direction.DESC, "bookedOn"))).getContent();
        if (rows.isEmpty()) return List.of();

        Map<Long, String> devNames = developments.findAllById(
                        rows.stream().map(UnitBooking::getDevelopmentId).filter(Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Development::getId, Development::getName));
        Map<Long, Property> homes = units.findAllById(
                        rows.stream().map(UnitBooking::getPropertyId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Property::getId, Function.identity()));

        return rows.stream().map(b -> new BookingOption(
                HashIdUtil.encodeId(b.getId()), b.getReference(), b.getState(),
                b.getBuyerName(), b.getBuyerPhone(),
                // A house has no project: its title stands where the project's name would.
                b.getDevelopmentId() == null
                        ? (homes.get(b.getPropertyId()) == null ? null : homes.get(b.getPropertyId()).getTitle())
                        : devNames.get(b.getDevelopmentId()),
                homes.get(b.getPropertyId()) == null ? null : homes.get(b.getPropertyId()).getUnitLabel(),
                b.getCurrency(),
                balances.forBooking(b.getId()).map(BalanceRow::balance).orElse(BigDecimal.ZERO)))
                .toList();
    }

    /** The methods on offer, so the form does not hardcode the list. */
    public List<MethodOption> methods() {
        return PaymentMethods.ALL.stream().map(m -> new MethodOption(m, PaymentMethods.label(m))).toList();
    }

    // ── rows ──────────────────────────────────────────────────────────────────

    /** One payment as a response, resolving its own lookups. For a caller holding a single row. */
    public PaymentResponse toResponse(Payment payment) {
        return toResponse(payment, lookups(List.of(payment)));
    }

    /** The lookups a page needs, fetched once rather than per row. */
    private record Lookups(Map<Long, Development> developments, Map<Long, UnitBooking> bookings) {}

    private Lookups lookups(List<Payment> rows) {
        if (rows.isEmpty()) return new Lookups(Map.of(), Map.of());
        Map<Long, Development> devs = developments.findAllById(
                        rows.stream().map(Payment::getDevelopmentId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Development::getId, Function.identity()));
        Map<Long, UnitBooking> bks = bookings.findAllById(
                        rows.stream().map(Payment::getBookingId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UnitBooking::getId, Function.identity()));
        return new Lookups(devs, bks);
    }

    private PaymentResponse toResponse(Payment p, Lookups lookups) {
        Development development = lookups.developments().get(p.getDevelopmentId());
        UnitBooking booking = lookups.bookings().get(p.getBookingId());
        String ownerKind = p.getInstitutionId() != null ? "INSTITUTION"
                : p.getTenantId() != null ? "TENANT" : "PLATFORM";
        String ownerName = development == null ? null
                : p.getInstitutionId() != null ? development.getInstitutionName()
                : p.getTenantId() != null ? development.getTenantName()
                : "Platform";
        return new PaymentResponse(
                HashIdUtil.encodeId(p.getId()), p.getReference(), p.getStatus(),
                p.isVoided() ? "Voided" : "Received",
                HashIdUtil.encodeId(p.getBookingId()), booking == null ? null : booking.getReference(),
                HashIdUtil.encodeId(p.getDevelopmentId()), p.getDevelopmentName(),
                HashIdUtil.encodeId(p.getPropertyId()), p.getUnitLabel(),
                p.getBuyerName(), p.getBuyerPhone(), ownerKind, ownerName,
                p.getPaidOn(), p.getAmount(), p.getCurrency(), p.getSource(),
                p.getMethod(), PaymentMethods.label(p.getMethod()), p.arrivedAs(),
                HashIdUtil.encodeId(p.getPaymentTypeId()), p.getPaymentTypeName(),
                p.getQuotedReference(), p.getExternalReference(), p.getPayerName(), p.getPayerPhone(),
                p.getBalanceBefore(), p.getBalanceAfter(), p.getNotes(),
                p.getVoidedAt(), p.getVoidedBy(), p.getVoidReason(),
                p.getCreatedAt(), p.getCreatedBy());
    }

    private PaymentDetail detailOf(Payment payment) {
        PaymentResponse row = toResponse(payment);
        UnitBooking booking = bookings.findById(payment.getBookingId()).orElse(null);
        Property home = booking == null ? null : units.findById(booking.getPropertyId()).orElse(null);
        BookingBalance balance = booking == null || home == null ? null
                : balanceOf(booking, access.developmentOf(home), home);
        return new PaymentDetail(row, balance);
    }

    private BookingBalance balanceOf(UnitBooking booking, Development development, Property unit) {
        Optional<BalanceRow> balance = balances.forBooking(booking.getId());
        LocalDate today = LocalDate.now();
        List<ScheduleLine> schedule = instalments.findCurrentPlan(booking.getId()).stream()
                .map(i -> new ScheduleLine(
                        i.getLabel() == null ? "Instalment " + i.getSequenceNo() : i.getLabel(),
                        i.getDueOn(), i.getAmount(), i.getDueOn().isBefore(today)))
                .toList();
        return new BookingBalance(
                HashIdUtil.encodeId(booking.getId()), booking.getReference(), booking.getState(),
                booking.getBuyerName(), booking.getBuyerPhone(),
                development == null ? null : HashIdUtil.encodeId(development.getId()),
                development == null ? unit.getTitle() : development.getName(),
                HashIdUtil.encodeId(booking.getPropertyId()),
                unit == null ? null : unit.getUnitLabel(),
                unit == null ? null : unit.getPayReference(),
                booking.getCurrency(), booking.getPriceAgreed(),
                balance.map(BalanceRow::scheduled).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::paid).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::balance).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::overdue).orElse(BigDecimal.ZERO),
                balance.map(BalanceRow::nextDueOn).orElse(null),
                schedule);
    }

    /** One payment, if the caller may see it. Through the same scope as the list. */
    private Optional<Payment> scoped(Long id) {
        if (id == null) return Optional.empty();
        Specification<Payment> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("id", id),
                scope.byDevelopment(AuthContext.require()));
        return payments.findOne(spec);
    }

    private static String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }
}
