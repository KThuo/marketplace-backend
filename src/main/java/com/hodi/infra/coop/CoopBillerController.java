package com.hodi.infra.coop;

import com.hodi.logging.SkipRequestLog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The two addresses the account form hands to Co-op for a biller, now served.
 *
 * <p>Until this class, {@link CoopRoutes#BILLER_VALIDATION} and {@link CoopRoutes#BILLER_ADVICE} were
 * declared, shown on the biller's account screen, and answered by nothing — a bank posting to them met the
 * security filter, not a handler. Both answer HTTP 200 with Co-op's own status code in the header, as
 * the bank's protocol expects; the one exception is an address outside the allow-list, which is refused
 * outright because it is not the bank.
 *
 * <p>An advice this class could not record answers {@code 405}, which is what makes Co-op try again — and
 * the only situation where a retry helps, because a payload we could not store might store next time.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CoopBillerController {

    private final CoopBillerService biller;
    private final CoopIpnService addresses;

    @PostMapping(CoopRoutes.BILLER_VALIDATION)
    @SkipRequestLog
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> refused = refusedAddress(body, request);
        if (refused != null) return refused;
        try {
            return ResponseEntity.ok(biller.validate(body));
        } catch (Exception e) {
            log.error("Co-op biller validation failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(CoopBillerService.error(messageId(body), CoopBillerService.UNAVAILABLE,
                    "Unexpected error"));
        }
    }

    @PostMapping(CoopRoutes.BILLER_ADVICE)
    @SkipRequestLog
    public ResponseEntity<Map<String, Object>> advise(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> refused = refusedAddress(body, request);
        if (refused != null) return refused;
        try {
            return ResponseEntity.ok(biller.advise(body));
        } catch (Exception e) {
            log.error("Could not record a Co-op biller advice: {}", e.getMessage(), e);
            return ResponseEntity.ok(CoopBillerService.error(messageId(body), CoopBillerService.UNAVAILABLE,
                    "End system unavailable"));
        }
    }

    private ResponseEntity<Map<String, Object>> refusedAddress(Map<String, Object> body,
                                                               HttpServletRequest request) {
        String caller = callerAddress(request);
        if (addresses.isFromAllowedAddress(caller)) return null;
        log.warn("Refused a Co-op biller call from {} — not in the allowed addresses", caller);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CoopBillerService.error(messageId(body), CoopBillerService.UNAUTHORISED,
                        "Not an address we accept biller calls from"));
    }

    @SuppressWarnings("unchecked")
    private static String messageId(Map<String, Object> body) {
        if (body == null || !(body.get("header") instanceof Map<?, ?> header)) return null;
        Object id = ((Map<String, Object>) header).get("messageID");
        return id == null ? null : String.valueOf(id);
    }

    private static String callerAddress(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
