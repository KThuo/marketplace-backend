package com.hodi.modules.consent;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.consent.ConsentService.ConsentHistoryRow;
import com.hodi.modules.consent.ConsentService.ConsentRow;
import com.hodi.modules.consent.ConsentService.UpdateConsentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * What a person has agreed to be contacted about (plan §3.8, BRD FR004–FR005).
 *
 * <p>Under {@code /api/v1/me} with the rest of a person's own things, and for the same reason: the row
 * filter is the identity, so there is no permission to grant and no endpoint that names a user. An
 * administrator cannot read or change somebody's consent through this controller — if that ever becomes a
 * requirement it will be a different endpoint with a permission and an audit entry saying who did it, not a
 * user-id parameter added here.
 */
@RestController
@RequestMapping("/api/v1/me/consent")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService service;

    @GetMapping
    public ApiResponse<List<ConsentRow>> mine() {
        return ApiResponse.success(service.mine());
    }

    @PutMapping
    @RequestAction("UPDATE CONSENT")
    public ApiResponse<List<ConsentRow>> update(@RequestBody UpdateConsentRequest request) {
        return ApiResponse.success("Preferences saved", service.update(request));
    }

    /**
     * The person's own trail: every movement of their preferences, newest first.
     *
     * <p>Exposed to them rather than only to Compliance. The regulation's question is "prove they opted in
     * on this date", and the person it is about is entitled to the same answer.
     */
    @GetMapping("/history")
    public ApiResponse<List<ConsentHistoryRow>> history(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.myHistory(limit));
    }
}
