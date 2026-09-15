package com.hodi.modules.developments;

import tools.jackson.databind.ObjectMapper;
import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.developments.DevelopmentDtos.SaveDevelopmentRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoints, over HTTP.
 *
 * <p>What the service tests cannot reach: whether a path is mapped where the frontend will look for it,
 * whether {@code @PreAuthorize} actually refuses a caller without the authority, and — the part worth the most
 * — whether the public surface really withholds what its response record was designed to withhold. A field
 * that is absent from a record cannot leak, but a controller pointed at the wrong record can, and only a
 * request proves which one is wired up.
 */
@SpringBootTest
@Transactional
class DevelopmentApiIT {

    /*
     * MockMvc is built from the context by hand rather than injected.
     *
     * Spring Boot 4 restructured the test autoconfiguration and @AutoConfigureMockMvc is not on this
     * project's classpath, so the standard builder is used instead. springSecurity() is what puts the filter
     * chain in front of the controllers — without it @PreAuthorize is never consulted and every one of the
     * authorisation assertions below would pass for the wrong reason.
     */
    private MockMvc mvc;

    @Autowired WebApplicationContext context;

    @BeforeEach
    void buildMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
    @Autowired ObjectMapper json;
    @Autowired DevelopmentRepository developments;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId() {
        return jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
    }

    /** A principal with exactly the authorities named, so a refusal can be attributed to one missing code. */
    private UserPrincipal seller(Set<String> authorities) {
        Long tenantId = tenantId();
        User user = User.builder().id(1L).username("api-seller").password("x")
                .email("a@example.invalid").firstName("Api").lastName("Seller")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        return UserPrincipal.of(user, profile, authorities, List.of(tenantId), false, true);
    }

    /**
     * The token the filter chain sees.
     *
     * <p>The authorities come from the principal's own {@code getAuthorities()} rather than being rebuilt
     * here — that is the list {@code @PreAuthorize} consults in production, and reconstructing it in the test
     * would prove that my reconstruction works rather than that the principal's does.
     */
    private UsernamePasswordAuthenticationToken as(UserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private String body(String name, String town) throws Exception {
        return json.writeValueAsString(new SaveDevelopmentRequest(
                name, "Two hundred units.", "APARTMENT", AppConstant.DEV_PURPOSE_FOR_SALE,
                "Acacia Builders", null, null, null, "Nairobi", town, "Kilimani", "Off Argwings Kodhek",
                null, null, 200, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("the list is mapped where the frontend will look for it")
    void listIsMapped() throws Exception {
        mvc.perform(get("/api/v1/developments/list")
                        .with(authentication(as(seller(Set.of("DEVELOPMENTS_VIEW"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").exists());
    }

    @Test
    @DisplayName("a caller without the authority is refused, so the annotation is doing something")
    void authorityIsEnforced() throws Exception {
        mvc.perform(get("/api/v1/developments/list")
                        .with(authentication(as(seller(Set.of("PROPERTIES_VIEW"))))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/developments/create")
                        .with(authentication(as(seller(Set.of("DEVELOPMENTS_VIEW")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Unauthorised Heights", "Nairobi")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("creating over HTTP returns the reference the frontend needs")
    void createOverHttp() throws Exception {
        mvc.perform(post("/api/v1/developments/create")
                        .with(authentication(as(seller(Set.of("DEVELOPMENTS_CREATE")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HTTP Heights", "Nairobi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reference").exists())
                .andExpect(jsonPath("$.data.listingState").value(AppConstant.LISTING_DRAFT));
    }

    @Test
    @DisplayName("a blank name is refused as a field error rather than a 500")
    void validationIsWired() throws Exception {
        String invalid = json.writeValueAsString(new SaveDevelopmentRequest(
                "  ", null, "APARTMENT", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null));
        mvc.perform(post("/api/v1/developments/create")
                        .with(authentication(as(seller(Set.of("DEVELOPMENTS_CREATE")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    @DisplayName("the public search needs no authentication and shows only live developments")
    void publicSearchShowsOnlyLive() throws Exception {
        Long tenantId = tenantId();
        Development draft = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Draft Never Shown").developmentType("APARTMENT")
                .listingState(AppConstant.LISTING_DRAFT).build());
        Development privateProject = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId)
                .name("Bank Tracked Project").developmentType("APARTMENT")
                .listingState(AppConstant.DEV_STATE_PRIVATE).build());
        Development live = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Live Gardens").developmentType("APARTMENT").town("Nairobi")
                .listingState(AppConstant.LISTING_LIVE)
                .publishedAt(java.time.OffsetDateTime.now()).build());

        // No authentication at all.
        String response = mvc.perform(get("/api/v1/public/developments/search").param("size", "200"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(response.contains(live.getReference()),
                "a live development is on the marketplace");
        org.junit.jupiter.api.Assertions.assertFalse(response.contains(draft.getReference()),
                "a draft is not");
        org.junit.jupiter.api.Assertions.assertFalse(response.contains(privateProject.getReference()),
                "and a bank's tracked project certainly is not");
    }

    @Test
    @DisplayName("the public detail of a tracked project is a 404, not a redacted page")
    void privateProjectIsNotFoundPublicly() throws Exception {
        Long tenantId = tenantId();
        Development privateProject = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId)
                .name("Borrower Block").developmentType("APARTMENT")
                .listingState(AppConstant.DEV_STATE_PRIVATE).build());

        mvc.perform(get("/api/v1/public/developments/" + privateProject.getReference()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the public response carries no budget, facility or address line")
    void publicResponseWithholdsTheFigures() throws Exception {
        Long tenantId = tenantId();
        Development live = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Transparent Court").developmentType("APARTMENT").town("Nairobi")
                .addressLine("14 Secret Lane")
                .budgetAmount(new java.math.BigDecimal("480000000"))
                .facilityReference("FAC-2026-001")
                .facilityAmount(new java.math.BigDecimal("300000000"))
                .listingState(AppConstant.LISTING_LIVE)
                .publishedAt(java.time.OffsetDateTime.now()).build());

        String response = mvc.perform(get("/api/v1/public/developments/" + live.getReference()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(response.contains("480000000"),
                "the budget is the owner's business");
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("FAC-2026-001"),
                "so is the facility it is borrowed against");
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("Secret Lane"),
                "an unbuilt site with a published street address is an invitation");
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("Transparent Court"));
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("Nairobi"));
    }
}
