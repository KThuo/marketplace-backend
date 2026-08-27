package com.hodi.modules.progress;

import com.hodi.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The build stages, in build order.
 *
 * <p>Public, for the same reason property types are: the progress editor needs it and so does a public
 * timeline that wants to show where a project sits in the sequence, and one of those has no session. There is
 * nothing here anybody should not see — eight stage names and a rough percentage each.
 *
 * <p>Read-only for now. Maintaining the list is an admin screen nobody has asked for, and eight rows seeded by
 * a migration are enough until somebody wants a ninth; adding writes before then would be a screen and a
 * permission built on a guess.
 */
@RestController
@RequiredArgsConstructor
public class ProgressMilestoneController {

    private final ProgressMilestoneRepository milestones;

    /**
     * What a stage is called and roughly how far along it is.
     *
     * <p>{@code typicalPercent} is for the editor to *offer* when somebody picks a stage. A screen that wrote
     * it over a figure the reporter typed would be publishing our estimate as their measurement.
     */
    public record MilestoneResponse(String code, String name, String description, Short typicalPercent) {}

    @GetMapping("/api/v1/public/progress-milestones")
    @Transactional(readOnly = true)
    public ApiResponse<List<MilestoneResponse>> milestones() {
        return ApiResponse.success(milestones.findLive().stream()
                .map(m -> new MilestoneResponse(m.getCode(), m.getName(), m.getDescription(),
                        m.getTypicalPercent()))
                .toList());
    }
}
