package com.hodi.modules.assistant;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.assistant.AssistantService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The assistant (M11).
 *
 * <p>Everything is under {@code /me}: a conversation belongs to the person who had it, scoped by identity,
 * with no permission to grant. Nothing here reads anybody else's rows — including the "where do my enquiries
 * stand" answer, which is the caller's own count and nobody else's.
 */
@RestController
@RequestMapping("/api/v1/me/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistant;

    @PostMapping("/ask")
    @RequestAction("ASK THE ASSISTANT")
    public ApiResponse<ConversationDetail> ask(@Valid @RequestBody AskRequest request) {
        return ApiResponse.success(assistant.ask(request));
    }

    @GetMapping
    public ApiResponse<PagedResponse<ConversationResponse>> mine(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(assistant.mine(request));
    }

    @GetMapping("/{reference}")
    public ApiResponse<ConversationDetail> find(@PathVariable String reference) {
        return ApiResponse.success(assistant.find(reference));
    }

    @PostMapping("/{reference}/hand-off")
    @RequestAction("HAND OFF TO A PERSON")
    public ApiResponse<ConversationDetail> handOff(@PathVariable String reference,
                                                   @Valid @RequestBody HandOffRequest request) {
        return ApiResponse.success("Passed on", assistant.handOff(reference, request));
    }

    @PostMapping("/{reference}/close")
    public ApiResponse<Void> close(@PathVariable String reference) {
        assistant.close(reference);
        return ApiResponse.success("Closed", null);
    }
}
