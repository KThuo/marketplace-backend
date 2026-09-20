package com.hodi.infra.coop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a Co-op answer is read.
 *
 * <p>Worth pinning because the asymmetry is the whole design and nothing about it is visible at a glance:
 * almost everything that is not an explicit success or an explicit failure has to come out as PENDING. Each
 * case below is a way a payment could be wrongly failed, and a wrongly failed payment is a customer told
 * their money did not arrive while it sits in the bank.
 */
class CoopAnswerTest {

    private static final Set<String> PENDING_CODES = Set.of("S_001");
    private static final Set<String> PENDING_WORDS = Set.of("PROCESSING");

    private static CoopAnswer.Outcome read(Map<String, Object> body) {
        return CoopAnswer.read(body, PENDING_CODES, PENDING_WORDS);
    }

    @Test
    @DisplayName("code 0 is the only success, and it is read from the body not the HTTP status")
    void zeroIsSuccess() {
        assertEquals(CoopAnswer.Outcome.SUCCESS, read(Map.of("MessageCode", "0")));
        assertEquals(CoopAnswer.Outcome.SUCCESS,
                read(Map.of("MessageCode", "0", "MessageDescription", "Success")));
    }

    @Test
    @DisplayName("a configured still-processing code or description is pending, whatever its case")
    void configuredPendingIsPending() {
        assertEquals(CoopAnswer.Outcome.PENDING, read(Map.of("MessageCode", "S_001")));
        assertEquals(CoopAnswer.Outcome.PENDING, read(Map.of("MessageCode", "s_001")));
        assertEquals(CoopAnswer.Outcome.PENDING,
                read(Map.of("MessageCode", "99", "MessageDescription", "processing")));
    }

    @Test
    @DisplayName("no answer at all is pending — silence is not failure")
    void silenceIsPending() {
        assertEquals(CoopAnswer.Outcome.PENDING, read(null));
        assertEquals(CoopAnswer.Outcome.PENDING, read(Map.of()));
    }

    @Test
    @DisplayName("an answer with no code is pending, because it is unreadable rather than negative")
    void anUnreadableAnswerIsPending() {
        assertEquals(CoopAnswer.Outcome.PENDING, read(Map.of("MessageDescription", "Something happened")));
        assertEquals(CoopAnswer.Outcome.PENDING, read(Map.of("MessageCode", "  ")));
    }

    @Test
    @DisplayName("a code the bank gave and we do not recognise is a failure — they answered")
    void anUnknownCodeIsFailure() {
        assertEquals(CoopAnswer.Outcome.FAILED, read(Map.of("MessageCode", "500")));
        assertEquals(CoopAnswer.Outcome.FAILED,
                read(Map.of("MessageCode", "1032", "MessageDescription", "Request cancelled by user")));
    }

    @Test
    @DisplayName("an empty configured list matches nothing, so a deployment cannot switch failure off")
    void emptyConfigurationMatchesNothing() {
        assertEquals(CoopAnswer.Outcome.FAILED,
                CoopAnswer.read(Map.of("MessageCode", "S_001"), Set.of(), Set.of()));
        assertTrue(CoopAnswer.csv("").isEmpty());
        assertTrue(CoopAnswer.csv(null).isEmpty());
    }

    @Test
    @DisplayName("a configured list is read as a set, trimmed, ignoring blanks")
    void csvIsForgiving() {
        assertEquals(Set.of("S_001", "S_002"), CoopAnswer.csv(" S_001 , ,S_002,"));
    }

    @Test
    @DisplayName("the bank's reference and receipt are found under any of the names Co-op uses")
    void referencesAreFoundWhicheverNameTheyArrivedUnder() {
        assertEquals("ABC1", CoopAnswer.bankReference(Map.of("MessageReference", "ABC1")));
        assertEquals("ABC2", CoopAnswer.bankReference(Map.of("messageReference", "ABC2")));
        assertEquals("ABC3", CoopAnswer.bankReference(Map.of("TransactionID", "ABC3")));
        assertNull(CoopAnswer.bankReference(Map.of("Unrelated", "x")));
        assertEquals("R1", CoopAnswer.receipt(Map.of("ReceiptNumber", "R1")));
    }

    @Test
    @DisplayName("there is always a sentence to show a person")
    void thereIsAlwaysSomethingToSay() {
        assertEquals("no answer", CoopAnswer.description(null));
        assertEquals("code 7", CoopAnswer.description(Map.of("MessageCode", "7")));
        assertEquals("Insufficient funds",
                CoopAnswer.description(Map.of("MessageCode", "7", "MessageDescription", "Insufficient funds")));
    }

    @Test
    @DisplayName("the receipt is read out of the enquiry's narration item, second field, or a receipt-named item")
    void receiptIsReadOutOfTheEnquirysMetadata() {
        Map<String, Object> narrated = Map.of(
                "MessageCode", "0",
                "TransactionMetadata", Map.of("Items", java.util.List.of(
                        Map.of("Name", "Amount", "Value", "950000"),
                        Map.of("Name", "Narration", "Value", "Payment for BK1~TIP6V5IRAG~2026-09-20"))));
        assertEquals("TIP6V5IRAG", CoopAnswer.receipt(narrated),
                "observed shape: <description>~<receipt>~<date>");

        Map<String, Object> named = Map.of(
                "TransactionMetadata", Map.of("Items", java.util.List.of(
                        Map.of("Name", "MpesaReceiptNumber", "Value", "TIP6V5IRAH"),
                        Map.of("Name", "Narration", "Value", "x~WRONG~y"))));
        assertEquals("TIP6V5IRAH", CoopAnswer.receipt(named), "an item plainly named a receipt wins");

        assertNull(CoopAnswer.receipt(Map.of("MessageCode", "0")), "and nothing is invented");
        assertNull(CoopAnswer.receipt(Map.of("TransactionMetadata", Map.of("Items", java.util.List.of(
                Map.of("Name", "Narration", "Value", "no tildes here"))))));
    }
}
