package com.hodi.modules.assistant;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.assistant.AssistantService.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The assistant for somebody who has not signed in.
 *
 * <p>The visitor's token travels in a header, never in the address, so it stays out of access logs. It reads
 * only conversations started with it, and the service limits how many questions it may ask in an hour.
 * Nothing here reads anybody's own rows: questions about bookings or viewings are answered with a card asking
 * the visitor to sign in.
 */
@RestController
@RequestMapping("/api/v1/public/assistant")
@RequiredArgsConstructor
public class PublicAssistantController {

    private final AssistantService assistant;

    @PostMapping("/ask")
    @RequestAction("ASK THE ASSISTANT AS A VISITOR")
    public ApiResponse<ConversationDetail> ask(@RequestHeader(AssistantConstants.VISITOR_HEADER) String visitor,
                                               @Valid @RequestBody AskRequest request, HttpServletRequest http) {
        return ApiResponse.success(assistant.askAsVisitor(visitor, clientAddress(http), request));
    }

    @GetMapping("/{reference}")
    public ApiResponse<ConversationDetail> find(@RequestHeader(AssistantConstants.VISITOR_HEADER) String visitor,
                                                @PathVariable String reference) {
        return ApiResponse.success(assistant.findAsVisitor(visitor, reference));
    }

    @PostMapping("/{reference}/messages/{messageId}/rating")
    public ApiResponse<ConversationDetail> rate(@RequestHeader(AssistantConstants.VISITOR_HEADER) String visitor,
                                                @PathVariable String reference, @PathVariable String messageId,
                                                @Valid @RequestBody RateRequest request) {
        return ApiResponse.success(assistant.rateAsVisitor(visitor, reference, messageId, request));
    }

    /**
     * Who asked, through whatever sits in front of this: the leftmost {@code X-Forwarded-For} entry, else the
     * socket. A forged header only moves a visitor into somebody else's hourly count; the per-token count still
     * applies, so it narrows the limit rather than being the limit.
     */
    static String clientAddress(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return http.getRemoteAddr();
    }
}
