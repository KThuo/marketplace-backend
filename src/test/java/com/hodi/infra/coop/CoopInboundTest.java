package com.hodi.infra.coop;

import com.hodi.infra.coop.CoopIpnDtos.IpnPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading Co-op's real notification.
 *
 * <p>Pinned against the bank's own Postman collection, verbatim, because the previous shape was invented
 * here and matched nothing they send — every field arrived null and every notification was stored quoting
 * nothing. A test built from our own assumptions would have passed just as happily.
 */
class CoopInboundTest {

    /** Exactly the body in Co-op's collection, field for field. */
    private static Map<String, Object> theBanksOwnExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("AcctNo", "01120000568900");
        body.put("Amount", "20.0");
        body.put("BookedBalance", "2555021.06");
        body.put("ClearedBalance", "2553021.06");
        body.put("Currency", "KES");
        body.put("CustMemoLine1", "TIP6V5IRAD~254707919065~0");
        body.put("CustMemoLine2", "1120000568900~MPESAC2B_40");
        body.put("CustMemoLine3", "0200~MELVIN WANJIKU");
        body.put("EventType", "CREDIT");
        body.put("ExchangeRate", "");
        body.put("Narration",
                "TIP6V5IRAG~254707919065~01120000568900~MPESAC2B_400200~MELVIN WANJIKU");
        body.put("PaymentRef", "25092025_409511749");
        body.put("PostingDate", "2025-09-25");
        body.put("ValueDate", "2025-09-25");
        body.put("TransactionDate", "2025-09-25T08:01:01");
        body.put("TransactionId", "CB0089060_25092025_23");
        return body;
    }

    @Test
    @DisplayName("every field we rely on is found in the bank's own example")
    void theBanksExampleParses() {
        IpnPayload parsed = CoopInbound.parse(theBanksOwnExample());

        assertEquals("CB0089060_25092025_23", parsed.refNo(),
                "their unique handle, which is what a retry repeats and deduplication turns on");
        assertEquals("01120000568900", parsed.accountIdentifier(), "which of our accounts it landed in");
        assertEquals("20.0", parsed.amount());
        assertEquals("KES", parsed.currency());
        assertEquals("CREDIT", parsed.transType());
    }

    @Test
    @DisplayName("the payer comes out of the narration, which is the only place Co-op puts them")
    void thePayerIsReadFromTheNarration() {
        IpnPayload parsed = CoopInbound.parse(theBanksOwnExample());

        assertEquals("+254707919065", parsed.phoneNo(),
                "found by shape, not position — the same line carries an account number of similar length");
        assertEquals("MELVIN WANJIKU", parsed.customerName());
        assertEquals("TIP6V5IRAG", parsed.reference(),
                "the payer's own receipt, which is what they would quote");
    }

    @Test
    @DisplayName("a prompt we started is answered by the reference we sent, which wins over the narration")
    void aPromptIsMatchedOnOurOwnReference() {
        Map<String, Object> body = theBanksOwnExample();
        body.put("MessageReference", "IN2609187KQX");

        IpnPayload parsed = CoopInbound.parse(body);

        assertEquals("IN2609187KQX", parsed.reference(),
                "an exact answer beats a guess read off a narration");
    }

    @Test
    @DisplayName("a debit on the same account is not money arriving")
    void aDebitIsNotACredit() {
        Map<String, Object> body = theBanksOwnExample();
        body.put("EventType", "DEBIT");
        assertFalse(CoopInbound.isCredit(body));

        // Absent means credit: an inbound notification channel carries nothing else, and refusing one
        // for a missing field would discard money already in the bank.
        body.remove("EventType");
        assertTrue(CoopInbound.isCredit(body));
    }

    @Test
    @DisplayName("an empty or unrecognisable body does not throw")
    void nothingBreaksOnRubbish() {
        assertDoesNotThrow(() -> CoopInbound.parse(null));
        assertDoesNotThrow(() -> CoopInbound.parse(Map.of()));
        assertNull(CoopInbound.parse(Map.of()).refNo());
    }
}
