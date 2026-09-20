package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.payments.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompting a customer's phone, and finding out what became of it.
 *
 * <h2>The order of operations is the design</h2>
 *
 * <p>The intent is written and committed <em>before</em> the call goes out. Co-op may push the prompt and
 * then the connection may die on our side; if the row were written afterwards, that customer would be
 * debited against nothing this platform could ever find. So: write, commit, call, record what came back.
 *
 * <h2>The acknowledgement is not the money</h2>
 *
 * <p>A successful push means Co-op accepted the request and the customer's handset is ringing. It says
 * nothing about whether they entered their PIN. The money is confirmed by the callback, or by the status
 * query when no callback comes — never by the acknowledgement.
 *
 * <h2>A transport failure is unknown, not failed</h2>
 *
 * <p>If the request left this process and the answer did not come back, the prompt may well be on the
 * customer's phone right now. That intent stays {@code PROCESSING} for the status query to settle. Failing
 * it would tell somebody their payment did not go through while their money moved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoopStkService {

    /** Co-op's timestamp format, to the millisecond with an offset. */
    private static final DateTimeFormatter COOP_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final CoopClient coop;
    private final PaymentIntentRepository intents;
    private final PaymentTypeRepository types;
    private final PaymentAccountRepository accounts;
    private final UnitBookingRepository bookings;
    private final ConfigurationService configs;
    private final CoopIntentSettlement settlement;
    private final com.hodi.common.EncryptionUtil crypto;
    /** For keeping the enquiry's whole answer on the statement written from it, as a notification's payload is. */
    private final tools.jackson.databind.ObjectMapper mapper;

    // ── asking ────────────────────────────────────────────────────────────────

    /**
     * Prompts the buyer's phone for money against a booking.
     *
     * @param phone  who to prompt — the buyer's own number unless somebody else is paying for them
     * @param amount what to ask for, which may be less than the whole balance
     */
    public PaymentIntent push(Long bookingId, BigDecimal amount, String phone, String narration,
                              String by) {
        UnitBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new HodiException("That booking no longer exists.", HttpStatus.CONFLICT));
        PaymentType channel = liveChannel(CoopChannel.COOP_STK_PUSH.name());
        PaymentAccount account = accountFor(channel, booking);

        String prompting = phone == null || phone.isBlank() ? booking.getBuyerPhone() : phone.trim();
        if (prompting == null || prompting.isBlank()) {
            throw new HodiException("There is no phone number to prompt.", HttpStatus.BAD_REQUEST);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new HodiException("Enter how much to ask for.", HttpStatus.BAD_REQUEST);
        }

        // Committed before anything leaves this process. See the class note.
        PaymentIntent intent = settlement.open(PaymentIntent.builder()
                .reference(RrnGenerator.generate("IN"))
                .paymentTypeId(channel.getId())
                .paymentAccountId(account.getId())
                .bookingId(booking.getId())
                .propertyId(booking.getPropertyId())
                .buyerUserId(booking.getBuyerUserId())
                .amount(amount)
                .currency("KES")
                .phoneNo(prompting)
                .narration(narration == null || narration.isBlank()
                        ? "Payment for " + booking.getReference() : narration.trim())
                .state(PaymentIntent.PENDING)
                .callbackTimeoutSeconds(configs.getInt(ConfigKey.COOP_CALLBACK_TIMEOUT_SECONDS))
                .tenantId(account.getTenantId())
                .institutionId(account.getInstitutionId())
                .createdBy(by)
                .updatedBy(by)
                .build());

        CoopClient.Outcome<Map<String, Object>> answer =
                coop.post(channel, pushBody(intent, channel, account), true);

        if (answer.neverSent()) {
            /*
             * Nothing left this process, so no customer was prompted.
             *
             * <p>This is a settings problem — a missing endpoint, a missing host, credentials nobody has
             * filled in — and it is failed immediately with the bank's own absence as the reason. Leaving
             * it in flight said "waiting for the customer" about a prompt that was never sent, and then
             * spent the status-query budget asking Co-op about a payment they had never heard of.
             */
            return settlement.failed(intent.getId(), null, answer.failure());
        }
        if (!answer.succeeded()) {
            /*
             * The request left us and we cannot say what happened.
             *
             * Nothing is failed here: the prompt may be on the customer's handset right now. This is in
             * flight and unknown, and the status query is what settles it.
             */
            return settlement.inFlight(intent.getId(), null,
                    "Could not confirm the prompt reached Co-op (" + answer.failure()
                            + "). Left in flight; the status query will settle it.");
        }

        Map<String, Object> body = answer.value();
        CoopAnswer.Outcome outcome = read(body);
        String said = CoopAnswer.description(body);
        String bankReference = CoopAnswer.bankReference(body);

        if (outcome == CoopAnswer.Outcome.FAILED) {
            // Co-op refused the request itself — a bad number, a closed channel. Nothing was prompted.
            return settlement.failed(intent.getId(), bankReference,
                    "Co-op did not accept the prompt: " + said);
        }
        return settlement.inFlight(intent.getId(), bankReference,
                "Prompt sent. Waiting for the customer to approve it on their phone.");
    }

    // ── the answer, when it comes to us ───────────────────────────────────────

    /**
     * Co-op calling back about a prompt we started.
     *
     * <p>Two envelopes are accepted, because nobody has a specimen: Co-op's own ({@code MessageReference},
     * {@code MessageCode}, {@code MessageDescription}) and M-Pesa's ({@code Body.stkCallback} with
     * {@code CheckoutRequestID}, {@code ResultCode}, {@code ResultDesc} and a {@code CallbackMetadata}
     * carrying the receipt). Correlated on our reference first, then on the bank's.
     *
     * <p>Untrusted callbacks settle nothing. A forged "success" quoting a guessable reference would
     * otherwise credit a buyer; the intent stays in flight and the status enquiry decides.
     *
     * @return what the callback was about, for the log and the acknowledgement; null when it matched nothing
     */
    public PaymentIntent callback(Map<String, Object> body, boolean trusted) {
        Map<String, Object> mpesa = stkCallback(body);
        String ourReference = text(body.get("MessageReference"), body.get("messageReference"));
        String checkout = mpesa == null ? null : text(mpesa.get("CheckoutRequestID"));

        PaymentIntent intent = null;
        if (ourReference != null) {
            intent = intents.findByReference(ourReference).or(() -> intents.findByBankReference(ourReference))
                    .orElse(null);
        }
        if (intent == null && checkout != null) {
            intent = intents.findByBankReference(checkout).or(() -> intents.findByReference(checkout))
                    .orElse(null);
        }
        if (intent == null) {
            log.info("Co-op STK callback matched no prompt (reference {}, checkout {})", ourReference, checkout);
            return null;
        }
        if (!trusted) {
            log.warn("Unauthenticated Co-op STK callback for {} ignored; the status enquiry will settle it",
                    intent.getReference());
            return intent;
        }

        String coopCode = text(body.get("MessageCode"), body.get("messageCode"));
        String bankReference = checkout != null ? checkout : CoopAnswer.bankReference(body);
        String raw = toJson(body);

        if (coopCode != null) {
            CoopAnswer.Outcome outcome = read(body);
            String said = CoopAnswer.description(body);
            return switch (outcome) {
                case SUCCESS -> settlement.succeeded(intent.getId(), bankReference,
                        CoopAnswer.receipt(body), said, raw, "callback");
                case FAILED -> settlement.failed(intent.getId(), bankReference,
                        "Co-op says it did not go through: " + said);
                case PENDING -> settlement.inFlight(intent.getId(), bankReference,
                        "Co-op says it is still in progress: " + said);
            };
        }
        if (mpesa != null) {
            String resultCode = text(mpesa.get("ResultCode"));
            String said = text(mpesa.get("ResultDesc"));
            if ("0".equals(resultCode)) {
                return settlement.succeeded(intent.getId(), bankReference, metadataItem(mpesa, "MpesaReceiptNumber"),
                        said == null ? "success" : said, raw, "callback");
            }
            if (resultCode == null || resultCode.isBlank()) {
                return settlement.inFlight(intent.getId(), bankReference,
                        "A callback arrived with no result code; waiting for the status enquiry.");
            }
            return settlement.failed(intent.getId(), bankReference,
                    "Co-op says it did not go through: " + (said == null ? "code " + resultCode : said));
        }
        return settlement.inFlight(intent.getId(), bankReference,
                "A callback arrived that could not be read; waiting for the status enquiry.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stkCallback(Map<String, Object> body) {
        if (body == null || !(body.get("Body") instanceof Map<?, ?> wrapper)) return null;
        return wrapper.get("stkCallback") instanceof Map<?, ?> callback ? (Map<String, Object>) callback : null;
    }

    /** {@code CallbackMetadata.Item[]} is a list of {@code {Name, Value}}; this reads one by name. */
    private static String metadataItem(Map<String, Object> callback, String name) {
        if (!(callback.get("CallbackMetadata") instanceof Map<?, ?> metadata)) return null;
        if (!(metadata.get("Item") instanceof List<?> items)) return null;
        for (Object item : items) {
            if (item instanceof Map<?, ?> entry && name.equalsIgnoreCase(String.valueOf(entry.get("Name")))) {
                return text(entry.get("Value"));
            }
        }
        return null;
    }

    private static String text(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate == null) continue;
            String value = String.valueOf(candidate).trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    // ── asking what became of it ──────────────────────────────────────────────

    /**
     * Asks Co-op what became of a prompt, and settles the intent when the answer is definite.
     *
     * <p>Called from the sweep and from an operator's button, and from nowhere else. It never fails an
     * intent on its own uncertainty: an error here, an unreadable code, a still-processing answer all
     * leave the payment in flight with a sentence saying so.
     *
     * @param counted whether this attempt counts against the automatic cap — false for a person
     * @return the state the intent is now in
     */
    public String query(Long intentId, boolean counted, String by) {
        PaymentIntent intent = intents.findById(intentId)
                .orElseThrow(() -> new HodiException("That payment no longer exists.", HttpStatus.CONFLICT));
        if (!intent.inFlight()) return intent.getState();

        Map<String, Object> body = new LinkedHashMap<>();
        // What we asked under. Co-op's own reference when they gave one, ours otherwise — ours is what
        // we sent as the MessageReference, so it is answerable either way.
        body.put("MessageReference",
                intent.getBankReference() == null ? intent.getReference() : intent.getBankReference());

        /*
         * Through its own method row, like everything else.
         *
         * <p>It is an ENQUIRY rather than a way to pay, so nothing offers it and nobody can choose it —
         * but it has an endpoint, and an endpoint belongs on the method it is for.
         */
        PaymentType enquiry = types.findByProviderType(CoopChannel.COOP_STK_STATUS.name())
                .filter(PaymentType::isAvailable)
                .orElse(null);
        if (enquiry == null) {
            return settlement.stillWaiting(intent.getId(), intent.getStatusQueryAttempts(),
                    "The Co-op status enquiry is switched off, so it cannot be asked. Settle this one "
                            + "by hand.").getState();
        }
        CoopClient.Outcome<Map<String, Object>> answer = coop.post(enquiry, body, false);
        int attempts = counted ? intent.getStatusQueryAttempts() + 1 : intent.getStatusQueryAttempts();

        if (answer.neverSent()) {
            // The query itself is not configured. Nothing was asked, so nothing was learned, and the
            // attempt does not count — a cap should be spent on the bank's silence, not on ours.
            return settlement.stillWaiting(intent.getId(), intent.getStatusQueryAttempts(),
                    answer.failure()).getState();
        }
        if (!answer.succeeded()) {
            return settlement.stillWaiting(intent.getId(), attempts,
                    "Asked Co-op and could not get an answer (" + answer.failure() + ").").getState();
        }

        Map<String, Object> response = answer.value();
        CoopAnswer.Outcome outcome = read(response);
        String said = CoopAnswer.description(response);

        return switch (outcome) {
            case SUCCESS -> settlement.succeeded(intent.getId(),
                    CoopAnswer.bankReference(response), CoopAnswer.receipt(response), said,
                    toJson(response), by).getState();
            case FAILED -> settlement.failed(intent.getId(), CoopAnswer.bankReference(response),
                    /*
                     * "Message Reference does not exist" is the answer when the push never reached them,
                     * and it reads like a system fault rather than what it is. Said plainly, because the
                     * next question is always "so was my customer charged" and the answer is no.
                     */
                    unknownReference(said)
                            ? "Co-op has no record of this request, so nothing was prompted and no "
                                    + "money moved. Check the endpoint on this method."
                            : "Co-op says it did not go through: " + said).getState();
            case PENDING -> settlement.stillWaiting(intent.getId(), attempts,
                    "Co-op says it is still in progress: " + said).getState();
        };
    }

    private String toJson(Map<String, Object> response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (RuntimeException e) {
            // Worth keeping, not worth failing a confirmed payment for.
            log.warn("Could not serialise Co-op's answer for the statement: {}", e.getMessage());
            return null;
        }
    }

    /** Whether Co-op is telling us they have never seen this reference. */
    private static boolean unknownReference(String said) {
        String lower = said == null ? "" : said.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("does not exist") || lower.contains("not found");
    }

    // ── the body Co-op expects ────────────────────────────────────────────────

    private Map<String, Object> pushBody(PaymentIntent intent, PaymentType channel,
                                         PaymentAccount account) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Ours. It comes back on the callback and it is what the status query asks about.
        body.put("MessageReference", intent.getReference());
        body.put("CallBackUrl", callbackUrl());
        // The operator code Co-op issued, which lives on the account rather than in the application.
        body.put("OperatorCode", ChannelConfig.value(channel.getAccountConfigFields(),
                account.getConfig(), "accountNumber", crypto));
        body.put("TransactionCurrency", intent.getCurrency());
        body.put("MobileNumber", intent.getPhoneNo());
        body.put("Narration", intent.getNarration());
        body.put("Amount", intent.getAmount());
        body.put("MessageDateTime", OffsetDateTime.now(ZoneOffset.UTC).format(COOP_TIME));
        // What the payer would have typed at a till, so the credit can be placed even if the callback
        // is lost and the money arrives as an ordinary notification.
        body.put("OtherDetails", List.of(Map.of("Name", "Reference", "Value", intent.getReference())));
        return body;
    }

    private String callbackUrl() {
        String base = configs.getString(ConfigKey.PUBLIC_URL);
        if (base == null || base.isBlank()) return null;
        String trimmed = base.trim();
        String root = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return root + CoopRoutes.NOTIFICATIONS;
    }

    private CoopAnswer.Outcome read(Map<String, Object> response) {
        return CoopAnswer.read(response,
                CoopAnswer.csv(configs.getString(ConfigKey.COOP_PENDING_STATUS_CODES)),
                CoopAnswer.csv(configs.getString(ConfigKey.COOP_PENDING_STATUS_DESCRIPTIONS)));
    }

    private PaymentType liveChannel(String providerType) {
        return types.findByProviderType(providerType)
                .filter(PaymentType::isAvailable)
                .orElseThrow(() -> new HodiException(
                        "The Co-op phone prompt is not switched on yet.", HttpStatus.CONFLICT));
    }

    /**
     * The account this prompt collects into.
     *
     * <p>The booking's own organisation's account when it has one that reaches this development, else the
     * platform's — the same "stands behind" rule the receive form uses — and always the oldest such row,
     * so which account collects does not change between restarts. Live only: a pending account is one
     * nobody has approved, and prompting money into it would route real payments through a destination
     * that has not had its second pair of eyes.
     */
    private PaymentAccount accountFor(PaymentType channel, UnitBooking booking) {
        List<PaymentAccount> live = accounts.findByPaymentTypeIdNotArchived(channel.getId()).stream()
                .filter(a -> a.getStatus() != null
                        && (a.getStatus() == AppConstant.STATUS_ACTIVE
                            || a.getStatus() == AppConstant.STATUS_EDITED))
                .filter(a -> a.reaches(booking.getDevelopmentId()))
                .sorted(java.util.Comparator.comparing(PaymentAccount::getId))
                .toList();
        return live.stream()
                .filter(a -> a.belongsTo(booking.getTenantId(), booking.getInstitutionId()))
                .findFirst()
                .or(() -> live.stream().filter(PaymentAccount::isPlatformOwned).findFirst())
                .orElseThrow(() -> new HodiException(
                        "No approved account is set up for the Co-op phone prompt yet.",
                        HttpStatus.CONFLICT));
    }
}
