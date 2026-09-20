package com.hodi.infra.coop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The biller's two addresses, reached the way Co-op reaches them: no session, a JSON body, an answer in
 * their envelope.
 *
 * <p>Until now these routes were shown on the account screen and answered by the security filter. What is
 * pinned here is that a bank posting to them gets HTTP 200 and Co-op's status code in the header — even
 * when the answer is "who are you" — rather than a redirect to a login page or a 403 from the filter.
 */
@SpringBootTest
class CoopBillerApiIT {

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static final String VALIDATION = """
            {
              "header": {
                "connectionID": "TEST",
                "connectionPassword": "CoopTest",
                "messageID": "api-test-1",
                "serviceName": "NOBODY ESTATE"
              },
              "request": {
                "TransactionReferenceCode": "c1",
                "TransactionDate": "2026-05-28T14:43:19.763+03:00",
                "InstitutionCode": "000000000"
              }
            }
            """;

    @Test
    @DisplayName("a validation reaches the handler without a session and is answered in Co-op's envelope")
    void validationIsPublicAndAnsweredInTheirShape() throws Exception {
        mvc.perform(post(CoopRoutes.BILLER_VALIDATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALIDATION))
                .andExpect(status().isOk())
                // No biller is registered under that institution code, so the protocol says 404 — in
                // the header, not the HTTP status, and with the message id echoed.
                .andExpect(jsonPath("$.header.statusCode").value("404"))
                .andExpect(jsonPath("$.header.messageID").value("api-test-1"))
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    @DisplayName("an advice reaches the handler too, and a body with no header is a 400 in their shape")
    void adviceIsPublic() throws Exception {
        mvc.perform(post(CoopRoutes.BILLER_ADVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\": {\"TransactionReferenceCode\": \"x\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.statusCode").value("400"))
                .andExpect(jsonPath("$.header.statusDescription").value("Missing header or body"));
    }
}
