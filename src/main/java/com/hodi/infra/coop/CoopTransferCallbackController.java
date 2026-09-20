package com.hodi.infra.coop;

import com.hodi.logging.SkipRequestLog;
import com.hodi.modules.disbursements.Disbursement;
import com.hodi.modules.disbursements.DisbursementService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Co-op telling us what became of a transfer we sent.
 *
 * <p>The address goes out as the {@code CallBackUrl} on every PesaLink request. The body is expected in the
 * Transaction Status V3 shape — envelope plus {@code Source} and {@code Destinations[]} — which is what pesi
 * observed and what the enquiry answers with; both are read by the same code. Authenticated like the
 * notification, with HTTP Basic; an unauthenticated callback settles nothing and is still acknowledged,
 * because the enquiry will settle it and a refusal would only make the bank retry.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CoopTransferCallbackController {

    private final DisbursementService disbursements;
    private final CoopIpnService addresses;

    @PostMapping(CoopRoutes.FT_CALLBACK)
    @SkipRequestLog
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        String caller = addresses.callerAddress(request);
        if (!addresses.isFromAllowedAddress(caller)) {
            log.warn("Refused a Co-op transfer callback from {} — not in the allowed addresses", caller);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ack(body, "1", "Not an address we accept callbacks from"));
        }
        try {
            Disbursement matched = disbursements.callback(body, addresses.isTrusted(authorization));
            return ResponseEntity.ok(ack(body, "0", matched == null ? "Received; no transfer of ours" : "Received"));
        } catch (Exception e) {
            log.error("Could not record a Co-op transfer callback: {}", e.getMessage(), e);
            return ResponseEntity.ok(ack(body, "1", "Could not record the callback; please retry"));
        }
    }

    private static Map<String, Object> ack(Map<String, Object> body, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("MessageReference", body == null ? null : body.get("MessageReference"));
        out.put("MessageCode", code);
        out.put("MessageDescription", message);
        return out;
    }
}
