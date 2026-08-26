package com.hodi.infra.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rule the client got wrong for two months: <strong>the envelope decides whether a message was
 * sent, not the HTTP status</strong>.
 *
 * <p>The bodies below are the samples published at {@code https://notify.qnex.io/documentation}, copied
 * rather than paraphrased. Both documented failures — an insufficient unit balance and a rejected email —
 * arrive inside an otherwise ordinary 2xx response, so a client that reads only the transport records them
 * as delivered and drops the message with nothing left to show it happened. That is what this test exists to
 * keep from coming back.
 *
 * <p>By reflection into the private method, and no Spring context: the parsing is the whole subject, and it
 * should be provable without an HTTP server or an API key. The alternative — a stubbed gateway driving
 * {@link NotifyClient#sendSms} — would exercise the same three lines through a great deal more machinery,
 * and would need configuration this test has no business knowing about.
 */
class NotifyEnvelopeTest {

    private static NotifyResult interpret(String body) {
        try {
            Method m = NotifyClient.class.getDeclaredMethod("interpret", String.class);
            m.setAccessible(true);
            return (NotifyResult) m.invoke(null, body);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("NotifyClient.interpret(String) is gone or has changed shape", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("interpret threw — it is meant to answer, never throw", e.getCause());
        }
    }

    // ── the successes ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an SMS success has no id of its own, so one is generated")
    void smsSuccess() {
        NotifyResult result = interpret("""
                {"status": "00", "message": "Message Sent", "data": null}""");
        assertTrue(result.success());
        assertNotNull(result.correlationId());
        assertFalse(result.correlationId().isBlank(), "a success must be traceable to something");
    }

    @Test
    @DisplayName("an email success is tracked by the service's own id, not a local one")
    void emailSuccessCarriesTheProviderId() {
        NotifyResult result = interpret("""
                {"status": "00", "message": "Email sent successfully", "data": {
                   "id": "Qb7K2x", "subject": "Order Confirmation", "email": "customer@example.com",
                   "from": "billing@acme.com", "sent": true, "status": 1, "attachmentCount": 2}}""");
        assertTrue(result.success());
        assertEquals("Qb7K2x", result.correlationId(),
                "the id support can trace is the service's, not one we invented");
    }

    @Test
    @DisplayName("\"0\" is documented alongside \"00\"")
    void singleZeroIsAlsoSuccess() {
        assertTrue(interpret("{\"status\": \"0\", \"message\": \"Sent\", \"data\": null}").success());
    }

    // ── the failures that used to read as successes ────────────────────────────

    @Test
    @DisplayName("an insufficient balance is a failure, and says so with the code to act on")
    void insufficientBalance() {
        NotifyResult result = interpret("""
                {"status": "01", "message": "You have insufficient Unit balance"}""");
        assertFalse(result.success(), "01 is an error code, whatever the HTTP status was");
        assertFalse(result.skipped(), "the gateway declining is not the same as a channel switched off");
        assertTrue(result.error().startsWith("01"), result.error());
        assertTrue(result.error().contains("insufficient Unit balance"), result.error());
    }

    @Test
    @DisplayName("a rejected email is a failure")
    void emailSendFailed() {
        NotifyResult result = interpret("""
                {"status": "EMAIL_SEND_FAILED", "message": "Failed to send email", "data": {
                   "id": "Qb7K2x", "subject": "Order Confirmation", "email": "customer@example.com",
                   "from": "billing@acme.com", "sent": false, "status": 2, "attachmentCount": 0}}""");
        assertFalse(result.success());
        assertTrue(result.error().startsWith("EMAIL_SEND_FAILED"), result.error());
    }

    @Test
    @DisplayName("a success code over sent:false believes the flag")
    void contradictionBelievesTheFlag() {
        NotifyResult result = interpret("""
                {"status": "00", "message": "Email sent successfully", "data": {"id": "Qb7K2x", "sent": false}}""");
        assertFalse(result.success(), "the flag is set from the outcome of the send itself");
    }

    // ── everything that is not the documented envelope ────────────────────────

    @Test
    @DisplayName("a body that is not the envelope is a failure, not an assumed success")
    void unparseableBodies() {
        for (String body : new String[] {null, "", "  ", "<html>502 Bad Gateway</html>", "[]", "\"ok\"", "{"}) {
            NotifyResult result = interpret(body);
            assertFalse(result.success(), "should not claim delivery for: " + body);
            assertEquals("BAD_ENVELOPE", result.error(), "for: " + body);
        }
    }

    @Test
    @DisplayName("an envelope with no status field is not an envelope")
    void missingStatus() {
        NotifyResult result = interpret("{\"message\": \"Message Sent\"}");
        assertFalse(result.success());
        assertEquals("BAD_ENVELOPE", result.error());
    }
}
