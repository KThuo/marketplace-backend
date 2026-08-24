package com.hodi.modules.properties;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.properties.PropertyDtos.MediaResponse;
import com.hodi.modules.properties.PropertyDtos.PropertyListRequest;
import com.hodi.modules.properties.PropertyDtos.PropertyResponse;
import com.hodi.modules.properties.PropertyDtos.SavePropertyRequest;
import com.hodi.modules.properties.PropertyDtos.SubmitRequest;
import com.hodi.modules.properties.PropertyDtos.WithdrawRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * A seller's own listings.
 *
 * <p>Nothing here is public. The marketplace reads {@code /api/v1/public/properties}, which is a different
 * controller with a different response shape — a stranger gets the town, not the address, and the two being
 * separate records is what stops a field leaking by being forgotten.
 *
 * <p>{@code PROPERTIES_APPROVE} is not used here. Publishing happens through the approvals queue, because
 * approving your own submission is the thing this platform now refuses at the database.
 */
@RestController
@RequestMapping("/api/v1/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService service;
    private final PropertyMediaService mediaService;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('PROPERTIES_VIEW')")
    public ApiResponse<PagedResponse<PropertyResponse>> list(
            @ModelAttribute PropertyListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('PROPERTIES_VIEW')")
    public ApiResponse<PropertyResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('PROPERTIES_CREATE')")
    @RequestAction("CREATE_LISTING")
    public ApiResponse<PropertyResponse> create(@Valid @RequestBody SavePropertyRequest request) {
        return ApiResponse.success("Listing drafted", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('PROPERTIES_UPDATE')")
    @RequestAction("UPDATE_LISTING")
    public ApiResponse<PropertyResponse> update(@PathVariable String hashId,
                                                @Valid @RequestBody SavePropertyRequest request) {
        return ApiResponse.success("Listing saved", service.update(hashId, request));
    }

    @PostMapping("/submit/{hashId}")
    @PreAuthorize("hasAuthority('PROPERTIES_SUBMIT')")
    @RequestAction("SUBMIT_LISTING")
    public ApiResponse<PropertyResponse> submit(@PathVariable String hashId,
                                                @RequestBody(required = false) SubmitRequest request) {
        return ApiResponse.success("Sent for approval", service.submit(hashId, request));
    }

    @PostMapping("/withdraw/{hashId}")
    @PreAuthorize("hasAuthority('PROPERTIES_WITHDRAW')")
    @RequestAction("WITHDRAW_LISTING")
    public ApiResponse<PropertyResponse> withdraw(@PathVariable String hashId,
                                                  @Valid @RequestBody WithdrawRequest request) {
        return ApiResponse.success("Taken down", service.withdraw(hashId, request.reason()));
    }

    @PostMapping("/mark-sold/{hashId}")
    @PreAuthorize("hasAuthority('PROPERTIES_MARK_SOLD')")
    @RequestAction("MARK_LISTING_SOLD")
    public ApiResponse<PropertyResponse> markSold(@PathVariable String hashId) {
        return ApiResponse.success("Marked sold", service.markSold(hashId));
    }

    @PostMapping("/delete/{hashId}")
    @PreAuthorize("hasAuthority('PROPERTIES_DELETE')")
    @RequestAction("DELETE_LISTING")
    public ApiResponse<Void> archive(@PathVariable String hashId,
                                     @RequestBody(required = false) DeactivateRequest request) {
        service.archive(hashId);
        return ApiResponse.success("Listing archived", null);
    }

    // ── photographs ───────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/media")
    @PreAuthorize("hasAuthority('PROPERTIES_VIEW')")
    public ApiResponse<List<MediaResponse>> media(@PathVariable String hashId) {
        return ApiResponse.success(mediaService.list(hashId));
    }

    @PostMapping("/{hashId}/media")
    @PreAuthorize("hasAuthority('PROPERTIES_MEDIA')")
    @RequestAction("ADD_LISTING_PHOTO")
    public ApiResponse<MediaResponse> addMedia(@PathVariable String hashId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) String caption) {
        return ApiResponse.success("Photograph added", mediaService.add(hashId, file, caption));
    }

    @PostMapping("/{hashId}/media/{mediaId}/primary")
    @PreAuthorize("hasAuthority('PROPERTIES_MEDIA')")
    @RequestAction("SET_LISTING_COVER")
    public ApiResponse<Void> makePrimary(@PathVariable String hashId, @PathVariable String mediaId) {
        mediaService.makePrimary(hashId, mediaId);
        return ApiResponse.success("Cover photograph set", null);
    }

    @PostMapping("/{hashId}/media/{mediaId}/delete")
    @PreAuthorize("hasAuthority('PROPERTIES_MEDIA')")
    @RequestAction("REMOVE_LISTING_PHOTO")
    public ApiResponse<Void> removeMedia(@PathVariable String hashId, @PathVariable String mediaId) {
        mediaService.remove(hashId, mediaId);
        return ApiResponse.success("Photograph removed", null);
    }
}
