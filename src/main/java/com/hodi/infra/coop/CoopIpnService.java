package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopIpnDtos.IpnPayload;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.properties.Property;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.payments.Payment;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentIntent;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Taking in a payment Co-op has told us about, and placing it if we safely can.
 *
 * <h2>Store first. Everything else is best-effort.</h2>
 *
 * <p>The one thing that must succeed is writing the statement. If it does, Co-op is told 0 and stops; the money
 * is on our books whether or not we worked out whose it is. If it does not, Co-op is told to retry — which is
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
 * a bad minute would spend it — turning a payment we had already stored into one Co-op retries, against a
 * handler that has to be idempotent to survive it. Notifying anybody is a separate, later concern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoopIpnService {

    private final CoopStatementRepository statements;
    private final PaymentAccountRepository accounts;
    private final UnitBookingRepository bookings;
    private final DevelopmentUnitRepository units;
    /** The one writer of a payment row, so a gateway credit is stamped exactly as a hand-keyed one is. */
    private final PaymentService payments;
    private final com.hodi.modules.payments.PaymentIntentRepository intents;
    private final CoopIntentSettlement settlement;
    private final ConfigurationService configs;
    private final ObjectMapper mapper;

    /** Co-op's timestamps are "yyyy-MM-dd HH:mm:ss", not ISO-8601. Parsed leniently; never fatal. */
    private static final DateTimeFormatter COOP_TIME =
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
    public CoopStatement accept(IpnPayload payload, boolean trusted) {
        String refNo = trim(payload.refNo());

        /*
         * The fast path for a retry. The index behind it is the actual guarantee — two deliveries of the same
         * money can be in flight at once and both will pass this check — but answering here means the ordinary
         * retry costs one query rather than a failed insert.
         */
        Optional<CoopStatement> seen = refNo == null ? Optional.empty() : statements.findByRefNo(refNo);
        if (seen.isPresent()) {
            log.info("Co-op notification {} already recorded as {}", refNo, seen.get().getOurReference());
            return seen.get();
        }

        PaymentAccount account = resolveAccount(trim(payload.accountIdentifier()));

        CoopStatement statement = CoopStatement.builder()
                .refNo(refNo == null ? "UNKNOWN-" + RrnGenerator.generate("RX") : refNo)
                .traceId(trim(payload.traceId()))
                .ourReference(RrnGenerator.generate("PS"))
                .transType(trim(payload.transType()) == null ? "UNKNOWN" : trim(payload.transType()))
                .paymentAccountId(account == null ? null : account.getId())
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
                .tenantId(account == null ? null : account.getTenantId())
                .institutionId(account == null ? null : account.getInstitutionId())
                .state(AppConstant.STATEMENT_UNMAPPED)
                .createdBy(AppConstant.USERNAME_SYSTEM)
                .updatedBy(AppConstant.USERNAME_SYSTEM)
                .build();

        CoopStatement stored;
        try {
            stored = statements.saveAndFlush(statement);
        } catch (DataIntegrityViolationException e) {
            /*
             * The race the check above cannot win: two deliveries of the same refNo at once. The index refused
             * the second, and the first one's row is the answer — so this is a success, not a failure.
             */
            log.info("Concurrent delivery of Co-op notification {}; keeping the first", refNo);
            return statements.findByRefNo(refNo).orElseThrow(() -> e);
        }

        // Matching is deliberately after the flush, so nothing below can lose the row above.
        tryToPlace(stored, account, trusted);
        return stored;
    }

    /**
     * Which of our accounts a notification landed in.
     *
     * <p>The account number first, because that is what the Co-op contract carries; the short code as a
     * fallback for a bank that quotes that instead. Live accounts only — a withdrawn account is exactly the
     * one a credit must not be matched to.
     */
    private PaymentAccount resolveAccount(String accountIdentifier) {
        if (accountIdentifier == null) return null;
        return accounts.findLiveByAccountNo(accountIdentifier)
                .or(() -> accounts.findLiveByShortCode(accountIdentifier))
                .orElse(null);
    }

    /**
     * Attaches the statement to a booking, when it is safe to do so without a person.
     *
     * <p>Every reason not to is written onto the row in words. "Unmatched" with no explanation is a queue
     * nobody can work through, and the reason is usually the whole answer — a reference nobody recognises, an
     * amount that does not correspond to anything owed.
     */
    private void tryToPlace(CoopStatement statement, PaymentAccount account, boolean trusted) {
        if (!trusted) {
            /*
             * No shared secret configured, so this notification is unauthenticated.
             *
             * It is still stored — refusing would make Co-op retry and eventually give up, losing real money —
             * but nothing is credited automatically. A forged notification that guessed a four-character code
             * and an amount would otherwise move a buyer's balance.
             */
            unplaced(statement, "Notifications are not authenticated yet, so every payment is placed by "
                    + "hand. Set the Co-op notification secret to allow automatic matching.");
            return;
        }
        if (account == null) {
            unplaced(statement, "The account " + statement.getAccountIdentifier()
                    + " is not one of ours, or has not been registered here yet.");
            return;
        }
        /*
         * An intent first, when the reference is one of ours.
         *
         * <p>This is the payment somebody started from the app: we asked, and this is the answer. It is
         * tried before the unit code because it needs no corroboration — we already know the amount, the
         * booking and the person, because we chose them when we asked.
         */
        if (creditedAnIntent(statement)) return;

        if (statement.getReference() == null || statement.getReference().isBlank()) {
            unplaced(statement, "The payer quoted no reference, so there is nothing to match on.");
            return;
        }

        Optional<Property> unit = units.findByPayReference(payCode(statement.getReference()));
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

        // Through the payments module, so a gateway credit carries the same names, snapshots and channel a
        // hand-keyed payment does — and so there is exactly one place a payment row is written.
        Payment payment = payments.recordFromGateway(target, statement, account);

        statement.setState(AppConstant.STATEMENT_MAPPED);
        statement.setMappedPaymentId(payment.getId());
        statement.setMappedBookingId(target.getId());
        statement.setMappedAt(OffsetDateTime.now());
        statement.setMappedBy(AppConstant.USERNAME_SYSTEM);
        statement.setUnmappedReason(null);
        statements.save(statement);

        log.info("Co-op notification {} placed on booking {} as {}", statement.getRefNo(),
                target.getReference(), payment.getReference());
    }

    /**
     * Places a notification that answers a prompt we started.
     *
     * <h3>The same money arrives more than once, routinely</h3>
     *
     * <p>Co-op retries a callback it believes went unacknowledged, and the status query may already have
     * settled the payment before the notification lands. So an intent that has already produced a payment
     * is linked to this notification and credited no further — one payment, whichever path confirmed it
     * first. Crediting both would double a buyer's balance, and a doubled balance is discovered by the
     * buyer rather than by us.
     *
     * @return true when this notification belonged to an intent and has been dealt with
     */
    private boolean creditedAnIntent(CoopStatement statement) {
        String quoted = trim(statement.getReference());
        PaymentIntent intent = null;
        if (quoted != null) {
            intent = intents.findByReference(quoted)
                    .or(() -> intents.findByBankReference(quoted))
                    .orElse(null);
        }
        if (intent == null && trim(statement.getTraceId()) != null) {
            intent = intents.findByReference(trim(statement.getTraceId())).orElse(null);
        }
        if (intent == null) return false;

        if (intent.getPaymentId() != null) {
            // Already credited — by the status query, or by an earlier delivery of this same money.
            settlement.attachStatement(intent, statement.getId(), intent.getPaymentId());
            statement.setState(AppConstant.STATEMENT_MAPPED);
            statement.setMappedPaymentId(intent.getPaymentId());
            statement.setMappedBookingId(intent.getBookingId());
            statement.setMappedAt(OffsetDateTime.now());
            statement.setMappedBy(AppConstant.USERNAME_SYSTEM);
            statement.setUnmappedReason(null);
            statements.save(statement);
            log.info("Co-op notification {} answers payment {}, which was already credited as {}",
                    statement.getRefNo(), intent.getReference(), intent.getPaymentId());
            return true;
        }

        UnitBooking booking = intent.getBookingId() == null ? null
                : bookings.findById(intent.getBookingId()).orElse(null);
        if (booking == null) {
            unplaced(statement, "This answers payment " + intent.getReference()
                    + ", whose booking no longer exists. Place it by hand.");
            return true;
        }

        PaymentAccount account = intent.getPaymentAccountId() == null ? null
                : accounts.findById(intent.getPaymentAccountId()).orElse(null);
        Payment payment = payments.recordFromGateway(booking, statement, account);

        settlement.attachStatement(intent, statement.getId(), payment.getId());
        statement.setState(AppConstant.STATEMENT_MAPPED);
        statement.setMappedPaymentId(payment.getId());
        statement.setMappedBookingId(booking.getId());
        statement.setMappedAt(OffsetDateTime.now());
        statement.setMappedBy(AppConstant.USERNAME_SYSTEM);
        statement.setUnmappedReason(null);
        statements.save(statement);

        log.info("Co-op notification {} placed on payment {} for booking {} as {}", statement.getRefNo(),
                intent.getReference(), booking.getReference(), payment.getReference());
        return true;
    }

    /**
     * Whether something other than the reference agrees that this payment belongs to this booking.
     *
     * <p>Either the amount corresponds to money actually owed, or the paying number is the buyer's. One
     * mistyped character is enough to hit another live code; it is not enough to also match an amount or a
     * phone number.
     */
    private boolean corroborated(CoopStatement statement, UnitBooking booking) {
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
     * <p>Compared on the last nine digits. Co-op sends {@code 254712345678}; a buyer's record may hold
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

    private void unplaced(CoopStatement statement, String reason) {
        statement.setState(AppConstant.STATEMENT_UNMAPPED);
        statement.setUnmappedReason(reason);
        statements.save(statement);
        log.info("Co-op notification {} stored unmapped: {}", statement.getRefNo(), reason);
    }

    /** Whether the caller proved it is Co-op. Blank secret means no, and no means nothing is auto-placed. */
    /**
     * Whether the caller proved it is Co-op.
     *
     * <h3>Closed when unset, not open</h3>
     *
     * <p>Co-op reaches this platform directly and authenticates with HTTP Basic. While either credential is
     * blank this answers false for every caller, and the endpoint refuses: a deployment that accepts
     * anonymous payment notifications records money that never arrived, which is worse than one that
     * accepts none and stops.
     *
     * <p>Constant-time on both halves — a timing-comparable equals on a shared secret leaks it a byte at a
     * time, and the username is as much a secret as the password when it is the whole of the credential.
     */
    public boolean isTrusted(String authorizationHeader) {
        String user = configs.getString(ConfigKey.COOP_IPN_USERNAME);
        String secret = configs.getString(ConfigKey.COOP_IPN_PASSWORD);
        if (user == null || user.isBlank() || secret == null || secret.isBlank()) return false;
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String presented;
        try {
            presented = new String(java.util.Base64.getDecoder()
                    .decode(authorizationHeader.substring(6).trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (user + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Whether this address is one Co-op notifies us from.
     *
     * <p><b>Empty accepts any address.</b> A blank allow-list is "not narrowed yet", not "trust nobody" —
     * the other reading turns an unconfigured deployment into one that silently discards notifications for
     * money already sitting in the bank. HTTP Basic is the control that fails closed; this narrows it to a
     * known set once somebody knows what that set is.
     *
     * <p>One list for the bank, not one per account: which of Co-op's addresses may reach us is a fact
     * about Co-op.
     */
    public boolean isFromAllowedAddress(String remoteAddress) {
        String allowed = configs.getString(ConfigKey.COOP_IPN_ALLOWED_IPS);
        if (allowed == null || allowed.isBlank()) return true;
        if (remoteAddress == null || remoteAddress.isBlank()) return false;
        String caller = remoteAddress.trim();
        for (String entry : allowed.split(",")) {
            if (caller.equalsIgnoreCase(entry.trim())) return true;
        }
        return false;
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
            log.warn("Co-op notification carried an unreadable amount: {}", amount);
            return BigDecimal.ZERO;
        }
    }

    /** Never fatal: a payment with an unreadable timestamp is still a payment. */
    private OffsetDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return OffsetDateTime.now();
        try {
            return LocalDateTime.parse(timestamp.trim(), COOP_TIME).atOffset(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            log.debug("Co-op timestamp not in the documented format: {}", timestamp);
            return OffsetDateTime.now();
        }
    }

    private String toJson(IpnPayload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            // The audit copy is worth having and not worth failing for.
            log.warn("Could not serialise a Co-op payload for audit: {}", e.getMessage());
            return null;
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
