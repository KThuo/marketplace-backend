package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.bookings.BookingAccess;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.payments.StatementDtos.SlipResult;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.TakeSlipRequest;
import com.hodi.modules.properties.Property;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * "The money arrived and nothing happened": a person types the reference off the slip, and the platform
 * finds what the bank told us and applies it.
 *
 * <h2>Unused only, and the database only</h2>
 *
 * <p>The client's definition. Only an unplaced credit is offered for attachment, and the answer comes
 * from what has already reached us — there is no call to the bank. A reference we have not seen is a
 * notification that has not arrived yet, and the answer says so rather than guessing.
 *
 * <h2>But "not found" is never the whole answer</h2>
 *
 * <p>A slip that paid last week is <em>found</em> — as a used credit — and the message says which booking
 * and which receipt it became. A clerk told only "not found" for a payment that already applied will key
 * it by hand a second time, which is the double credit this whole step exists to prevent. The row itself
 * is never returned outside the unused set; only the sentence about it.
 *
 * <h2>Who may</h2>
 *
 * <p>Platform staff who can record or reconcile money, always. A buyer, only against their own booking and
 * only while {@code payments.buyer.slip.validation} says so — a lookup by reference is also a way of
 * probing, and the institution decides whether the convenience is worth it. A buyer is told less: whose
 * booking a used credit went to is not theirs to learn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlipValidationService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final CoopStatementRepository statements;
    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final UnitBookingRepository bookings;
    private final BookingAccess access;
    private final StatementService reconciliation;
    private final ConfigurationService configs;

    /** What the reference the payer quoted turns out to be, for this booking. */
    @Transactional(readOnly = true)
    public SlipResult validate(String reference, String bookingHash) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = booking(bookingHash, caller);
        Property home = access.propertyOf(booking);
        assertMaySlip(caller, booking);

        String cleaned = clean(reference);
        if (cleaned.length() < 6) return SlipResult.no("A bank reference is at least six characters.");

        List<CoopStatement> found = statements.findAnyByReference(cleaned);
        Development development = access.developmentOf(home);
        Optional<CoopStatement> usable = found.stream()
                .filter(CoopStatement::isUnmapped)
                .filter(s -> collectsFor(s, home, development))
                .findFirst();
        if (usable.isPresent()) return yes(usable.get());
        return SlipResult.no(explain(found, booking, caller));
    }

    /**
     * Confirms the reference and applies the credit, in one transaction under the row's lock.
     *
     * <p>Validate and take are one step here rather than two calls, because a notification can land
     * between the person reading the figure and pressing the button. The lock, and the re-check after it,
     * are what stop the second of them crediting twice.
     */
    @Transactional
    public StatementResponse take(TakeSlipRequest request) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = booking(request.bookingId(), caller);
        Property home = access.propertyOf(booking);
        assertMaySlip(caller, booking);

        String cleaned = clean(request.reference());
        if (cleaned.length() < 6) {
            throw new HodiException("A bank reference is at least six characters.", HttpStatus.BAD_REQUEST);
        }
        List<CoopStatement> found = statements.findAnyByReference(cleaned);
        Development development = access.developmentOf(home);
        CoopStatement candidate = found.stream()
                .filter(CoopStatement::isUnmapped)
                .filter(s -> collectsFor(s, home, development))
                .findFirst()
                .orElseThrow(() -> new HodiException(explain(found, booking, caller), HttpStatus.CONFLICT));

        // Held, then read again: the state may have changed since the list above was built.
        CoopStatement locked = statements.lockById(candidate.getId())
                .orElseThrow(() -> new HodiException(explain(found, booking, caller), HttpStatus.CONFLICT));
        if (!locked.isUnmapped()) {
            throw new HodiException(explain(List.of(locked), booking, caller), HttpStatus.CONFLICT);
        }

        CoopStatement applied = reconciliation.apply(locked, booking, home, AuthContext.username());
        log.info("Slip {} confirmed and applied to booking {} by {}", applied.getRefNo(),
                booking.getReference(), AuthContext.username());
        return reconciliation.toResponse(applied, reconciliation.lookups(List.of(applied)));
    }

    // ── who may ───────────────────────────────────────────────────────────────

    /**
     * Whether this caller may look up and apply a slip against this booking.
     *
     * <p>Platform staff with either money permission, always. The buyer, for their own booking and only
     * while the setting allows. Nobody else: a seller's staff read receipts like everybody else.
     */
    public boolean maySlip(UserPrincipal caller, UnitBooking booking) {
        if (caller.isPlatformStaff()) {
            return holds(caller, "STATEMENTS_RECONCILE") || holds(caller, "PAYMENTS_RECEIVE");
        }
        if (caller.isBuyer()) {
            return buyersMay()
                    && booking.getBuyerUserId() != null
                    && Objects.equals(booking.getBuyerUserId(), caller.getUserId());
        }
        return false;
    }

    /** Whether a buyer is offered slip validation at all. The institution's setting. */
    public boolean buyersMay() {
        return configs.getBoolean(ConfigKey.PAYMENTS_BUYER_SLIP_VALIDATION);
    }

    private void assertMaySlip(UserPrincipal caller, UnitBooking booking) {
        if (maySlip(caller, booking)) return;
        throw new HodiException(caller.isBuyer() && !buyersMay()
                ? "Validating a bank slip yourself is not switched on. Send the reference to the sales "
                        + "office and they will apply it for you."
                : "You cannot apply a bank slip to this booking.", HttpStatus.FORBIDDEN);
    }

    private static boolean holds(UserPrincipal caller, String permission) {
        return caller.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    // ── the answer ────────────────────────────────────────────────────────────

    private SlipResult yes(CoopStatement s) {
        PaymentAccount account = s.getPaymentAccountId() == null ? null
                : accounts.findById(s.getPaymentAccountId()).orElse(null);
        PaymentType type = account == null ? null : types.findById(account.getPaymentTypeId()).orElse(null);
        return new SlipResult(true,
                s.getCurrency() + " " + s.getAmount().toPlainString() + " confirmed"
                        + (s.getCustomerName() == null ? "" : ", paid by " + s.getCustomerName())
                        + (type == null ? "" : " via " + type.getName()) + ".",
                HashIdUtil.encodeId(s.getId()), s.getRefNo(), s.getAmount(), s.getCurrency(),
                s.getPaidAt(), s.getCreatedAt(), s.getCustomerName(), s.getPhoneNo(), s.getReference(),
                type == null ? null : HashIdUtil.encodeId(type.getId()),
                type == null ? null : type.getName(),
                account == null ? null : account.getCategory(),
                account == null ? s.getAccountIdentifier() : account.getAccountNo(),
                account == null ? null : account.getAccountName());
    }

    /**
     * Why there is nothing to apply, in the words the caller is entitled to.
     *
     * <p>Staff are told what the row is — used, and on which booking; set aside, and when; found but in an
     * account that does not collect here. A buyer learns only that the reference is not available to them,
     * except when it already paid <em>their</em> booking, which they are entitled to know.
     */
    private String explain(List<CoopStatement> found, UnitBooking booking, UserPrincipal caller) {
        if (found.isEmpty()) {
            return "No payment with that reference has reached us yet. A bank notification can take a few "
                    + "minutes; check the reference and try again shortly.";
        }
        boolean staff = caller.isPlatformStaff();
        Optional<CoopStatement> used = found.stream().filter(CoopStatement::isMapped).findFirst();
        if (used.isPresent()) {
            CoopStatement s = used.get();
            if (Objects.equals(s.getMappedBookingId(), booking.getId())) {
                return "That payment has already been applied to this booking"
                        + receiptSuffix(s) + ".";
            }
            return staff
                    ? "That payment has already been applied to booking "
                            + bookings.findById(s.getMappedBookingId()).map(UnitBooking::getReference).orElse("—")
                            + receiptSuffix(s) + "."
                    : "That reference has already been used.";
        }
        Optional<CoopStatement> aside = found.stream().filter(CoopStatement::isIgnored).findFirst();
        if (aside.isPresent()) {
            CoopStatement s = aside.get();
            return staff
                    ? "That payment was set aside on " + DAY.format(s.getUpdatedAt())
                            + (s.getUnmappedReason() == null ? "" : ": " + s.getUnmappedReason())
                            + " An administrator can restore it from the statements screen."
                    : "No payment with that reference is available for this booking.";
        }
        // Unused, but in an account that does not collect for this listing.
        CoopStatement s = found.get(0);
        PaymentAccount account = s.getPaymentAccountId() == null ? null
                : accounts.findById(s.getPaymentAccountId()).orElse(null);
        return staff
                ? "That payment was found, but it landed in "
                        + (account == null ? "an account nobody has registered" : StatementService.accountLabel(account))
                        + ", which does not collect for this booking's listing."
                : "No payment with that reference is available for this booking.";
    }

    private String receiptSuffix(CoopStatement s) {
        return s.getMappedPaymentId() == null ? "" : " as receipt "
                + reconciliation.lookups(List.of(s)).payments().values().stream()
                        .findFirst().map(Payment::getReference).orElse("—");
    }

    private boolean collectsFor(CoopStatement s, Property home, Development development) {
        if (s.getPaymentAccountId() == null) return true;
        PaymentAccount account = accounts.findById(s.getPaymentAccountId()).orElse(null);
        return account == null || reconciliation.collectsFor(account, home, development);
    }

    private UnitBooking booking(String hash, UserPrincipal caller) {
        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(hash))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", hash));
        // The buyer reads their own booking by identity; staff by the ordinary listing rule.
        if (caller.isBuyer()) {
            if (!Objects.equals(booking.getBuyerUserId(), caller.getUserId())) {
                throw new ResourceNotFoundException("Booking", hash);
            }
            return booking;
        }
        if (!access.mayRead(access.propertyOf(booking), caller)) {
            throw new ResourceNotFoundException("Booking", hash);
        }
        return booking;
    }

    /** Uppercased and trimmed. The bank's reference is matched whole, not by its alphanumerics only. */
    static String clean(String reference) {
        return reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
    }
}
