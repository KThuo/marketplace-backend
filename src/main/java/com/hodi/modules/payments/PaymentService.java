package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.BookingBalanceReader;
import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.payments.PaymentDtos.PaymentResponse;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentDtos.VoidRequest;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Receiving money against a booking, and voiding it.
 *
 * <h2>One writer</h2>
 *
 * <p>This is the only place a payment row is created — the receive form, the booking drawer and the Co-op
 * notification all come through {@link #write}. So the cached names, the balance snapshots, the channel
 * stamp and the audit row happen once, whatever route the money took. The receipt used to be sent only
 * for gateway credits; the buyer who paid in person, the one most likely to want written proof, got none.
 *
 * <h2>Balances come from the view</h2>
 *
 * <p>Never computed here. {@code balance_before} and {@code balance_after} are read from
 * {@code v_booking_balances} either side of the insert, so the receipt shows the same arithmetic the
 * booking screen does. Two implementations of a balance is two balances.
 *
 * <h2>Cash and cheque are the only things a person may write down</h2>
 *
 * <p>Everything else — a phone prompt, a transfer, a paybill — arrives as a notification from the bank, and
 * the notification is the evidence. So {@link #receive} takes cash and cheques only, and every other payment
 * is written from the statement it arrived on and carries that statement's id. {@link #write} refuses the
 * combination the database also refuses ({@code ck_payment_statement}), so a payment with nothing behind it
 * cannot be produced from any path, including one added later.
 *
 * <h2>Voided, never edited</h2>
 *
 * <p>A wrong payment is voided with a reason and the row stays. The balance view stops counting it; the
 * receipt keeps saying what the buyer was told, with the reversal written beside it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private final PaymentRepository payments;
    private final UnitBookingRepository bookings;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitRepository units;
    private final DevelopmentVisibility visibility;
    private final com.hodi.modules.bookings.BookingAccess access;
    private final BookingBalanceReader balances;
    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final PaymentQueryService queries;
    private final CoopStatementRepository statements;
    private final AuditService audit;
    private final NotifyClient notify;

    // ── receiving ─────────────────────────────────────────────────────────────

    /**
     * Records cash or a cheque received by hand.
     *
     * <p>Only those two. A transfer or a phone payment is money the bank tells us about, and it is placed
     * from that notification — by the matcher, or by a person finding it by its bank reference — never
     * keyed from memory. Keying it would produce a payment with no evidence behind it, which is what this
     * refusal exists to prevent.
     */
    @Transactional
    public PaymentResponse receive(ReceiveRequest request) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = requireBooking(request.bookingId());
        Property home = homeOf(booking, request.bookingId(), caller);
        access.assertMayWrite(home, caller);
        Development development = access.developmentOf(home);
        assertOpen(booking);

        LocalDate paidOn = request.paidOn() == null ? LocalDate.now() : request.paidOn();
        if (paidOn.isAfter(LocalDate.now())) {
            throw new HodiException("Money cannot have arrived in the future.", HttpStatus.BAD_REQUEST);
        }

        // The channel decides the method where one was named; otherwise the method is taken as given.
        PaymentType type = null;
        String method;
        Long accountId = HashIdUtil.decodeId(request.paymentAccountId());
        if (accountId != null) {
            PaymentAccount account = accounts.findById(accountId)
                    .filter(PaymentAccount::isLive)
                    .orElseThrow(() -> new HodiException("That payment account is not available.",
                            HttpStatus.BAD_REQUEST));
            // A unit's money lands in the project owner's accounts; a house's in its seller's. An account
            // pinned to one development does not collect for a house, whose development is nothing.
            Long ownerTenant = development == null ? home.getTenantId() : development.getTenantId();
            Long ownerInstitution = development == null ? home.getInstitutionId() : development.getInstitutionId();
            if (!account.belongsTo(ownerTenant, ownerInstitution)
                    || !account.reaches(development == null ? null : development.getId())) {
                throw new HodiException("That account does not collect for "
                        + (development == null ? home.getTitle() : development.getName()) + ".",
                        HttpStatus.BAD_REQUEST);
            }
            type = types.findById(account.getPaymentTypeId())
                    .orElseThrow(() -> new HodiException("That payment method no longer exists.",
                            HttpStatus.CONFLICT));
            if (!type.selectable() || !type.channelCategory().isReceivable()) {
                throw new HodiException(type.getName() + " sends money out; it is not a way to receive it.",
                        HttpStatus.BAD_REQUEST);
            }
            if (!type.isManual()) {
                throw new HodiException(arrivesAsANotification(type.getName()), HttpStatus.BAD_REQUEST);
            }
            method = type.getMethod();
        } else {
            method = manualMethod(request.method());
        }

        Payment saved = write(booking, development, request.amount(), paidOn, AppConstant.PAY_MANUAL,
                method, type, request.quotedReference(), request.externalReference(),
                orElse(request.payerName(), booking.getBuyerName()), request.payerPhone(),
                request.notes(), null, AuthContext.username());

        audit.record(AppConstant.AUDIT_PAYMENT_RECEIVED, "Payment", saved.getId(), null, snapshot(saved));
        receipt(saved, booking);
        return queries.toResponse(saved);
    }

    /**
     * Records a credit Co-op told us about, once the notification handler has decided it is safe to place.
     *
     * <p>No permission checks: the gateway has no login, and the money moved at the bank before we heard
     * about it. No receipt SMS either — the handler has thirty seconds and no outbound calls in it, which is
     * the one deliberate exception to "every payment sends a receipt".
     */
    @Transactional
    public Payment recordFromGateway(UnitBooking booking, CoopStatement statement, PaymentAccount account) {
        Development development = booking.getDevelopmentId() == null ? null
                : developments.findById(booking.getDevelopmentId())
                .orElseThrow(() -> new HodiException("That development no longer exists.", HttpStatus.CONFLICT));
        PaymentType type = account == null ? null
                : types.findById(account.getPaymentTypeId()).orElse(null);
        String method = type == null ? AppConstant.PAY_MOBILE_MONEY : type.getMethod();
        LocalDate paidOn = statement.getPaidAt() == null ? LocalDate.now()
                : statement.getPaidAt().toLocalDate();

        Payment saved = write(booking, development, statement.getAmount(), paidOn, AppConstant.PAY_GATEWAY,
                method, type, statement.getReference(), statement.getRefNo(),
                orElse(statement.getCustomerName(), booking.getBuyerName()), statement.getPhoneNo(),
                null, statement.getId(), AppConstant.USERNAME_SYSTEM);
        audit.record(AppConstant.AUDIT_PAYMENT_RECEIVED, "Payment", saved.getId(), null, snapshot(saved));
        return saved;
    }

    /**
     * Records the money for an intent Co-op has confirmed, from the statement written for that confirmation.
     *
     * <p>Used when the status query settles a payment the callback never reported. The alternative —
     * crediting only on a callback — loses the money whose callback was lost, which is the whole reason
     * the status query exists.
     *
     * <p>The statement is the enquiry's answer written down as a bank statement row, so this payment has
     * the same evidence behind it as one placed from a notification. A notification arriving later for
     * the same money is linked to this payment rather than creating a second one.
     *
     * <p>Deduplication is the caller's: {@code CoopIntentSettlement} writes this once per intent and
     * never again.
     */
    @Transactional
    public Payment recordFromIntent(UnitBooking booking, PaymentIntent intent, PaymentAccount account,
                                    CoopStatement statement) {
        Development development = booking.getDevelopmentId() == null ? null
                : developments.findById(booking.getDevelopmentId())
                .orElseThrow(() -> new HodiException("That development no longer exists.", HttpStatus.CONFLICT));
        PaymentType type = types.findById(intent.getPaymentTypeId()).orElse(null);
        String method = type == null ? AppConstant.PAY_MOBILE_MONEY : type.getMethod();
        LocalDate paidOn = statement.getPaidAt() == null ? LocalDate.now()
                : statement.getPaidAt().toLocalDate();

        Payment saved = write(booking, development, intent.getAmount(), paidOn,
                AppConstant.PAY_GATEWAY, method, type, intent.getReference(),
                intent.getReceipt() == null ? intent.getBankReference() : intent.getReceipt(),
                booking.getBuyerName(), intent.getPhoneNo(), null, statement.getId(),
                AppConstant.USERNAME_SYSTEM);
        audit.record(AppConstant.AUDIT_PAYMENT_RECEIVED, "Payment", saved.getId(), null, snapshot(saved));
        return saved;
    }

    // ── voiding ───────────────────────────────────────────────────────────────

    /**
     * Voids a payment, with a reason.
     *
     * <p>Never an edit and never a delete. A balance a buyer has already been told changes only by an entry
     * that says why it changed, and the row stays so the receipt can still be read.
     *
     * <p>A statement credited as this payment goes back to the unused queue, carrying the reason. The bank
     * still says the money arrived; what has been withdrawn is only our decision about whose it was, and
     * somebody has to make that decision again.
     */
    @Transactional
    public PaymentResponse voidPayment(String hashId, VoidRequest request) {
        UserPrincipal caller = AuthContext.require();
        Payment payment = payments.findById(HashIdUtil.decodeId(hashId))
                .filter(p -> p.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", hashId));
        UnitBooking booking = bookings.findById(payment.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", hashId));
        Property home = homeOf(booking, hashId, caller);
        access.assertMayWrite(home, caller);
        if (payment.isVoided()) {
            throw new HodiException("Payment " + payment.getReference() + " is already voided.",
                    HttpStatus.CONFLICT);
        }

        String before = snapshot(payment);
        payment.voidWith(AuthContext.username(), request.reason().trim());
        // Flushed, so the balance view — read through JDBC by whoever renders the response — sees the void.
        Payment saved = payments.saveAndFlush(payment);
        audit.record(AppConstant.AUDIT_PAYMENT_VOIDED, "Payment", saved.getId(), before, snapshot(saved));

        for (CoopStatement statement : statements.findByMappedPaymentId(saved.getId())) {
            statement.release("The payment it was applied to, " + saved.getReference() + ", was voided: "
                    + request.reason().trim(), AuthContext.username());
            statements.save(statement);
            log.info("Statement {} released back to the queue by the void of {}", statement.getRefNo(),
                    saved.getReference());
        }

        log.info("Payment {} voided by {}: {}", saved.getReference(), AuthContext.username(),
                request.reason().trim());
        return queries.toResponse(saved);
    }

    // ── the one place a payment is written ────────────────────────────────────

    private Payment write(UnitBooking booking, Development development, BigDecimal amount, LocalDate paidOn,
                          String source, String method, PaymentType type, String quotedReference,
                          String externalReference, String payerName, String payerPhone, String notes,
                          Long statementId, String actor) {
        if (!PaymentMethods.isManual(method) && statementId == null) {
            // The database refuses this too (ck_payment_statement). Refusing here says why, in words, and
            // before a balance has been read for a row that will never exist.
            throw new IllegalStateException("A " + PaymentMethods.label(method).toLowerCase(Locale.ROOT)
                    + " payment must be written from the bank statement it arrived on.");
        }
        Property home = units.findById(booking.getPropertyId()).orElse(null);
        // Before anything is applied. The receipt shows the subtraction, so both ends are read when true.
        BigDecimal before = balance(booking.getId());

        Payment payment = Payment.builder()
                .reference(RrnGenerator.generate("PY"))
                .bookingId(booking.getId())
                .developmentId(development == null ? null : development.getId())
                .propertyId(booking.getPropertyId())
                .tenantId(booking.getTenantId())
                .institutionId(booking.getInstitutionId())
                // A house has no project: its own title stands where the project's name would.
                .developmentName(development == null ? (home == null ? null : home.getTitle()) : development.getName())
                .unitLabel(home == null ? null : home.getUnitLabel())
                .buyerName(booking.getBuyerName())
                .buyerPhone(booking.getBuyerPhone())
                .paidOn(paidOn)
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .currency(booking.getCurrency() == null ? "KES" : booking.getCurrency())
                .source(source)
                .method(method)
                .paymentTypeId(type == null ? null : type.getId())
                .paymentTypeName(type == null ? null : type.getName())
                // Sixteen characters on the receipt; a statement's reference holds sixty-four. The payer's
                // whole sentence overflowing this column used to roll back the notification that carried it.
                .quotedReference(clip(blankToNull(quotedReference), 16))
                .externalReference(blankToNull(externalReference))
                .payerName(blankToNull(payerName))
                .payerPhone(blankToNull(payerPhone))
                .notes(blankToNull(notes))
                .statementId(statementId)
                .balanceBefore(before)
                .createdBy(actor)
                .updatedBy(actor)
                .build();

        // Flushed, so the view sees the row when the balance after is read.
        Payment saved = payments.saveAndFlush(payment);
        saved.setBalanceAfter(balance(booking.getId()));
        saved = payments.save(saved);

        log.info("Payment {} of {} recorded against booking {} via {}", saved.getReference(),
                saved.getAmount(), booking.getReference(), saved.arrivedAs());
        return saved;
    }

    private BigDecimal balance(Long bookingId) {
        return balances.forBooking(bookingId).map(BalanceRow::balance).orElse(BigDecimal.ZERO);
    }

    /**
     * Tells the buyer their money arrived.
     *
     * <p>To the buyer's own number, not the payer's: the payer may be a relative or an employer, and a receipt
     * is a statement about the buyer's account. Nothing here throws — the money is the fact, and telling
     * somebody can be retried.
     */
    private void receipt(Payment payment, UnitBooking booking) {
        if (booking.getBuyerPhone() == null || booking.getBuyerPhone().isBlank()) return;
        try {
            String currency = payment.getCurrency();
            String balance = payment.getBalanceAfter() != null && payment.getBalanceAfter().signum() > 0
                    ? " Balance now " + currency + " " + MONEY.format(payment.getBalanceAfter()) + "."
                    : " Your account is settled. Thank you.";
            notify.sendSms(booking.getBuyerPhone(),
                    currency + " " + MONEY.format(payment.getAmount()) + " received for "
                            + (payment.getUnitLabel() == null ? "your unit" : payment.getUnitLabel())
                            + " at " + payment.getDevelopmentName() + "." + balance
                            + " Receipt " + payment.getReference() + ".",
                    booking.getBuyerName());
        } catch (RuntimeException e) {
            log.warn("Could not send the receipt for {}: {}", payment.getReference(), e.getMessage());
        }
    }

    // ── rules ─────────────────────────────────────────────────────────────────

    private void assertOpen(UnitBooking booking) {
        if (AppConstant.BOOKING_CANCELLED.equals(booking.getState())
                || AppConstant.BOOKING_LAPSED.equals(booking.getState())) {
            // Not refused because money cannot arrive against a dead booking — it can, and often does, which
            // is exactly why the message says what to do rather than just saying no.
            throw new HodiException("That booking is " + booking.getState().toLowerCase()
                    + ". Re-book the unit before recording money against it.", HttpStatus.CONFLICT);
        }
    }

    private UnitBooking requireBooking(String hashId) {
        return bookings.findById(HashIdUtil.decodeId(hashId))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", hashId));
    }

    /** The home the booking is on, or not found when the caller may not see it. */
    private Property homeOf(UnitBooking booking, String bookingHash, UserPrincipal caller) {
        Property home = units.findById(booking.getPropertyId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingHash));
        if (!access.mayRead(home, caller)) throw new ResourceNotFoundException("Booking", bookingHash);
        return home;
    }

    /** Cash or cheque, said plainly; anything else is told where the payment actually is. */
    private static String manualMethod(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new HodiException("Say whether this was cash or a cheque.", HttpStatus.BAD_REQUEST);
        }
        String value = requested.trim().toUpperCase(Locale.ROOT);
        if (PaymentMethods.isManual(value)) return value;
        if (PaymentMethods.isKnown(value)) {
            throw new HodiException(arrivesAsANotification(PaymentMethods.label(value)), HttpStatus.BAD_REQUEST);
        }
        throw new HodiException("That is not a way money arrives here.", HttpStatus.BAD_REQUEST);
    }

    private static String arrivesAsANotification(String what) {
        return what + " payments arrive as a notification from the bank, so this one cannot be keyed by "
                + "hand. Find it by its bank reference and attach it to the booking instead.";
    }

    private static String snapshot(Payment p) {
        return p.getReference() + " " + p.getCurrency() + " " + p.getAmount().toPlainString()
                + " for " + p.getUnitLabel() + " via " + p.arrivedAs() + " status=" + p.getStatus()
                + (p.getVoidReason() == null ? "" : " — " + p.getVoidReason());
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value, int width) {
        return value == null || value.length() <= width ? value : value.substring(0, width);
    }
}
