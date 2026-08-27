package com.hodi.infra.pesi;

import com.hodi.infra.pesi.PesiIpnDtos.IpnAck;
import com.hodi.infra.pesi.PesiIpnDtos.IpnPayload;
import com.hodi.logging.SkipRequestLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Pesi tells us money arrived.
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
 * <p>Every other endpoint answers with the platform's envelope. This one answers in Pesi's shape, because Pesi
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
 * paid. It is already stored in {@code pesi_statements.raw_payload} where it belongs, behind a permission;
 * the request log is read far more casually than that table.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PesiIpnController {

    private final PesiIpnService service;

    /**
     * @param signature the shared secret, when one is configured. Its absence does not refuse the
     *                  notification — it means nothing will be credited without a person.
     */
    @PostMapping("/api/v1/public/pesi/notifications")
    @SkipRequestLog
    public ResponseEntity<IpnAck> receive(
            @RequestBody IpnPayload payload,
            @RequestHeader(value = "X-Pesi-Signature", required = false) String signature) {

        try {
            PesiStatement stored = service.accept(payload, service.isTrusted(signature));
            return ResponseEntity.ok(IpnAck.accepted(stored.getOurReference()));
        } catch (Exception e) {
            /*
             * Caught broadly and on purpose.
             *
             * An uncaught exception here reaches GlobalExceptionHandler, which answers in the platform's
             * envelope — a body with no statusCode in it, which Pesi cannot read. It would then retry on the
             * missing field rather than on the failure, which is the right outcome reached for the wrong
             * reason and only by accident.
             *
             * The reference is logged, the payload is not: whatever went wrong, it is still a customer's name
             * and phone number.
             */
            log.error("Could not store Pesi notification {}: {}",
                    payload == null ? "(no body)" : payload.refNo(), e.getMessage(), e);
            return ResponseEntity.ok(IpnAck.retry("Could not record the notification; please retry"));
        }
    }
}
