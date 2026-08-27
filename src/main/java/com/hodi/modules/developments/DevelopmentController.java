package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.properties.ProgressUpdateService.UpdateResponse;
import com.hodi.modules.developments.DevelopmentDtos.DevelopmentListRequest;
import com.hodi.modules.developments.DevelopmentDtos.DevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.SaveDevelopmentRequest;
import com.hodi.modules.developments.DevelopmentDtos.SubmitRequest;
import com.hodi.modules.developments.DevelopmentDtos.WithdrawRequest;
import com.hodi.modules.developments.DevelopmentPhaseDtos.PhaseResponse;
import com.hodi.modules.developments.DevelopmentPhaseDtos.SavePhaseRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.BuildStatusRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.GeneratePreview;
import com.hodi.modules.developments.DevelopmentUnitDtos.GenerateUnitsRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.ReserveUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.SaveUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.SellUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.UnitListRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.UnitResponse;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.SaveUnitTypeRequest;
import com.hodi.modules.media.MediaDtos.MediaResponse;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.UnitTypeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Developments, their phases, their typologies and their units.
 *
 * <p>Nothing here is public. The marketplace reads {@code /api/v1/public/developments}, which is a different
 * controller with a different response record — a stranger gets the town and the availability, never the
 * budget, the facility or a buyer's name, and the two being separate records is what stops a field leaking by
 * being forgotten.
 *
 * <p>One controller for four resources rather than four, because they are one aggregate: a phase, a typology
 * and a unit are only ever reached through the development they belong to, and every path here says so.
 * That nesting is also the authorisation — the service resolves the development first and checks it, so a
 * caller cannot reach a unit by knowing its id alone.
 *
 * <p>{@code DEVELOPMENTS_APPROVE} does not appear below. Publishing goes through the approvals queue, for the
 * reason listings do: approving your own submission is what the Maker/Checker table exists to refuse.
 */
@RestController
@RequestMapping("/api/v1/developments")
@RequiredArgsConstructor
public class DevelopmentController {

    private final DevelopmentService service;
    private final DevelopmentPhaseService phases;
    private final DevelopmentUnitTypeService unitTypes;
    private final DevelopmentUnitService units;
    private final DevelopmentCollaboratorService collaborators;
    private final DevelopmentProgressService progress;
    private final DevelopmentMediaService mediaService;

    // ── the development ───────────────────────────────────────────────────────

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<PagedResponse<DevelopmentResponse>> list(
            @ModelAttribute DevelopmentListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<DevelopmentResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_CREATE')")
    @RequestAction("CREATE_DEVELOPMENT")
    public ApiResponse<DevelopmentResponse> create(
            @Valid @RequestBody SaveDevelopmentRequest request) {
        return ApiResponse.success("Development drafted", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_UPDATE')")
    @RequestAction("UPDATE_DEVELOPMENT")
    public ApiResponse<DevelopmentResponse> update(@PathVariable String hashId,
                                                   @Valid @RequestBody SaveDevelopmentRequest request) {
        return ApiResponse.success("Saved", service.update(hashId, request));
    }

    @PostMapping("/submit/{hashId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_SUBMIT')")
    @RequestAction("SUBMIT_DEVELOPMENT")
    public ApiResponse<DevelopmentResponse> submit(@PathVariable String hashId,
                                                   @RequestBody(required = false) SubmitRequest request) {
        return ApiResponse.success("Sent for approval", service.submit(hashId, request));
    }

    @PostMapping("/withdraw/{hashId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_WITHDRAW')")
    @RequestAction("WITHDRAW_DEVELOPMENT")
    public ApiResponse<DevelopmentResponse> withdraw(@PathVariable String hashId,
                                                     @Valid @RequestBody WithdrawRequest request) {
        return ApiResponse.success("Withdrawn", service.withdraw(hashId, request));
    }

    /** Turns a marketed development into a tracked-only project. */
    @PostMapping("/mark-private/{hashId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_UPDATE')")
    @RequestAction("MARK_DEVELOPMENT_PRIVATE")
    public ApiResponse<DevelopmentResponse> markPrivate(@PathVariable String hashId) {
        return ApiResponse.success("Now a tracked project", service.markPrivate(hashId));
    }

    @PostMapping("/delete/{hashId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_DELETE')")
    @RequestAction("DELETE_DEVELOPMENT")
    public ApiResponse<Void> archive(@PathVariable String hashId) {
        service.archive(hashId);
        return ApiResponse.success("Archived", null);
    }

    // ── phases ────────────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/phases")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<List<PhaseResponse>> phases(@PathVariable String hashId) {
        return ApiResponse.success(phases.list(hashId));
    }

    @PostMapping("/{hashId}/phases")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PHASES')")
    @RequestAction("ADD_DEVELOPMENT_PHASE")
    public ApiResponse<PhaseResponse> addPhase(@PathVariable String hashId,
                                               @Valid @RequestBody SavePhaseRequest request) {
        return ApiResponse.success("Phase added", phases.create(hashId, request));
    }

    @PostMapping("/{hashId}/phases/{phaseId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PHASES')")
    @RequestAction("UPDATE_DEVELOPMENT_PHASE")
    public ApiResponse<PhaseResponse> updatePhase(@PathVariable String hashId,
                                                  @PathVariable String phaseId,
                                                  @Valid @RequestBody SavePhaseRequest request) {
        return ApiResponse.success("Saved", phases.update(hashId, phaseId, request));
    }

    @PostMapping("/{hashId}/phases/{phaseId}/delete")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PHASES')")
    @RequestAction("DELETE_DEVELOPMENT_PHASE")
    public ApiResponse<Void> deletePhase(@PathVariable String hashId, @PathVariable String phaseId) {
        phases.archive(hashId, phaseId);
        return ApiResponse.success("Phase removed", null);
    }

    // ── typologies ────────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/unit-types")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<List<UnitTypeResponse>> unitTypes(@PathVariable String hashId) {
        return ApiResponse.success(unitTypes.list(hashId));
    }

    @PostMapping("/{hashId}/unit-types")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("ADD_UNIT_TYPE")
    public ApiResponse<UnitTypeResponse> addUnitType(@PathVariable String hashId,
                                                     @Valid @RequestBody SaveUnitTypeRequest request) {
        return ApiResponse.success("Unit type added", unitTypes.create(hashId, request));
    }

    @PostMapping("/{hashId}/unit-types/{typeId}")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("UPDATE_UNIT_TYPE")
    public ApiResponse<UnitTypeResponse> updateUnitType(@PathVariable String hashId,
                                                        @PathVariable String typeId,
                                                        @Valid @RequestBody SaveUnitTypeRequest request) {
        return ApiResponse.success("Saved", unitTypes.update(hashId, typeId, request));
    }

    /**
     * Gives a typology a listing so buyers can find and enquire about it.
     *
     * <p>Needs the listing permission as well as the unit one: this creates a {@code properties} row, and a
     * person who may arrange inventory is not automatically a person who may put something on the market.
     */
    @PostMapping("/{hashId}/unit-types/{typeId}/list")
    @PreAuthorize("hasAuthority('UNITS_MANAGE') and hasAuthority('PROPERTIES_CREATE')")
    @RequestAction("LIST_UNIT_TYPE")
    public ApiResponse<Map<String, String>> listUnitType(@PathVariable String hashId,
                                                          @PathVariable String typeId) {
        String reference = unitTypes.listOnMarketplace(hashId, typeId);
        return ApiResponse.success("Listing drafted — send it for approval to publish it",
                Map.of("listingReference", reference));
    }

    @PostMapping("/{hashId}/unit-types/{typeId}/delete")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("DELETE_UNIT_TYPE")
    public ApiResponse<Void> deleteUnitType(@PathVariable String hashId, @PathVariable String typeId) {
        unitTypes.archive(hashId, typeId);
        return ApiResponse.success("Unit type removed", null);
    }

    // ── units ─────────────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/units")
    @PreAuthorize("hasAuthority('UNITS_VIEW')")
    public ApiResponse<PagedResponse<UnitResponse>> units(@PathVariable String hashId,
                                                           @ModelAttribute UnitListRequest request) {
        return ApiResponse.success(units.list(hashId, request));
    }

    /** What a generation would produce. Writes nothing, so it needs no audit action. */
    @PostMapping("/{hashId}/units/preview")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    public ApiResponse<GeneratePreview> previewUnits(@PathVariable String hashId,
                                                      @Valid @RequestBody GenerateUnitsRequest request) {
        return ApiResponse.success(units.preview(hashId, request));
    }

    @PostMapping("/{hashId}/units/generate")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("GENERATE_UNITS")
    public ApiResponse<Map<String, Integer>> generateUnits(@PathVariable String hashId,
                                                            @Valid @RequestBody
                                                            GenerateUnitsRequest request) {
        int written = units.generate(hashId, request);
        return ApiResponse.success(written + " units added", Map.of("created", written));
    }

    @PostMapping("/{hashId}/units")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("ADD_UNIT")
    public ApiResponse<UnitResponse> addUnit(@PathVariable String hashId,
                                             @Valid @RequestBody SaveUnitRequest request) {
        return ApiResponse.success("Unit added", units.create(hashId, request));
    }

    @PostMapping("/{hashId}/units/{unitId}")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("UPDATE_UNIT")
    public ApiResponse<UnitResponse> updateUnit(@PathVariable String hashId,
                                                 @PathVariable String unitId,
                                                 @Valid @RequestBody SaveUnitRequest request) {
        return ApiResponse.success("Saved", units.update(hashId, unitId, request));
    }

    @PostMapping("/{hashId}/units/{unitId}/reserve")
    @PreAuthorize("hasAuthority('UNITS_SELL')")
    @RequestAction("RESERVE_UNIT")
    public ApiResponse<UnitResponse> reserveUnit(@PathVariable String hashId,
                                                  @PathVariable String unitId,
                                                  @Valid @RequestBody ReserveUnitRequest request) {
        return ApiResponse.success("Unit held", units.reserve(hashId, unitId, request));
    }

    @PostMapping("/{hashId}/units/{unitId}/sell")
    @PreAuthorize("hasAuthority('UNITS_SELL')")
    @RequestAction("SELL_UNIT")
    public ApiResponse<UnitResponse> sellUnit(@PathVariable String hashId,
                                               @PathVariable String unitId,
                                               @Valid @RequestBody SellUnitRequest request) {
        return ApiResponse.success("Sale recorded", units.sell(hashId, unitId, request));
    }

    @PostMapping("/{hashId}/units/{unitId}/release")
    @PreAuthorize("hasAuthority('UNITS_SELL')")
    @RequestAction("RELEASE_UNIT")
    public ApiResponse<UnitResponse> releaseUnit(@PathVariable String hashId,
                                                  @PathVariable String unitId) {
        return ApiResponse.success("Back on offer", units.release(hashId, unitId));
    }

    @PostMapping("/{hashId}/units/{unitId}/build-status")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("SET_UNIT_BUILD_STATUS")
    public ApiResponse<UnitResponse> setBuildStatus(@PathVariable String hashId,
                                                     @PathVariable String unitId,
                                                     @Valid @RequestBody BuildStatusRequest request) {
        return ApiResponse.success("Saved", units.setBuildStatus(hashId, unitId, request));
    }

    @PostMapping("/{hashId}/units/{unitId}/delete")
    @PreAuthorize("hasAuthority('UNITS_MANAGE')")
    @RequestAction("DELETE_UNIT")
    public ApiResponse<Void> deleteUnit(@PathVariable String hashId, @PathVariable String unitId) {
        units.archive(hashId, unitId);
        return ApiResponse.success("Unit removed", null);
    }

    // ── photographs, plans and brochures ──────────────────────────────────────
    //
    // One set of paths for five owner types rather than five sets, because the difference between a
    // development's album and a phase's is a parameter, not a shape. `ownerType` names which, and
    // DevelopmentMediaService is what confirms the child named actually belongs to the development in the
    // path — the schema's composite keys stop a bad row, not a bad request.

    @GetMapping("/{hashId}/media")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<List<MediaResponse>> media(
            @PathVariable String hashId,
            @RequestParam(defaultValue = "DEVELOPMENT") String ownerType,
            @RequestParam(required = false) String childId) {
        return ApiResponse.success(mediaService.list(hashId, ownerType, childId));
    }

    @PostMapping("/{hashId}/media")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_MEDIA')")
    @RequestAction("ADD_DEVELOPMENT_MEDIA")
    public ApiResponse<MediaResponse> addMedia(
            @PathVariable String hashId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "DEVELOPMENT") String ownerType,
            @RequestParam(required = false) String childId,
            @RequestParam(required = false) String mediaKind,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) Boolean publicVisible) {
        return ApiResponse.success("Uploaded",
                mediaService.add(hashId, ownerType, childId, file, mediaKind, caption, publicVisible));
    }

    @PostMapping("/{hashId}/media/{mediaId}/primary")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_MEDIA')")
    @RequestAction("SET_DEVELOPMENT_COVER")
    public ApiResponse<MediaResponse> makeCover(
            @PathVariable String hashId,
            @PathVariable String mediaId,
            @RequestParam(defaultValue = "DEVELOPMENT") String ownerType,
            @RequestParam(required = false) String childId) {
        return ApiResponse.success("Cover set",
                mediaService.makePrimary(hashId, ownerType, childId, mediaId));
    }

    @PostMapping("/{hashId}/media/{mediaId}/delete")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_MEDIA')")
    @RequestAction("DELETE_DEVELOPMENT_MEDIA")
    public ApiResponse<Void> deleteMedia(
            @PathVariable String hashId,
            @PathVariable String mediaId,
            @RequestParam(defaultValue = "DEVELOPMENT") String ownerType,
            @RequestParam(required = false) String childId) {
        mediaService.remove(hashId, ownerType, childId, mediaId);
        return ApiResponse.success("Removed", null);
    }

    // ── progress ──────────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/progress")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<List<UpdateResponse>> progress(@PathVariable String hashId) {
        return ApiResponse.success(progress.forOwner(hashId));
    }

    @PostMapping("/{hashId}/progress")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PROGRESS')")
    @RequestAction("CREATE_DEVELOPMENT_PROGRESS")
    public ApiResponse<UpdateResponse> createProgress(
            @PathVariable String hashId,
            @Valid @RequestBody DevelopmentProgressService.SaveDevelopmentUpdateRequest request) {
        return ApiResponse.success("Update saved", progress.create(hashId, request));
    }

    @PutMapping("/{hashId}/progress/{updateId}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PROGRESS')")
    @RequestAction("UPDATE_DEVELOPMENT_PROGRESS")
    public ApiResponse<UpdateResponse> updateProgress(
            @PathVariable String hashId,
            @PathVariable String updateId,
            @Valid @RequestBody DevelopmentProgressService.SaveDevelopmentUpdateRequest request) {
        return ApiResponse.success("Update saved", progress.update(hashId, updateId, request));
    }

    /**
     * Publishing and withdrawing are one endpoint with a flag rather than two.
     *
     * <p>They are the same decision in two directions and share every check; splitting them would mean two
     * places to remember that a private project may not publish.
     */
    @PostMapping("/{hashId}/progress/{updateId}/published")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PROGRESS')")
    @RequestAction("PUBLISH_DEVELOPMENT_PROGRESS")
    public ApiResponse<UpdateResponse> setProgressPublished(
            @PathVariable String hashId,
            @PathVariable String updateId,
            @RequestParam(defaultValue = "true") boolean publish) {
        return ApiResponse.success(publish ? "Published" : "Withdrawn",
                progress.setPublished(hashId, updateId, publish));
    }

    @PostMapping("/{hashId}/progress/{updateId}/delete")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_PROGRESS')")
    @RequestAction("DELETE_DEVELOPMENT_PROGRESS")
    public ApiResponse<Void> archiveProgress(@PathVariable String hashId, @PathVariable String updateId) {
        progress.archive(hashId, updateId);
        return ApiResponse.success("Removed", null);
    }

    // ── collaborators ─────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/collaborators")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_VIEW')")
    public ApiResponse<List<DevelopmentCollaboratorService.CollaboratorResponse>> collaborators(
            @PathVariable String hashId) {
        return ApiResponse.success(collaborators.list(hashId));
    }

    @PostMapping("/{hashId}/collaborators")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_GRANT')")
    @RequestAction("GRANT_DEVELOPMENT_RIGHTS")
    public ApiResponse<DevelopmentCollaboratorService.CollaboratorResponse> grant(
            @PathVariable String hashId,
            @Valid @RequestBody DevelopmentCollaboratorService.GrantRequest request) {
        return ApiResponse.success("Rights granted", collaborators.grant(hashId, request));
    }

    @PostMapping("/{hashId}/collaborators/{collaboratorId}/revoke")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_GRANT')")
    @RequestAction("REVOKE_DEVELOPMENT_RIGHTS")
    public ApiResponse<Void> revoke(@PathVariable String hashId,
                                    @PathVariable String collaboratorId,
                                    @Valid @RequestBody DevelopmentCollaboratorService.RevokeRequest request) {
        collaborators.revoke(hashId, collaboratorId, request);
        return ApiResponse.success("Rights withdrawn", null);
    }
}
