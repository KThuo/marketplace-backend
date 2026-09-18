package com.hodi.infra.coop;

import com.hodi.infra.coop.CoopIpnDtos.IpnPayload;

import java.util.List;
import java.util.Map;

/**
 * Reading what Co-op actually posts.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The notification endpoint was built to a record whose field names were invented here —
 * {@code refNo}, {@code accountIdentifier}, {@code phoneNo}. Co-op posts {@code AcctNo},
 * {@code PaymentRef}, {@code TransactionId} and a {@code Narration}, and nothing in between translated
 * one to the other. Every notification would have bound to an empty record and been stored as an
 * unplaceable row quoting nothing. That is why no payment ever completed.
 *
 * <h2>Two shapes, one endpoint</h2>
 *
 * <p>A credit into an account and a response to a prompt arrive at the same URL wearing different names:
 * the first carries {@code AcctNo} and a narration, the second carries the {@code MessageReference} we
 * sent. Both are normalised here rather than at two endpoints, because Co-op decides which it sends and
 * an endpoint per shape would mean guessing right in advance.
 *
 * <h2>The narration is the payer</h2>
 *
 * <p>Co-op splits one line across {@code CustMemoLine1..3} and repeats it whole in {@code Narration},
 * tilde-separated:
 * {@code TIP6V5IRAG~254707919065~01120000568900~MPESAC2B_400200~MELVIN WANJIKU} — receipt, phone,
 * account, channel, name. It is the only place the payer's own number and name appear, and both are what
 * a credit is corroborated against, so it is parsed rather than stored as prose.
 */
public final class CoopInbound {

    private CoopInbound() {}

    /** Only a credit is money arriving. A debit notification on the same account is not ours to place. */
    public static final String CREDIT = "CREDIT";

    public static IpnPayload parse(Map<String, Object> body) {
        if (body == null) return new IpnPayload(null, null, null, null, null, null, null, null, null, null);

        List<String> narration = split(first(body, "Narration", "narration"));

        return new IpnPayload(
                // Their own unique handle for the posting, which is what a retry repeats and therefore
                // what deduplication turns on.
                first(body, "TransactionId", "transactionId", "PaymentRef", "paymentRef",
                        "MessageReference", "messageReference"),
                first(body, "MessageReference", "messageReference", "PaymentRef", "paymentRef"),
                first(body, "TransactionDate", "transactionDate", "PostingDate", "postingDate",
                        "ValueDate", "valueDate"),
                first(body, "Amount", "amount", "TotalAmount", "PaymentAmount"),
                first(body, "Currency", "currency", "TransactionCurrency"),
                /*
                 * What the payer quoted, which is what a payment is matched on.
                 *
                 * The message reference first, because a prompt we started comes back carrying the one we
                 * sent and that is an exact answer. Otherwise the first field of the narration — the
                 * payer's own receipt — then whatever account reference the biller flow carries.
                 */
                firstNotBlank(
                        first(body, "MessageReference", "messageReference"),
                        at(narration, 0),
                        first(body, "AccountNumber", "accountNumber", "PaymentRef", "paymentRef")),
                // The name is the last thing on the narration line, and Co-op sends no separate field.
                firstNotBlank(first(body, "CustomerName", "customerName"), last(narration)),
                firstNotBlank(first(body, "MobileNumber", "mobileNumber", "PhoneNumber"),
                        phoneIn(narration)),
                // Which of our accounts it landed in.
                first(body, "AcctNo", "acctNo", "AccountNumber", "accountNumber"),
                firstNotBlank(first(body, "EventType", "eventType"), CREDIT));
    }

    /** Whether this notification is money arriving at all. */
    public static boolean isCredit(Map<String, Object> body) {
        String event = first(body, "EventType", "eventType");
        return event == null || event.isBlank() || CREDIT.equalsIgnoreCase(event.trim());
    }

    // ── the narration ─────────────────────────────────────────────────────────

    private static List<String> split(String narration) {
        if (narration == null || narration.isBlank()) return List.of();
        return java.util.Arrays.stream(narration.split("~")).map(String::trim).toList();
    }

    /**
     * The payer's number, found by shape rather than by position.
     *
     * <p>Position would be brittle: the same line carries an account number of similar length, and the
     * order has already varied between Co-op's own examples. A Kenyan mobile number is 254 followed by
     * nine digits, which nothing else on that line looks like.
     */
    private static String phoneIn(List<String> parts) {
        for (String part : parts) {
            String digits = part.replaceAll("\\D", "");
            if (digits.length() == 12 && digits.startsWith("254")) return "+" + digits;
            if (digits.length() == 10 && digits.startsWith("0")) return digits;
        }
        return null;
    }

    private static String at(List<String> parts, int index) {
        return parts.size() > index ? parts.get(index) : null;
    }

    private static String last(List<String> parts) {
        return parts.isEmpty() ? null : parts.get(parts.size() - 1);
    }

    // ── reading a loosely-typed body ──────────────────────────────────────────

    private static String first(Map<String, Object> body, String... names) {
        if (body == null) return null;
        for (String name : names) {
            Object value = body.get(name);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
