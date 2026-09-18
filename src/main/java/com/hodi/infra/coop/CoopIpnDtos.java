package com.hodi.infra.coop;

/**
 * The one inbound shape, and the one response Co-op expects.
 *
 * <p>Every provider's raw payload is normalised by Co-op before it reaches us, so one handler serves all five
 * inbound codes. Nothing here is validated with Bean Validation annotations, and that is deliberate: a
 * rejected request makes Co-op retry, and a payload we cannot parse will be just as unparseable next time.
 * Whatever arrives is stored; what is missing becomes a reason a person can read.
 */
public final class CoopIpnDtos {

    private CoopIpnDtos() {}

    /**
     * @param refNo             Co-op's identifier, and our idempotency key
     * @param traceId           Co-op's correlation id, for chasing a payment with them
     * @param timestamp         when the provider says it happened, as a string — parsed leniently
     * @param amount            a string in the payload, not a number; parsed here
     * @param reference         what the payer typed. Often wrong, which is the point of the queue
     * @param accountIdentifier which of our tills it landed in
     * @param transType         which of the five inbound codes this is
     */
    public record IpnPayload(
            String refNo,
            String traceId,
            String timestamp,
            String amount,
            String currency,
            String reference,
            String customerName,
            String phoneNo,
            String accountIdentifier,
            String transType) {}

    /**
     * What Co-op requires back.
     *
     * <p>{@code statusCode} 0 accepts; anything else makes Co-op retry. It is 0 for every notification we
     * manage to store, including the ones we cannot place — a retry would deliver the same wrong reference
     * again while giving us another chance to double-post.
     */
    public record IpnAck(String transactionID, int statusCode, String statusMessage) {

        static IpnAck accepted(String ourReference) {
            return new IpnAck(ourReference, 0, "Notification received");
        }

        /** Only when we failed to store it. Then a retry is exactly what we want. */
        static IpnAck retry(String message) {
            return new IpnAck(null, 1, message);
        }
    }
}
