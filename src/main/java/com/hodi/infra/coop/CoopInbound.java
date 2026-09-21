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
 * <h2>The narration is the payer, and the receipt</h2>
 *
 * <p>Co-op splits one line across {@code CustMemoLine1..3} and repeats it whole in {@code Narration},
 * tilde-separated, in one of the shapes {@link CoopNarration} knows. It is the only place the payer's own
 * number and name appear, and both are what a credit is corroborated against, so it is parsed rather than
 * stored as prose. Its first part is the M-Pesa receipt — the reference the customer holds — and that, not
 * the bank's {@code TransactionId}, is the statement's bank reference; the bank's id is kept beside it.
 */
public final class CoopInbound {

    private CoopInbound() {}

    /** Only a credit is money arriving. A debit notification on the same account is not ours to place. */
    public static final String CREDIT = "CREDIT";

    public static IpnPayload parse(Map<String, Object> body) {
        if (body == null) return new IpnPayload(null, null, null, null, null, null, null, null, null, null);

        List<String> narration = split(first(body, "Narration", "narration"));
        CoopNarration line = CoopNarration.parse(first(body, "Narration", "narration"));
        String bankId = first(body, "TransactionId", "transactionId");

        return new IpnPayload(
                /*
                 * The bank reference is the receipt the customer holds — the first part of the narration —
                 * so a slip validated on the number the payer reads off their phone finds the money. A retry
                 * repeats the same narration, so it deduplicates just as well. The bank's own id is the
                 * fallback for a narration that carries no receipt, and is always kept in ft.
                 */
                firstNotBlank(line.receipt(), bankId, first(body, "PaymentRef", "paymentRef",
                        "MessageReference", "messageReference")),
                bankId,
                first(body, "MessageReference", "messageReference", "PaymentRef", "paymentRef"),
                first(body, "TransactionDate", "transactionDate", "PostingDate", "postingDate",
                        "ValueDate", "valueDate"),
                first(body, "Amount", "amount", "TotalAmount", "PaymentAmount"),
                first(body, "Currency", "currency", "TransactionCurrency"),
                /*
                 * What the payer quoted, which is what a payment is matched on.
                 *
                 * The message reference first, because a prompt we started comes back carrying the one we
                 * sent and that is an exact answer. Otherwise what the narration's shape says the payer
                 * typed — the account reference after the hash on a paybill, the reference on a prompt.
                 * A plain M-Pesa credit carries nothing the payer typed, and the receipt is not it: the
                 * receipt is the bank reference, and a receipt fed to the matcher would be tried as a code.
                 */
                firstNotBlank(
                        first(body, "MessageReference", "messageReference"),
                        line.quoted(),
                        line.known() ? null : first(body, "AccountNumber", "accountNumber")),
                // The name where the shape puts it; the last part for a line we do not recognise.
                firstNotBlank(first(body, "CustomerName", "customerName"), line.customerName(),
                        line.known() ? null : last(narration)),
                firstNotBlank(first(body, "MobileNumber", "mobileNumber", "PhoneNumber"),
                        phone(line.phone()), phoneIn(narration)),
                // Which of our accounts it landed in.
                first(body, "AcctNo", "acctNo", "AccountNumber", "accountNumber"),
                // The account the payer named on a paybill, which may be the one we registered.
                line.format() == CoopNarration.Format.C2B_ACCOUNT ? line.account() : null,
                firstNotBlank(first(body, "EventType", "eventType"), CREDIT));
    }

    /** Whether this notification is money arriving at all. */
    /**
     * Whether this is the answer to a prompt we started, rather than a credit landing in an account.
     *
     * <p>Two envelopes, because nobody has a specimen from Co-op: their own — {@code MessageReference}
     * with a {@code MessageCode} — and M-Pesa's, which Co-op resells underneath and which arrives as
     * {@code Body.stkCallback}. A credit notification carries neither; it carries an account and an
     * amount. A callback treated as a credit would credit a cancelled prompt on its reference alone.
     */
    public static boolean isCallback(Map<String, Object> body) {
        if (body == null) return false;
        if (body.get("Body") instanceof Map<?, ?> wrapper && wrapper.get("stkCallback") instanceof Map<?, ?>) {
            return true;
        }
        boolean hasCode = first(body, "MessageCode", "messageCode") != null;
        boolean hasCredit = first(body, "AcctNo", "acctNo", "Amount", "amount") != null;
        return hasCode && !hasCredit;
    }

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

    /** A phone the shape pointed at, normalised the same way as one found by shape; null when it is not one. */
    private static String phone(String part) {
        return part == null ? null : phoneIn(List.of(part));
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
