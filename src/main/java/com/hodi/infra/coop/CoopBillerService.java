package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.common.EncryptionUtil;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingBalanceReader;
import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import com.hodi.modules.bookings.BookingInstalment;
import com.hodi.modules.bookings.BookingInstalmentRepository;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.payments.ChannelConfig;
import com.hodi.modules.payments.CoopChannel;
import com.hodi.modules.payments.PayeeResolver;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Co-op's biller protocol: "is this reference real, and what is owed on it", then "the customer paid it".
 *
 * <h2>Shapes are pesi's, and pesi's are Co-op's</h2>
 *
 * <p>The request and response bodies, the header envelope and the status codes are ported from the
 * gateway that already speaks this protocol to Co-op in production, so nothing here is a guess about
 * what the bank sends. In their vocabulary: {@code 200} done, {@code 400} something missing, {@code 401}
 * the connection credentials are wrong, {@code 402} this transaction was already advised, {@code 404}
 * no such customer reference, {@code 405} we could not answer. The HTTP status is always 200; the code
 * in the header is the answer.
 *
 * <h2>Who is calling</h2>
 *
 * <p>Co-op names the biller by {@code InstitutionCode} and {@code serviceName}, and presents a connection
 * ID and password. Those live on the biller's payment account, encrypted, and are compared in constant
 * time. While either is unset the biller is <b>closed</b>: a misconfigured deployment that answered
 * validations for anybody would be telling strangers who owes what.
 *
 * <h2>One inbound path</h2>
 *
 * <p>An advice is a notification with a different envelope. It is stored as a statement on the biller's
 * account, keyed on Co-op's {@code TransactionReferenceCode}, and placed by the same matcher every other
 * statement goes through. A validation answers from the same resolver, so the reference a customer is
 * told is valid is the one the advice will then be placed on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoopBillerService {

    public static final String TRANS_TYPE = CoopChannel.COOP_BILLER.name();

    static final String OK = "200";
    static final String BAD_REQUEST = "400";
    static final String UNAUTHORISED = "401";
    static final String DUPLICATE = "402";
    static final String NOT_FOUND = "404";
    static final String UNAVAILABLE = "405";

    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final CoopStatementRepository statements;
    private final PayeeResolver resolver;
    private final BookingBalanceReader balances;
    private final BookingInstalmentRepository instalments;
    private final CoopIpnService placer;
    private final EncryptionUtil crypto;
    private final ObjectMapper mapper;

    // ── validation ────────────────────────────────────────────────────────────

    /** Is this reference real, and what is owed on it. Reads only. */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(Map<String, Object> body) {
        Map<String, Object> header = section(body, "header");
        Map<String, Object> request = section(body, "request");
        String messageId = text(header, "messageID");
        if (header.isEmpty() || request.isEmpty()) return error(messageId, BAD_REQUEST, "Missing header or body");

        String institution = text(request, "InstitutionCode");
        String service = text(header, "serviceName");
        String quoted = text(request, "TransactionReferenceCode");
        if (institution == null || service == null || quoted == null) {
            return error(messageId, BAD_REQUEST, "Missing mandatory fields");
        }

        Biller biller = biller(institution, service, header);
        if (biller.refused != null) return error(messageId, biller.refused, biller.reason);

        PayeeResolver.Resolution match = resolver.resolve(quoted);
        if (!match.found()) {
            log.info("Co-op biller validation of \"{}\" for {}: {}", quoted, institution, match.reason());
            return error(messageId, NOT_FOUND, "Customer reference not found");
        }
        UnitBooking booking = match.booking();
        BigDecimal due = amountDue(booking);
        if (due.signum() <= 0) {
            return error(messageId, NOT_FOUND, "Nothing is owed on that reference");
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("TransactionReferenceCode", quoted);
        answer.put("TransactionDate", text(request, "TransactionDate"));
        answer.put("TotalAmount", due);
        answer.put("Currency", booking.getCurrency() == null ? "KES" : booking.getCurrency());
        answer.put("AdditionalInfo", booking.getBuyerName());
        answer.put("AccountNumber", quoted);
        answer.put("AccountName", booking.getBuyerName());
        answer.put("InstitutionCode", institution);
        answer.put("InstitutionName", biller.institutionName);
        log.info("Co-op biller validation of \"{}\": booking {} owes {} {}", quoted, booking.getReference(),
                answer.get("Currency"), due);
        return envelope(messageId, OK, "Successfully validated customer", answer);
    }

    /**
     * What a customer paying this booking now should be asked for.
     *
     * <p>Whatever is overdue first. Otherwise the next instalment not yet covered by what has been paid —
     * instalments are cumulative, so it is the first one the running total has not reached. With no
     * schedule, the balance. Never more than the balance.
     */
    private BigDecimal amountDue(UnitBooking booking) {
        Optional<BalanceRow> row = balances.forBooking(booking.getId());
        if (row.isEmpty()) return BigDecimal.ZERO;
        BalanceRow balance = row.get();
        BigDecimal outstanding = balance.balance() == null ? BigDecimal.ZERO : balance.balance();
        if (outstanding.signum() <= 0) return BigDecimal.ZERO;
        if (balance.overdue() != null && balance.overdue().signum() > 0) return balance.overdue().min(outstanding);

        BigDecimal paid = balance.paid() == null ? BigDecimal.ZERO : balance.paid();
        BigDecimal running = BigDecimal.ZERO;
        for (BookingInstalment instalment : instalments.findCurrentPlan(booking.getId())) {
            running = running.add(instalment.getAmount());
            if (running.compareTo(paid) > 0) return running.subtract(paid).min(outstanding);
        }
        return outstanding;
    }

    // ── advice ────────────────────────────────────────────────────────────────

    /**
     * The customer paid. Stored first, then placed; Co-op is told done, duplicate, or try again.
     *
     * <p>{@code REQUIRES_NEW} for the same reason the notification handler is: the record of money arriving
     * must survive on its own. A repeat of a transaction already on record is {@code 402}, which is what
     * stops the bank retrying it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> advise(Map<String, Object> body) {
        Map<String, Object> header = section(body, "header");
        Map<String, Object> request = section(body, "request");
        String messageId = text(header, "messageID");
        if (header.isEmpty() || request.isEmpty()) return error(messageId, BAD_REQUEST, "Missing header or body");

        String institution = text(request, "InstitutionCode");
        String service = text(header, "serviceName");
        String transactionRef = text(request, "TransactionReferenceCode");
        if (institution == null || service == null || transactionRef == null) {
            return error(messageId, BAD_REQUEST, "Missing mandatory fields");
        }

        Biller biller = biller(institution, service, header);
        if (biller.refused != null) return error(messageId, biller.refused, biller.reason);

        BigDecimal amount = amount(first(request, "TotalAmount", "PaymentAmount"));
        if (amount == null || amount.signum() <= 0) return error(messageId, BAD_REQUEST, "Invalid amount");

        if (statements.findByRefNo(transactionRef).isPresent()) {
            log.info("Co-op biller advice {} already recorded", transactionRef);
            return error(messageId, DUPLICATE, "Duplicate transaction");
        }

        String quoted = first(request, "DocumentReferenceNumber", "AccountNumber", "AdditionalInfo");
        CoopStatement statement = CoopStatement.builder()
                .refNo(transactionRef)
                .traceId(first(request, "PaymentReferenceCode"))
                .ourReference(RrnGenerator.generate("PS"))
                .transType(TRANS_TYPE)
                .paymentAccountId(biller.account.getId())
                .accountIdentifier(biller.account.getAccountNo())
                .reference(clip(quoted, 64))
                .amount(amount)
                .currency(first(request, "Currency") == null ? "KES" : first(request, "Currency"))
                .customerName(clip(first(request, "AccountName"), 160))
                .paidAt(paidAt(first(request, "PaymentDate", "TransactionDate")))
                .rawPayload(audited(body))
                .tenantId(biller.account.getTenantId())
                .institutionId(biller.account.getInstitutionId())
                .state(AppConstant.STATEMENT_UNMAPPED)
                .createdBy(AppConstant.USERNAME_SYSTEM)
                .updatedBy(AppConstant.USERNAME_SYSTEM)
                .build();

        CoopStatement stored;
        try {
            stored = statements.saveAndFlush(statement);
        } catch (DataIntegrityViolationException e) {
            // Two deliveries at once. The index refused the second; the first is the answer.
            log.info("Concurrent Co-op biller advice {}; keeping the first", transactionRef);
            return error(messageId, DUPLICATE, "Duplicate transaction");
        }

        placer.placeAutomatically(stored, biller.account);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("TransactionReferenceCode", transactionRef);
        answer.put("TransactionDate", first(request, "TransactionDate"));
        answer.put("TransactionAmount", first(request, "TotalAmount"));
        answer.put("AccountNumber", first(request, "AccountNumber"));
        answer.put("AccountName", first(request, "AccountName"));
        answer.put("InstitutionCode", institution);
        answer.put("InstitutionName", biller.institutionName);
        answer.put("Currency", first(request, "Currency"));
        answer.put("AdditionalInfo", first(request, "AdditionalInfo"));
        answer.put("TotalAmount", first(request, "TotalAmount"));
        log.info("Co-op biller advice {} stored as {} ({})", transactionRef, stored.getOurReference(),
                stored.getState());
        return envelope(messageId, OK, "Payment successfully received", answer);
    }

    // ── who is calling ────────────────────────────────────────────────────────

    /** The biller Co-op named, once it has proved it is Co-op. */
    private record Biller(PaymentAccount account, String institutionName, String refused, String reason) {
        static Biller refuse(String code, String reason) { return new Biller(null, null, code, reason); }
    }

    private Biller biller(String institution, String service, Map<String, Object> header) {
        PaymentType channel = types.findByProviderType(CoopChannel.COOP_BILLER.name()).orElse(null);
        if (channel == null || !channel.isAvailable()) return Biller.refuse(UNAVAILABLE, "Biller not available");

        String key = ChannelConfig.accountKey(channel.getAccountConfigFields(),
                Map.of("institutionCode", institution, "serviceName", service));
        PaymentAccount account = key == null ? null : accounts.findLiveByAccountNo(key)
                .filter(a -> CoopChannel.COOP_BILLER.name().equals(a.getProviderCode()))
                .orElse(null);
        if (account == null) return Biller.refuse(NOT_FOUND, "Biller not found");

        Map<String, Object> descriptor = channel.getAccountConfigFields();
        String expectedId = ChannelConfig.value(descriptor, account.getConfig(), "connectionID", crypto);
        String expectedPassword = ChannelConfig.value(descriptor, account.getConfig(), "connectionPassword", crypto);
        if (blank(expectedId) || blank(expectedPassword)) {
            // Closed while unconfigured, never open. See the class note.
            return Biller.refuse(UNAVAILABLE, "Incomplete biller configuration");
        }
        if (!same(expectedId, text(header, "connectionID")) || !same(expectedPassword, text(header, "connectionPassword"))) {
            log.warn("Co-op biller {}: connection credentials refused", institution);
            return Biller.refuse(UNAUTHORISED, "Caller not authorized");
        }
        String institutionName = ChannelConfig.value(descriptor, account.getConfig(), "institutionName", crypto);
        return new Biller(account, institutionName == null ? account.getAccountName() : institutionName, null, null);
    }

    private static boolean same(String expected, String presented) {
        if (expected == null || presented == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    // ── the envelope ──────────────────────────────────────────────────────────

    static Map<String, Object> envelope(String messageId, String code, String description,
                                        Map<String, Object> answer) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("messageID", messageId);
        header.put("statusCode", code);
        header.put("statusDescription", description);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", header);
        if (answer != null) out.put("response", answer);
        return out;
    }

    static Map<String, Object> error(String messageId, String code, String description) {
        return envelope(messageId, code, description, null);
    }

    /** The message as it arrived, less the password, which is a secret and not evidence. */
    private String audited(Map<String, Object> body) {
        try {
            Map<String, Object> copy = new LinkedHashMap<>(body);
            Map<String, Object> header = new LinkedHashMap<>(section(body, "header"));
            if (header.containsKey("connectionPassword")) header.put("connectionPassword", ChannelConfig.MASK);
            copy.put("header", header);
            return mapper.writeValueAsString(copy);
        } catch (RuntimeException e) {
            log.warn("Could not serialise a Co-op biller advice for audit: {}", e.getMessage());
            return null;
        }
    }

    // ── reading Co-op's fields ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> body, String name) {
        if (body == null) return Map.of();
        Object value = body.get(name);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = text(map, key);
            if (value != null) return value;
        }
        return null;
    }

    private static BigDecimal amount(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Co-op writes "2025-06-30T16:26:43.0198942+03:00". Anything unreadable is now; a payment is not lost over a clock. */
    private static OffsetDateTime paidAt(String value) {
        if (value == null) return OffsetDateTime.now();
        try {
            OffsetDateTime at = OffsetDateTime.parse(value);
            return at.isAfter(OffsetDateTime.now()) ? OffsetDateTime.now() : at;
        } catch (DateTimeParseException e) {
            return OffsetDateTime.now();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String clip(String value, int width) {
        return value == null || value.length() <= width ? value : value.substring(0, width);
    }

    /** For the tests: the key an advice for this biller is matched on. */
    public static String accountKeyFor(PaymentType channel, String institutionCode, String serviceName) {
        return ChannelConfig.accountKey(channel.getAccountConfigFields(),
                Map.of("institutionCode", institutionCode, "serviceName", serviceName));
    }

}
