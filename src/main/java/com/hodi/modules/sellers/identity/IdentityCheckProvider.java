package com.hodi.modules.sellers.identity;

/**
 * What an outside service has to supply for an application to be checked against it.
 *
 * <p>Three of these exist and none of them is real yet: Co-op account validation, AML screening and an
 * IPRS lookup. The interface is here anyway, and the stubs implement it, so that wiring a provider in is
 * a class and two configuration rows rather than a migration and a refactor — the same bet
 * {@code OCP_BASE_URL} already makes for the credit service.
 *
 * <p>Implementations are found by {@link #code}, so a real provider replaces a stub by being a bean with
 * the same code and a higher {@code @Order}. Nothing that calls a check knows which kind it got.
 */
public interface IdentityCheckProvider {

    /** {@code COOP_ACCOUNT}, {@code AML} or {@code IPRS} — see {@code SellerState}. */
    String code();

    /** False while this is a stub, which is what lets the caller tell an applicant why nothing happened. */
    default boolean configured() {
        return false;
    }

    CheckResult run(CheckRequest request);

    /**
     * What is known about the applicant at the moment of asking.
     *
     * <p>One shape for all three, because two of them want a name and a document number and the third
     * wants an account number, and a request record per provider would be three records differing by one
     * field each.
     */
    record CheckRequest(String fullName, String email, String phone,
                        String idNumber, String kraPin, String accountNumber) {}

    /**
     * What came back.
     *
     * @param verdict one of {@code SellerState}'s verdicts. A stub returns
     *                {@code PENDING_INTEGRATION} and never {@code PASS} — see that class for why.
     * @param detail  shown to the reviewer verbatim, so it has to read as a sentence rather than a code
     * @param person  what the provider knows about them, when it knows anything. Only the Co-op check
     *                fills this, and it is the whole point of that check: the bank already holds these
     *                details and the applicant should not be retyping them.
     */
    record CheckResult(String verdict, String providerRef, String detail, KnownPerson person) {

        public static CheckResult pendingIntegration(String what) {
            return new CheckResult(com.hodi.modules.sellers.SellerState.VERDICT_PENDING_INTEGRATION,
                    null, what + " is not connected yet, so this was not checked.", null);
        }
    }

    record KnownPerson(String fullName, String email, String phone, String idNumber, String kraPin) {}
}
