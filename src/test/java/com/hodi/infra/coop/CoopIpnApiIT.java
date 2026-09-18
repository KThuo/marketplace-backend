package com.hodi.infra.coop;

import com.hodi.common.util.RrnGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint as Co-op sees it: no session, its own response shape, and 200 for anything we stored.
 *
 * <p>Worth testing over HTTP rather than through the service, because everything that matters here is in the
 * layers around the method — the security chain letting an unauthenticated POST through, and the response body
 * carrying {@code statusCode} rather than the platform's envelope. Co-op reads that field and retries on
 * anything non-zero, so an envelope would turn every notification into an infinite redelivery.
 *
 * <p>Not {@code @Transactional}: the handler is {@code REQUIRES_NEW} and commits, so the rows are removed
 * afterwards instead.
 */
@SpringBootTest
class CoopIpnApiIT {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private String refNo;

    @BeforeEach
    void setUp() {
        // Built from the context because Boot 4 no longer offers @AutoConfigureMockMvc on this classpath.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        refNo = RrnGenerator.generate("RF");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from coop_statements where ref_no = ? or ref_no like 'UNKNOWN-%'", refNo);
    }

    /**
     * Co-op's own shape, from the bank's Postman collection.
     *
     * <p>Not the shape this codebase invented. That one — {@code refNo}, {@code accountIdentifier},
     * {@code phoneNo} — matched nothing Co-op sends, so every real notification bound to an empty record
     * and was stored quoting nothing. A test written against our own names passed throughout.
     */
    private String body(String transactionId) {
        return """
                {
                  "AcctNo": "not-a-till-of-ours",
                  "Amount": "500.00",
                  "Currency": "KES",
                  "EventType": "CREDIT",
                  "Narration": "TIPTEST~254700000000~not-a-till-of-ours~MPESAC2B~WALK IN",
                  "PaymentRef": "25092026_TEST",
                  "PostingDate": "2026-08-27",
                  "TransactionDate": "2026-08-27T10:30:00",
                  "TransactionId": "%s"
                }
                """.formatted(transactionId);
    }

    @Test
    @DisplayName("an unauthenticated notification is accepted and answered in Co-op's own shape")
    void acceptsWithoutASession() throws Exception {
        mvc.perform(post("/api/v1/public/coop/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(refNo)))
                .andExpect(status().isOk())
                /*
                 * Zero even though nothing could be placed — the till is not ours and the reference matches
                 * nothing. A retry would deliver the same wrong reference again while giving us another chance
                 * to double-post, so this is a success as far as Co-op is concerned.
                 */
                .andExpect(jsonPath("$.statusCode").value(0))
                .andExpect(jsonPath("$.transactionID").exists())
                .andExpect(jsonPath("$.statusMessage").value("Notification received"))
                // And no platform envelope: Co-op reads statusCode at the top level.
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("a field we have never seen does not refuse the payment")
    void unknownFieldsAreIgnored() throws Exception {
        /*
         * Co-op adding a field must not stop money arriving. Boot leaves FAIL_ON_UNKNOWN_PROPERTIES off, and
         * this test is what says so out loud — the alternative is every notification 400ing on the day they
         * extend their payload, and every one of them retrying.
         */
        String extended = body(refNo).replace("\"EventType\": \"CREDIT\"",
                "\"transType\": \"BUNI_IPN_TILL\", \"settlementBatch\": \"SB-99\"");

        mvc.perform(post("/api/v1/public/coop/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extended))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(0));
    }

    @Test
    @DisplayName("the same delivery twice answers with the same reference both times")
    void retryEchoesTheFirstReference() throws Exception {
        String first = mvc.perform(post("/api/v1/public/coop/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(refNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String again = mvc.perform(post("/api/v1/public/coop/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(refNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(first, again,
                "Co-op stores our transactionID as its RRN, so a retry must not be told a different one");

        Integer rows = jdbc.queryForObject(
                "select count(*) from coop_statements where ref_no = ?", Integer.class, refNo);
        org.junit.jupiter.api.Assertions.assertEquals(1, rows);
    }

    @Test
    @DisplayName("a notification with no reference of its own is still stored rather than lost")
    void missingRefNoIsStored() throws Exception {
        // No TransactionId and no PaymentRef: nothing Co-op sends that we could deduplicate on.
        String noRef = body("x")
                .replace("\"TransactionId\": \"x\"", "\"TransactionId\": \"\"")
                .replace("\"PaymentRef\": \"25092026_TEST\",", "");

        mvc.perform(post("/api/v1/public/coop/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(0));

        // Given a placeholder key rather than dropped: money that arrived without an identifier is still money
        // that arrived, and a person can match it from the amount and the phone number.
        Integer rows = jdbc.queryForObject(
                "select count(*) from coop_statements where ref_no like 'UNKNOWN-%'", Integer.class);
        org.junit.jupiter.api.Assertions.assertTrue(rows >= 1);
    }
}
