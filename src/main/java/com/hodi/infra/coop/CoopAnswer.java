package com.hodi.infra.coop;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading what Co-op said about a payment.
 *
 * <h2>Three outcomes, and the third is the important one</h2>
 *
 * <p>Success and failure are obvious. {@link Outcome#PENDING} is everything else, and everything else is a
 * lot: a still-processing code, a code we do not recognise, a blank code, a transport error, no answer at
 * all. All of them mean "ask again", never "it failed".
 *
 * <p>That asymmetry is deliberate and it is the whole point of this class. A payment marked failed on a
 * code nobody had seen before is a customer told their money did not arrive when it did — and the money is
 * already in the bank. The reverse mistake, leaving something pending that actually failed, costs a person
 * thirty seconds of reading a queue.
 *
 * <p>Pure and static so it can be tested on its answers rather than on a bank being available.
 */
public final class CoopAnswer {

    private CoopAnswer() {}

    public enum Outcome {
        /** The bank said it worked. */
        SUCCESS,
        /** The bank said it did not — with a code, which is what makes this different from silence. */
        FAILED,
        /** Anything else. Ask again; never conclude from it. */
        PENDING
    }

    /** Co-op's success marker, in the body rather than the HTTP status — the two disagree routinely. */
    private static final String OK = "0";

    /**
     * @param response what came back, or null when nothing did
     * @param pendingCodes  {@code MessageCode} values configured as still-processing
     * @param pendingDescriptions {@code MessageDescription} values configured as still-processing
     */
    public static Outcome read(Map<String, Object> response,
                               Set<String> pendingCodes, Set<String> pendingDescriptions) {
        if (response == null) return Outcome.PENDING;

        String code = text(response.get("MessageCode"));
        String description = text(response.get("MessageDescription"));

        if (OK.equals(code)) return Outcome.SUCCESS;
        if (matches(pendingCodes, code) || matches(pendingDescriptions, description)) {
            return Outcome.PENDING;
        }
        // No code at all is not a failure: it is an answer we cannot read, which is a reason to ask again.
        if (code.isEmpty()) return Outcome.PENDING;
        return Outcome.FAILED;
    }

    /** What Co-op said, for the sentence a person reads. Never null. */
    public static String description(Map<String, Object> response) {
        if (response == null) return "no answer";
        String description = text(response.get("MessageDescription"));
        if (!description.isEmpty()) return description;
        String code = text(response.get("MessageCode"));
        return code.isEmpty() ? "no answer" : "code " + code;
    }

    /**
     * The bank's own reference for the conversation, under whichever name it used.
     *
     * <p>Co-op is not consistent between the push acknowledgement and the status answer, so both spellings
     * are looked for rather than one being assumed.
     */
    public static String bankReference(Map<String, Object> response) {
        if (response == null) return null;
        for (String key : new String[]{"MessageReference", "messageReference", "TransactionID",
                "transactionId", "ThirdPartyTransID"}) {
            String value = text(response.get(key));
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    /** The receipt a customer would quote, when the answer carries one. */
    public static String receipt(Map<String, Object> response) {
        if (response == null) return null;
        for (String key : new String[]{"ReceiptNumber", "receiptNumber", "MpesaReceiptNumber",
                "TransactionReference", "CoopTransactionID"}) {
            String value = text(response.get(key));
            if (!value.isEmpty()) return value;
        }
        return receiptInMetadata(response.get("TransactionMetadata"));
    }

    /**
     * The M-Pesa receipt out of the status enquiry's {@code TransactionMetadata.Items}.
     *
     * <p>Observed, not documented: the enquiry answers with name–value items, and the receipt is the
     * second part of the one called {@code Narration}, written {@code "<description>~<receipt>~<date>"}.
     * An item plainly named a receipt is taken first, in case the bank ever adds one.
     */
    @SuppressWarnings("unchecked")
    static String receiptInMetadata(Object metadata) {
        if (!(metadata instanceof Map<?, ?> map)) return null;
        Object items = map.get("Items");
        if (!(items instanceof java.util.List<?> list)) return null;
        String fromNarration = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) continue;
            String name = text(entry.get("Name"));
            String value = text(entry.get("Value"));
            if (value.isEmpty()) continue;
            if (name.toLowerCase(Locale.ROOT).contains("receipt")) return value;
            if ("Narration".equalsIgnoreCase(name)) {
                String[] parts = value.split("~", -1);
                if (parts.length >= 2 && !parts[1].isBlank()) fromNarration = parts[1].trim();
            }
        }
        return fromNarration;
    }

    /** A configured comma-separated list, as a set. Blank means an empty set, which matches nothing. */
    public static Set<String> csv(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return Set.copyOf(out);
    }

    private static boolean matches(Set<String> configured, String value) {
        if (value.isEmpty() || configured == null) return false;
        for (String entry : configured) {
            if (entry.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** Lower-cased, for a log line that should not vary by the bank's capitalisation. */
    public static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
