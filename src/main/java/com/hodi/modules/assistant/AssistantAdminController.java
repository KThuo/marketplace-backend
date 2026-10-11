package com.hodi.modules.assistant;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.assistant.AssistantKnowledgeService.KnowledgeRequest;
import com.hodi.modules.assistant.AssistantKnowledgeService.KnowledgeResponse;
import com.hodi.modules.assistant.AssistantService.Insights;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The platform's side of the assistant: what it explains, and where it falls short.
 *
 * <p>Where a general hand-off goes is an ordinary setting ({@code assistant.handoff.general}), changed on the
 * settings page with the rest, so it is not repeated here.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantAdminController {

    private final AssistantKnowledgeService knowledge;
    private final AssistantService assistant;

    @GetMapping("/knowledge")
    @PreAuthorize("hasAuthority('ASSISTANT_VIEW')")
    public ApiResponse<PagedResponse<KnowledgeResponse>> knowledge(@ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(knowledge.list(request));
    }

    @PostMapping("/knowledge")
    @PreAuthorize("hasAuthority('ASSISTANT_MANAGE')")
    @RequestAction("ADD ASSISTANT KNOWLEDGE")
    public ApiResponse<KnowledgeResponse> create(@Valid @RequestBody KnowledgeRequest request) {
        return ApiResponse.success("Added", knowledge.create(request));
    }

    @PutMapping("/knowledge/{reference}")
    @PreAuthorize("hasAuthority('ASSISTANT_MANAGE')")
    @RequestAction("CHANGE ASSISTANT KNOWLEDGE")
    public ApiResponse<KnowledgeResponse> update(@PathVariable String reference,
                                                 @Valid @RequestBody KnowledgeRequest request) {
        return ApiResponse.success("Saved", knowledge.update(reference, request));
    }

    @DeleteMapping("/knowledge/{reference}")
    @PreAuthorize("hasAuthority('ASSISTANT_MANAGE')")
    @RequestAction("REMOVE ASSISTANT KNOWLEDGE")
    public ApiResponse<Void> delete(@PathVariable String reference) {
        knowledge.delete(reference);
        return ApiResponse.success("Removed", null);
    }

    @GetMapping("/insights")
    @PreAuthorize("hasAuthority('ASSISTANT_VIEW')")
    public ApiResponse<Insights> insights(@RequestParam(defaultValue = "30") int days,
                                          @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(assistant.insights(days, request));
    }
}
