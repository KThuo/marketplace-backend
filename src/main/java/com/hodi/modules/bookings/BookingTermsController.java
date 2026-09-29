package com.hodi.modules.bookings;

import com.hodi.common.ApiResponse;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.logging.RequestAction;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingTermsService.*;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * The booking terms, for everyone who meets them: the buyer on their own booking, the sales office on
 * one they may write, anybody on a listing, and the platform on the template.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingTermsController {

    private final BookingService bookings;
    private final BookingTermsService terms;
    private final PropertyRepository properties;
    private final BookingAccess access;

    // ── the buyer ─────────────────────────────────────────────────────────────

    @GetMapping("/me/bookings/{hashId}/terms")
    public ApiResponse<TermsView> myTerms(@PathVariable String hashId) {
        return ApiResponse.success(bookings.myTerms(hashId));
    }

    @PostMapping("/me/bookings/{hashId}/terms/accept")
    @RequestAction("ACCEPT BOOKING TERMS")
    public ApiResponse<BookingResponse> accept(@PathVariable String hashId) {
        return ApiResponse.success("Thank you — the terms are agreed", bookings.acceptTerms(hashId));
    }

    @PostMapping("/me/bookings/{hashId}/terms/decline")
    @RequestAction("DECLINE BOOKING TERMS")
    public ApiResponse<BookingResponse> decline(@PathVariable String hashId, @Valid @RequestBody DeclineRequest request) {
        return ApiResponse.success("The booking is closed and the home released", bookings.declineTerms(hashId, request));
    }

    @PostMapping("/me/bookings/{hashId}/terms/confirm")
    @RequestAction("CONFIRM BOOKING TERMS")
    public ApiResponse<BookingResponse> confirm(@PathVariable String hashId) {
        return ApiResponse.success("Confirmed", bookings.confirmTerms(hashId));
    }

    @GetMapping("/me/bookings/{hashId}/terms/signed")
    public ResponseEntity<byte[]> mySignedForm(@PathVariable String hashId) {
        return stream(bookings.signedTerms(hashId));
    }

    // ── the sales office ──────────────────────────────────────────────────────

    @GetMapping("/bookings/{hashId}/terms")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<TermsView> termsOf(@PathVariable String hashId) {
        return ApiResponse.success(bookings.termsOf(hashId));
    }

    /** The buyer signed on paper: the form is the acceptance. */
    @PostMapping(value = "/bookings/{hashId}/terms/paper", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("RECORD SIGNED BOOKING TERMS")
    public ApiResponse<BookingResponse> onPaper(@PathVariable String hashId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Signed terms recorded", bookings.recordTermsOnPaper(hashId, file));
    }

    @GetMapping("/bookings/{hashId}/terms/signed")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ResponseEntity<byte[]> signedForm(@PathVariable String hashId) {
        return stream(bookings.signedTerms(hashId));
    }

    // ── anybody, on a listing ─────────────────────────────────────────────────

    /** What a booking of this home would be made under today. Public, like the listing. */
    @GetMapping("/public/properties/{reference}/terms")
    @Transactional(readOnly = true)
    public ApiResponse<TermsView> forListing(@PathVariable String reference) {
        Property home = properties.findLiveByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));
        return ApiResponse.success(terms.viewForListing(home, access.developmentOf(home)));
    }

    // ── the platform, on the template ─────────────────────────────────────────

    @GetMapping("/booking-terms/template")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<TemplateResponse> template() {
        return ApiResponse.success(terms.activeTemplate());
    }

    @GetMapping("/booking-terms/template/history")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<List<TemplateResponse>> history() {
        return ApiResponse.success(terms.templateHistory());
    }

    @PostMapping("/booking-terms/template")
    @PreAuthorize("hasAuthority('APP_SETTINGS_UPDATE')")
    @RequestAction("PUBLISH BOOKING TERMS")
    public ApiResponse<TemplateResponse> publish(@Valid @RequestBody NewTemplateRequest request) {
        return ApiResponse.success("Published — new bookings are made under this version", terms.newTemplate(request));
    }

    private static ResponseEntity<byte[]> stream(DocumentService.Fetched fetched) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fetched.contentType() == null ? "application/octet-stream" : fetched.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fetched.fileName() == null ? "signed-terms" : fetched.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .body(fetched.bytes());
    }
}
