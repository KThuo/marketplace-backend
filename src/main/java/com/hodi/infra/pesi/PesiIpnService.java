package com.hodi.infra.pesi;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.pesi.PesiIpnDtos.IpnPayload;
import com.hodi.modules.bookings.BookingPayment;
import com.hodi.modules.bookings.BookingPaymentRepository;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.developments.DevelopmentUnit;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Taking in a payment Pesi has told us about, and placing it if we safely can.
 *
 * <h2>Store first. Everything else is best-effort.</h2>
 *
 * <p>The one thing that must succeed is writing the statement. If it does, Pesi is told 0 and stops; the money
 * is on our books whether or not we worked out whose it is. If it does not, Pesi is told to retry — which is
 * the only situation where a retry helps, because a payload we could not store might store next time.
 *
 * <p>A retry never helps a payment we could not <em>place</em>: the reference will be just as wrong on the
 * second delivery, and each delivery is another chance to double-post. So an unplaceable payment is a stored
 * statement and a successful acknowledgement, not a refusal.
 *
 * <h2>Why a bare reference match is never enough</h2>
 *
 * <p>A unit's code is four characters from a 32-letter alphabet. A single mistyped letter produces another
 * well-formed code, and the chance it happens to be a live one is roughly units ÷ 1,048,576 — about one in two
 * hundred at five thousand units. Small, not nothing, and it is money.
 *
 * <p>So a reference match alone goes to the queue. To place a payment automatically, something else must agree:
 * the amount equals something outstanding on that unit, or the paying phone number is the buyer's. Anything
 * less is a person's decision, which is what the unmapped queue is for.
 *
 * <h2>What this method must not do</h2>
 *
 * <p>No outbound HTTP and no email. Thirty seconds is the whole budget, and a gateway or an SMTP server having
 * a bad minute would spend it — turning a payment we had already stored into one Pesi retries, against a
 * handler that has to be idempotent to survive it. Notifying anybody is a separate, later concern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PesiIpnService {

    private final PesiStatementRepository statements;
    private final PesiPaymentMethodRepository methods;
    private final UnitBookingRepository bookings;
    private final DevelopmentUnitRepository units;
    private final BookingPaymentRepository payments;
    private final ConfigurationService configs;
    private final ObjectMapper mapper;

    /** Pesi's timestamps are "yyyy-MM-dd HH:mm:ss", not ISO-8601. Parsed leniently; never fatal. */
    private static final DateTimeFormatter PESI_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * Records the notification, and places it when that can be done safely.
     *
     * <p>{@code REQUIRES_NEW} so the statement survives on its own. A failure while matching — a booking in a
     * state nobody anticipated, a constraint somewhere downstream — must not roll back the record of money
     * having arrived. Losing the match is recoverable by a person; losing the statement is not.
     *
     * @return the statement, whether mapped or not
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PesiStatement accept(IpnPayload payload, boolean trusted) {
        String refNo = trim(payload.refNo());

        /*
         * The fast path for a retry. The index behind it is the actual guarantee — two deliveries of the same
         * money can be in flight at once and both will pass this check — but answering here means the ordinary
         * retry costs one query rather than a failed insert.
         */
        Optional<PesiStatement> seen = refNo == null ? Optional.empty() : statements.findByRefNo(refNo);
        if (seen.isPresent()) {
            log.info("Pesi notification {} already recorded as {}", refNo, seen.get().getOurReference());
            return seen.get();
        }

        PesiPaymentMethod method = payload.accountIdentifier() == null ? null
                : methods.findByAccountNumber(trim(payload.accountIdentifier())).orElse(null);

        PesiStatement statement = PesiStatement.builder()
                .refNo(refNo == null ? "UNKNOWN-" + RrnGenerator.generate("RX") : refNo)
                .traceId(trim(payload.traceId()))
                .ourReference(RrnGenerator.generate("PS"))
                .transType(trim(payload.transType()) == null ? "UNKNOWN" : trim(payload.transType()))
                .paymentMethodId(method == null ? null : method.getId())
                .accountIdentifier(trim(payload.accountIdentifier()))
                .reference(trim(payload.reference()))
                .amount(parseAmount(payload.amount()))
                .currency(trim(payload.currency()) == null ? "KES" : trim(payload.currency()))
                .phoneNo(trim(payload.phoneNo()))
                .customerName(trim(payload.customerName()))
                .paidAt(parseTimestamp(payload.timestamp()))
                .rawPayload(toJson(payload))
                // Whose money it is, from the till it landed in. Null when we do not recognise the account,
                // which is itself a reason it cannot be placed.
                .tenantId(method == null ? null : method.getTenantId())
                .institutionId(method == null ? null : method.getInstitutionId())
                .state(AppConstant.STATEMENT_UNMAPPED)
                .createdBy(AppConstant.USERNAME_SYSTEM)
                .updatedBy(AppConstant.USERNAME_SYSTEM)
                .build();

        PesiStatement stored;
        try {
            stored = statements.saveAndFlush(statement);
        } catch (DataIntegrityViolationException e) {
            /*
             * The race the check above cannot win: two deliveries of the same refNo at once. The index refused
             * the second, and the first one's row is the answer — so this is a success, not a failure.
             */
            log.info("Concurrent delivery of Pesi notification {}; keeping the first", refNo);
            return statements.findByRefNo(refNo).orElseThrow(() -> e);
        }

        // Matching is deliberately after the flush, so nothing below can lose the row above.
        tryToPlace(stored, method, trusted);
        return stored;
    }

    /**
     * Attaches the statement to a booking, when it is safe to do so without a person.
     *
     * <p>Every reason not to is written onto the row in words. "Unmatched" with no explanation is a queue
     * nobody can work through, and the reason is usually the whole answer — a reference nobody recognises, an
     * amount that does not correspond to anything owed.
     */
    private void tryToPlace(PesiStatement statement, PesiPaymentMethod method, boolean trusted) {
        if (!trusted) {
            /*
             * No shared secret configured, so this notification is unauthenticated.
             *
             * It is still stored — refusing would make Pesi retry and eventually give up, losing real money —
             * but nothing is credited automatically. A forged notification that guessed a four-character code
             * and an amount would otherwise move a buyer's balance.
             */
            unplaced(statement, "Notifications are not authenticated yet, so every payment is placed by "
                    + "hand. Set the Pesi notification secret to allow automatic matching.");
            return;
        }
        if (method == null) {
            unplaced(statement, "The account " + statement.getAccountIdentifier()
                    + " is not one of ours, or has not been registered here yet.");
            return;
        }
        if (statement.getReference() == null || statement.getReference().isBlank()) {
            unplaced(statement, "The payer quoted no reference, so there is nothing to match on.");
            return;
        }

        Optional<DevelopmentUnit> unit = units.findByPayReference(payCode(statement.getReference()));
        if (unit.isEmpty()) {
            unplaced(statement, "No unit has the code \"" + payCode(statement.getReference())
                    + "\". The payer may have mistyped it.");
            return;
        }

        Optional<UnitBooking> booking = bookings.findLiveForUnit(unit.get().getId());
        if (booking.isEmpty()) {
            unplaced(statement, "Unit " + unit.get().getUnitLabel()
                    + " has no live booking, so there is nothing to credit.");
            return;
        }

        UnitBooking target = booking.get();
        if (!corroborated(statement, target)) {
            /*
             * The reference matched and nothing else did.
             *
             * Four characters carry no redundancy, so one mistyped letter produces another well-formed code —
             * and at five thousand units the chance it is a live one is about one in two hundred. Too high to
             * credit somebody's balance on that alone.
             */
            unplaced(statement, "The code matches unit " + unit.get().getUnitLabel()
                    + ", but neither the amount nor the phone number does. Check before crediting "
                    + target.getReference() + ".");
            return;
        }

        BookingPayment payment = payments.save(BookingPayment.builder()
                .reference(RrnGenerator.generate("PY"))
                .bookingId(target.getId())
                .tenantId(target.getTenantId())
                .institutionId(target.getInstitutionId())
                .paidOn(statement.getPaidAt() == null ? LocalDate.now()
                        : statement.getPaidAt().toLocalDate())
                .amount(statement.getAmount())
                .currency(statement.getCurrency())
                .source(AppConstant.PAY_GATEWAY)
                .method(AppConstant.PAY_MOBILE_MONEY)
                .quotedReference(statement.getReference())
                .externalReference(statement.getRefNo())
                .payerName(statement.getCustomerName())
                .payerPhone(statement.getPhoneNo())
                .createdBy(AppConstant.USERNAME_SYSTEM)
                .updatedBy(AppConstant.USERNAME_SYSTEM)
                .build());

        statement.setState(AppConstant.STATEMENT_MAPPED);
        statement.setMappedPaymentId(payment.getId());
        statement.setMappedBookingId(target.getId());
        statement.setMappedAt(OffsetDateTime.now());
        statement.setMappedBy(AppConstant.USERNAME_SYSTEM);
        statement.setUnmappedReason(null);
        statements.save(statement);

        log.info("Pesi notification {} placed on booking {} as {}", statement.getRefNo(),
                target.getReference(), payment.getReference());
    }

    /**
     * Whether something other than the reference agrees that this payment belongs to this booking.
     *
     * <p>Either the amount corresponds to money actually owed, or the paying number is the buyer's. One
     * mistyped character is enough to hit another live code; it is not enough to also match an amount or a
     * phone number.
     */
    private boolean corroborated(PesiStatement statement, UnitBooking booking) {
        if (samePhone(statement.getPhoneNo(), booking.getBuyerPhone())) return true;

        // The deposit, or the whole price. Deliberately not "any amount at all" — a round figure that happens
        // to be smaller than the balance corroborates nothing.
        BigDecimal amount = statement.getAmount();
        if (amount == null) return false;
        return matches(amount, booking.getDepositDue()) || matches(amount, booking.getPriceAgreed());
    }

    private boolean matches(BigDecimal amount, BigDecimal expected) {
        return expected != null && amount.compareTo(expected) == 0;
    }

    /**
     * Whether two phone numbers are the same one written differently.
     *
     * <p>Compared on the last nine digits. Pesi sends {@code 254712345678}; a buyer's record may hold
     * {@code +254 712 345 678} or {@code 0712345678}, and all three are the same phone. Nine digits is the
     * subscriber part of a Kenyan number — enough to identify it, short enough to survive every prefix.
     */
    private boolean samePhone(String a, String b) {
        String left = digitsTail(a);
        String right = digitsTail(b);
        return left != null && left.equals(right);
    }

    private String digitsTail(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.length() < 9 ? null : digits.substring(digits.length() - 9);
    }

    /**
     * The four-character code out of whatever the payer typed.
     *
     * <p>People add spaces, prefixes and their own name. The code is uppercase and four characters, so the
     * last four alphanumerics are taken — which is also the pattern the guide suggests, and it survives
     * "UNIT A7K2" and "a7k2" alike.
     */
    private String payCode(String reference) {
        String cleaned = reference.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return cleaned.length() <= 4 ? cleaned : cleaned.substring(cleaned.length() - 4);
    }

    private void unplaced(PesiStatement statement, String reason) {
        statement.setState(AppConstant.STATEMENT_UNMAPPED);
        statement.setUnmappedReason(reason);
        statements.save(statement);
        log.info("Pesi notification {} stored unmapped: {}", statement.getRefNo(), reason);
    }

    /** Whether the caller proved it is Pesi. Blank secret means no, and no means nothing is auto-placed. */
    public boolean isTrusted(String signature) {
        String expected = configs.getString(ConfigKey.PESI_IPN_SECRET);
        if (expected == null || expected.isBlank()) return false;
        // Constant-time: a timing-comparable equals on a shared secret leaks it a byte at a time.
        return signature != null
                && java.security.MessageDigest.isEqual(
                        signature.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The amount, which arrives as a string.
     *
     * <p>Zero when it cannot be parsed rather than an exception — but the table forbids a zero amount, so an
     * unparseable amount surfaces as a failed store and a retry, which is the correct outcome: we genuinely
     * did not understand what arrived.
     */
    private BigDecimal parseAmount(String amount) {
        try {
            return new BigDecimal(amount == null ? "0" : amount.trim());
        } catch (NumberFormatException e) {
            log.warn("Pesi notification carried an unreadable amount: {}", amount);
            return BigDecimal.ZERO;
        }
    }

    /** Never fatal: a payment with an unreadable timestamp is still a payment. */
    private OffsetDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return OffsetDateTime.now();
        try {
            return LocalDateTime.parse(timestamp.trim(), PESI_TIME).atOffset(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            log.debug("Pesi timestamp not in the documented format: {}", timestamp);
            return OffsetDateTime.now();
        }
    }

    private String toJson(IpnPayload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            // The audit copy is worth having and not worth failing for.
            log.warn("Could not serialise a Pesi payload for audit: {}", e.getMessage());
            return null;
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
