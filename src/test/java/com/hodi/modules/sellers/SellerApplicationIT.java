package com.hodi.modules.sellers;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.sellers.SellerDtos.ApplyRequest;
import com.hodi.modules.sellers.SellerDtos.DecisionRequest;
import com.hodi.modules.sellers.SellerDtos.SaveApplicationRequest;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seller applies, finishes signed in, and the bank decides.
 *
 * <p>What is worth testing is not that the rows save — it is the two properties the whole design rests on:
 * that registering grants nothing, and that approval is the only thing that creates an organisation. Both
 * are easy to break later by a well-meaning convenience, and neither would be noticed until somebody
 * unapproved had a listing on the marketplace.
 */
@SpringBootTest
@Transactional
class SellerApplicationIT {

    @Autowired SellerApplicationService service;
    @Autowired SellerApplicationRepository applications;
    @Autowired SellerIdentityCheckRepository checks;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ApplyRequest applicant(String handle, Boolean coop, String account) {
        return new ApplyRequest("Ada", "Wanjiru", handle + "@example.invalid", "+254700111222",
                "Str0ng#Pass1", coop, account);
    }

    /** Signs in as the applicant, which is how they finish it. */
    private void signInAs(String email) {
        User user = users.findByEmail(email).orElseThrow();
        UserProfile profile = profiles.findLiveForUser(user.getId()).stream().findFirst().orElseThrow();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(), List.of(), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void signInAsBank() {
        User user = User.builder().id(8801L).username("bank-reviewer").password("x")
                .email("reviewer@example.invalid").firstName("Pat").lastName("Platform")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(8801L).userId(8801L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("BANK_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("SELLERS_VIEW", "SELLERS_DECIDE"), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /**
     * Fills the whole thing in, documents included.
     *
     * <p>The documents are half of what submit checks, and a helper that skipped them would make every
     * test below assert on an application no real applicant could have sent.
     */
    private void complete(String email) {
        signInAs(email);
        service.save(new SaveApplicationRequest("12345678", "A001234567X", "Wanjiru Properties",
                AppConstant.SELLER_COMPANY, "PVT-99", "Nairobi", "Nairobi", "Kilimani Road"));
        for (var required : service.requiredDocuments()) {
            if (required.required()) upload(required.code());
        }
    }

    private void upload(String code) {
        service.uploadDocument(code, new MockMultipartFile(
                "file", code.toLowerCase() + ".pdf", "application/pdf",
                ("a scan of the " + code).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    // ── applying ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("applying creates an account that can sign in and cannot sell")
    void applyingGrantsNothing() {
        var outcome = service.apply(applicant("ada.applies", false, null));

        assertEquals(SellerState.DRAFT, outcome.state());
        assertNotNull(outcome.username());

        User created = users.findByEmail("ada.applies@example.invalid").orElseThrow();
        assertTrue(created.isEnabled(), "they have to sign in to finish the application");

        UserProfile profile = profiles.findLiveForUser(created.getId()).stream().findFirst().orElseThrow();
        assertNull(profile.getTenantId(), "there is nowhere to put a listing until the bank approves");
        assertNull(profile.getUserGroupId(), "no group means no permissions resolve at all");
        assertEquals(AppConstant.KYC_PENDING, profile.getKycStatus(),
                "PENDING is what fails the listing gate; NOT_REQUIRED would clear it");
    }

    @Test
    @DisplayName("a second application for the same address is refused rather than silently started")
    void oneAccountPerAddress() {
        service.apply(applicant("ada.twice", false, null));
        assertThrows(RuntimeException.class, () -> service.apply(applicant("ada.twice", false, null)));
    }

    // ── the checks ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("without a Co-op account they are screened, and the stubs say so rather than passing")
    void screeningIsRecordedAsPending() {
        var outcome = service.apply(applicant("ada.screened", false, null));
        var application = applications.findByReference(outcome.reference()).orElseThrow();

        var ran = checks.findByApplicationIdOrderByRanAtAsc(application.getId());
        assertEquals(2, ran.size(), "AML and IPRS both ran");
        for (var check : ran) {
            assertEquals(SellerState.VERDICT_PENDING_INTEGRATION, check.getVerdict(),
                    "a stub must never report PASS — see SellerState");
            assertNotNull(check.getDetail(), "the reviewer is told why nothing happened");
        }
        assertEquals(SellerState.SOURCE_SELF, application.getIdentitySource());
    }

    @Test
    @DisplayName("a Co-op account is attempted, and an unverified one falls back to screening")
    void coopAccountFallsBackWhileItIsAStub() {
        var outcome = service.apply(applicant("ada.coop", true, "01100234567800"));
        var application = applications.findByReference(outcome.reference()).orElseThrow();

        var ran = checks.findByApplicationIdOrderByRanAtAsc(application.getId());
        assertEquals(3, ran.size(), "the account attempt, then AML and IPRS because it did not verify");
        assertEquals(SellerState.CHECK_COOP, ran.get(0).getCheckCode());
        assertFalse(application.isCoopAccountVerified());
        // Unverified means the bank has not identified them, so they are screened like anybody else.
        assertEquals(SellerState.SOURCE_SELF, application.getIdentitySource());
    }

    // ── finishing it ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an incomplete application says what is missing rather than being refused vaguely")
    void submitNamesWhatIsMissing() {
        var outcome = service.apply(applicant("ada.partial", false, null));
        signInAs("ada.partial@example.invalid");

        var refused = assertThrows(HodiException.class, () -> service.submit());
        assertTrue(refused.getMessage().contains("ID number"), refused.getMessage());
        assertEquals(SellerState.DRAFT,
                applications.findByReference(outcome.reference()).orElseThrow().getState());
    }

    @Test
    @DisplayName("a completed application goes to the bank")
    void submitHandsItOver() {
        var outcome = service.apply(applicant("ada.complete", false, null));
        complete("ada.complete@example.invalid");

        var response = service.submit();

        assertEquals(SellerState.SUBMITTED, response.state());
        assertNotNull(response.submittedAt());
        assertTrue(response.outstanding().isEmpty());
    }

    @Test
    @DisplayName("the checklist comes from the seller type, and submit insists on it")
    void documentsAreRequiredBeforeSubmitting() {
        service.apply(applicant("ada.docs", false, null));
        signInAs("ada.docs@example.invalid");

        // Nothing to ask for until they have said what kind of seller they are.
        assertTrue(service.requiredDocuments().isEmpty());

        service.save(new SaveApplicationRequest("12345678", null, "Wanjiru Properties",
                AppConstant.SELLER_COMPANY, null, "Nairobi", null, null));
        var checklist = service.requiredDocuments();
        assertFalse(checklist.isEmpty(), "a company has documents to produce");
        assertTrue(checklist.stream().noneMatch(d -> d.uploaded()));

        var refused = assertThrows(HodiException.class, () -> service.submit());
        assertTrue(refused.getMessage().length() > 20, refused.getMessage());

        for (var required : checklist) {
            if (required.required()) upload(required.code());
        }
        assertEquals(SellerState.SUBMITTED, service.submit().state());
    }

    @Test
    @DisplayName("re-uploading replaces the line rather than adding a second answer to it")
    void reuploadReplaces() {
        service.apply(applicant("ada.replace", false, null));
        signInAs("ada.replace@example.invalid");
        service.save(new SaveApplicationRequest("12345678", null, "Wanjiru Properties",
                AppConstant.SELLER_COMPANY, null, "Nairobi", null, null));

        String code = service.requiredDocuments().get(0).code();
        upload(code);
        upload(code);

        long live = service.requiredDocuments().stream().filter(d -> d.code().equals(code)).count();
        assertEquals(1, live, "one line, one answer — a reviewer should not be choosing between two");
    }

    // ── the decision ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("approval is what creates the organisation and lets them list")
    void approvalCreatesTheOrganisation() {
        var outcome = service.apply(applicant("ada.approved", false, null));
        complete("ada.approved@example.invalid");
        service.submit();

        signInAsBank();
        var decided = service.decide(outcome.reference(), new DecisionRequest("APPROVED", "Looks right."));

        assertEquals(SellerState.APPROVED, decided.state());
        assertNotNull(decided.tenantId(), "the organisation exists only now");

        User seller = users.findByEmail("ada.approved@example.invalid").orElseThrow();
        UserProfile profile = profiles.findLiveForUser(seller.getId()).stream().findFirst().orElseThrow();
        assertNotNull(profile.getTenantId());
        assertNotNull(profile.getUserGroupId(), "they are in their own organisation's owner group");
        assertEquals(AppConstant.KYC_APPROVED, profile.getKycStatus(),
                "an approved seller who still could not list would be the gate holding against a "
                        + "decision that has been made");
    }

    @Test
    @DisplayName("a rejection leaves them with an account and no way to sell")
    void rejectionCreatesNothing() {
        var outcome = service.apply(applicant("ada.refused", false, null));
        complete("ada.refused@example.invalid");
        service.submit();

        signInAsBank();
        var decided = service.decide(outcome.reference(),
                new DecisionRequest("REJECTED", "Documents do not match the company named."));

        assertEquals(SellerState.REJECTED, decided.state());
        assertNull(decided.tenantId());

        User refused = users.findByEmail("ada.refused@example.invalid").orElseThrow();
        UserProfile profile = profiles.findLiveForUser(refused.getId()).stream().findFirst().orElseThrow();
        assertNull(profile.getTenantId());
        assertEquals(AppConstant.KYC_PENDING, profile.getKycStatus());
    }

    @Test
    @DisplayName("asking for more hands it back without deciding it")
    void moreInfoReturnsItToTheApplicant() {
        var outcome = service.apply(applicant("ada.more", false, null));
        complete("ada.more@example.invalid");
        service.submit();

        signInAsBank();
        service.decide(outcome.reference(), new DecisionRequest("MORE_INFO", "Send the CR12."));

        var application = applications.findByReference(outcome.reference()).orElseThrow();
        assertEquals(SellerState.MORE_INFO, application.getState());
        assertNull(application.getDecidedAt(), "nothing has been decided yet");
        assertTrue(application.isOpenToApplicant(), "it is theirs to change again");

        // And they can: a send-back that left it read-only would be a dead end.
        signInAs("ada.more@example.invalid");
        service.save(new SaveApplicationRequest("12345678", "A001234567X", "Wanjiru Properties Ltd",
                AppConstant.SELLER_COMPANY, "PVT-99", "Nairobi", "Nairobi", "Kilimani Road"));
        assertEquals(SellerState.SUBMITTED, service.submit().state());
    }

    @Test
    @DisplayName("a refusal without a reason is refused")
    void refusingNeedsAReason() {
        var outcome = service.apply(applicant("ada.noreason", false, null));
        complete("ada.noreason@example.invalid");
        service.submit();

        signInAsBank();
        assertThrows(HodiException.class, () ->
                service.decide(outcome.reference(), new DecisionRequest("REJECTED", "  ")));
    }

    @Test
    @DisplayName("only a submitted application can be decided")
    void draftsAreNotDecidable() {
        var outcome = service.apply(applicant("ada.draft", false, null));

        signInAsBank();
        assertThrows(HodiException.class, () ->
                service.decide(outcome.reference(), new DecisionRequest("APPROVED", null)));
    }
}
