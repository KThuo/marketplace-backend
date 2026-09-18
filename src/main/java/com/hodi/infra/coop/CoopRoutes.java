package com.hodi.infra.coop;

/**
 * The addresses Co-op calls this platform on.
 *
 * <p>In one place because they are quoted twice: once by the controller that serves them, and once by the
 * account screen that shows an operator what to give the bank. Written out separately in each, they drift —
 * and the way that failure shows up is a bank posting to an address that stopped existing, which looks like
 * an outage at their end.
 *
 * <p>These are ours to decide. Nothing here is typed into a form: a URL somebody types is a URL that can
 * carry a typo into the one address that cannot be wrong, and one nobody updates when a route changes.
 */
public final class CoopRoutes {

    private CoopRoutes() {}

    /** A credit landed in an account we hold. Served by {@link CoopIpnController}. */
    public static final String NOTIFICATIONS = "/api/v1/public/coop/notifications";

    /**
     * Is this bill reference real, and what is owed on it — asked before the customer is debited.
     *
     * <p>Declared now and served when the biller adapter is built. The address is a decision, not an
     * implementation: Co-op's onboarding asks for it in writing well before anything posts to it, and
     * choosing it late is how two systems end up disagreeing about where it was.
     */
    public static final String BILLER_VALIDATION = "/api/v1/public/coop/biller/validation";

    /** The debit happened. Same reasoning as above. */
    public static final String BILLER_ADVICE = "/api/v1/public/coop/biller/advice";
}
