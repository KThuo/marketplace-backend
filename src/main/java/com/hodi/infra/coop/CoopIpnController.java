package com.hodi.infra.coop;

import com.hodi.infra.coop.CoopIpnDtos.IpnAck;
import com.hodi.infra.coop.CoopIpnDtos.IpnPayload;
import com.hodi.logging.SkipRequestLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Co-op tells us money arrived.
 *
 * <h2>The only unauthenticated write path in the application</h2>
 *
 * <p>Nothing else here accepts a request with no principal and changes data as a result, so nothing in
 * {@code SecurityConfig}, {@code TenantBindingFilter} or {@code GlobalExceptionHandler} has ever been
 * exercised against one. That is why this class is deliberately thin and why its rules are stated rather than
 * assumed.
 *
 * <h2>It does not use ApiResponse</h2>
 *
 * <p>Every other endpoint answers with the platform's envelope. This one answers in Co-op's shape, because Co-op
 * reads {@code statusCode} and retries on anything non-zero. Wrapping it would make every notification look
 * like a failure and every payment arrive repeatedly.
 *
 * <h2>Always 200, and 0 for anything we stored</h2>
 *
 * <p>Including the payments we could not place. A retry cannot help those — the reference will be just as
 * wrong next time — and each retry is another chance to double-post. The one case that answers non-zero is a
 * notification we failed to <em>store</em>, where a retry is exactly what is wanted.
 *
 * <p>The HTTP status stays 200 throughout: some gateways treat a 4xx or 5xx as a delivery failure regardless
 * of the body, which would produce a retry we have just said we do not want.
 *
 * <h2>Why the payload is not logged</h2>
 *
 * <p>{@code @SkipRequestLog}, because the body carries a customer's name, phone number and the amount they
 * paid. It is already stored in {@code coop_statements.raw_payload} where it belongs, behind a permission;
 * the request log is read far more casually than that table.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CoopIpnController {

    private final CoopIpnService service;
    private final CoopStkService prompts;

    /**
     * @param authorization Co-op's HTTP Basic credentials. Verified against the configured pair; while
     *                      either half is unset every caller fails, which is what keeps an unconfigured
     *                      deployment from accepting anonymous notifications.
     */
    @PostMapping(CoopRoutes.NOTIFICATIONS)
    @SkipRequestLog
    public ResponseEntity<IpnAck> receive(
            /*
             * Taken raw, then normalised.
             *
             * Binding straight to a record meant every field Co-op spells differently — which is all of
             * them — arrived null, and the notification was stored quoting nothing. See CoopInbound.
             */
            @RequestBody java.util.Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            jakarta.servlet.http.HttpServletRequest request) {

        /*
         * Refused outright, before the body is read into anything.
         *
         * An address outside the allow-list is not a payment we failed to record — it is somebody who is
         * not Co-op — so nothing is stored and the answer is a 403. The body still says "retry", because if
         * it really is Co-op behind an address nobody added to the list, retrying and being refused again
         * is the loud failure an allow-list mistake should be; a quiet success would lose the money. The
         * list being empty accepts everybody, which is what keeps an unconfigured deployment from
         * discarding real notifications.
         */
        String caller = service.callerAddress(request);
        if (!service.isFromAllowedAddress(caller)) {
            log.warn("Refused a Co-op notification from {} — not in the allowed addresses", caller);
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(IpnAck.retry("Not an address we accept notifications from"));
        }

        /*
         * A callback about a prompt we started is not a credit landing in an account: it carries a result
         * code and no amount, and a cancelled prompt treated as a credit would be credited on its reference
         * alone. It goes to the prompt it answers instead. Always acknowledged, matched or not.
         */
        if (CoopInbound.isCallback(body)) {
            try {
                var intent = prompts.callback(body, service.isTrusted(authorization));
                return ResponseEntity.ok(IpnAck.accepted(intent == null ? null : intent.getReference()));
            } catch (Exception e) {
                log.error("Could not settle a Co-op STK callback: {}", e.getMessage(), e);
                return ResponseEntity.ok(IpnAck.retry("Could not record the callback; please retry"));
            }
        }

        IpnPayload payload = CoopInbound.parse(body);

        /*
         * A debit on the same account is not money arriving.
         *
         * Acknowledged rather than refused: Co-op has nothing to retry and no error to report, and a
         * non-zero status would have them redelivering a notification we will go on ignoring.
         */
        if (!CoopInbound.isCredit(body)) {
            log.info("Ignoring a non-credit Co-op notification {}", payload.refNo());
            return ResponseEntity.ok(IpnAck.accepted(payload.refNo()));
        }

        try {
            CoopStatement stored = service.accept(payload, service.isTrusted(authorization));
            return ResponseEntity.ok(IpnAck.accepted(stored.getOurReference()));
        } catch (Exception e) {
            /*
             * Caught broadly and on purpose.
             *
             * An uncaught exception here reaches GlobalExceptionHandler, which answers in the platform's
             * envelope — a body with no statusCode in it, which Co-op cannot read. It would then retry on the
             * missing field rather than on the failure, which is the right outcome reached for the wrong
             * reason and only by accident.
             *
             * The reference is logged, the payload is not: whatever went wrong, it is still a customer's name
             * and phone number.
             */
            log.error("Could not store Co-op notification {}: {}",
                    payload == null ? "(no body)" : payload.refNo(), e.getMessage(), e);
            return ResponseEntity.ok(IpnAck.retry("Could not record the notification; please retry"));
        }
    }

    /**
     * Who called, through whatever sits in front of this.
     *
     * <p>{@code X-Forwarded-For} first, leftmost entry, because behind a load balancer every request
     * appears to come from the balancer and an allow-list matched against that would admit the whole
     * internet. A header can be forged by anybody who can reach the application directly, so this narrows
     * a control rather than being one on its own — which is exactly how it is documented on the setting.
     */
}
