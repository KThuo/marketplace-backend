package com.hodi.modules.disbursements;

import com.hodi.infra.coop.CoopAnswer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading Co-op's answer about a transfer — the status enquiry's, or the callback's, which arrives in the
 * same shape.
 *
 * <p>The envelope's {@code MessageCode} says whether the <em>request</em> was accepted. Whether the
 * <em>money</em> moved is per leg: {@code Destinations[0].ResponseCode}, {@code "0"} for done and, in the
 * one specimen the collection holds, {@code "-5"} for insufficient balance — with the envelope reading
 * {@code "2"}, "FULL FAILURE". So the leg is read first, and an answer with no legs says nothing about
 * the money and stays pending: marking a transfer succeeded on an envelope code is how a payee is told
 * they were paid when they were not.
 */
public final class CoopFtAnswer {

    private CoopFtAnswer() {}

    public record Reading(CoopAnswer.Outcome outcome, String code, String description, String transactionId) {}

    public static Reading read(Map<String, Object> response, Set<String> pendingCodes,
                               Set<String> pendingDescriptions) {
        if (response == null) return new Reading(CoopAnswer.Outcome.PENDING, null, "no answer", null);
        Map<String, Object> leg = firstLeg(response);
        String legCode = leg == null ? null : text(leg.get("ResponseCode"));
        String legText = leg == null ? null : text(leg.get("ResponseDescription"));
        String envelopeCode = text(response.get("MessageCode"));
        String envelopeText = text(response.get("MessageDescription"));
        String transactionId = leg == null ? null : transactionId(leg.get("TransactionID"));
        String description = legText != null ? legText : envelopeText != null ? envelopeText
                : legCode != null ? "code " + legCode : envelopeCode != null ? "code " + envelopeCode : "no answer";

        if (legCode != null) {
            if ("0".equals(legCode)) return new Reading(CoopAnswer.Outcome.SUCCESS, legCode, description, transactionId);
            if (matches(pendingCodes, legCode) || matches(pendingDescriptions, legText)) {
                return new Reading(CoopAnswer.Outcome.PENDING, legCode, description, transactionId);
            }
            return new Reading(CoopAnswer.Outcome.FAILED, legCode, description, transactionId);
        }
        // No leg. A failed envelope is a failure; anything else, including an accepted envelope, is not yet
        // an answer about the money.
        if (envelopeCode != null && !"0".equals(envelopeCode)
                && !matches(pendingCodes, envelopeCode) && !matches(pendingDescriptions, envelopeText)) {
            return new Reading(CoopAnswer.Outcome.FAILED, envelopeCode, description, transactionId);
        }
        return new Reading(CoopAnswer.Outcome.PENDING, envelopeCode, description, transactionId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstLeg(Map<String, Object> response) {
        Object legs = response.get("Destinations");
        if (legs instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return (Map<String, Object>) first;
        }
        return null;
    }

    /** Co-op writes the literal {@code "NULL"} for an id it has not assigned. */
    private static String transactionId(Object value) {
        String text = text(value);
        return text == null || "NULL".equalsIgnoreCase(text) ? null : text;
    }

    private static boolean matches(Set<String> configured, String value) {
        if (value == null || configured == null) return false;
        for (String entry : configured) if (entry.equalsIgnoreCase(value)) return true;
        return false;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
