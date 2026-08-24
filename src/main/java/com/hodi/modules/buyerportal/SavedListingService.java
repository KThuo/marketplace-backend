package com.hodi.modules.buyerportal;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A person's shortlist (BRD FR020–FR021).
 *
 * <h2>Visibility is identity, and there is no other axis</h2>
 *
 * <p>Every read and write here starts from {@link AuthContext#requireUserId()}. No TenantScope — a shortlist
 * has no organisation — and no permission beyond being signed in: a buyer holds exactly one permission
 * ({@code BUYER_PORTAL_ACCESS}) and what they see inside their own area is resolved from who they are, which
 * is the property that makes "a buyer cannot read another buyer's shortlist" true by construction. There is
 * no endpoint here that takes a user id.
 *
 * <h2>Listings are addressed by reference, not by id</h2>
 *
 * <p>The client is holding ids that were encoded on the public marketplace, where {@code PublicMarketplace}
 * fixes the HashId salt for everybody. These endpoints are not on that path, so the caller's own salt
 * applies and the same string would decode to something else or to nothing. A reference is salt-free, is
 * already the public detail page's handle, and is what a buyer would quote down the phone — so it is the
 * handle for the whole of this module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedListingService {

    private final SavedListingRepository repository;
    private final PropertyRepository properties;
    private final StorageService storage;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * A shortlist card.
     *
     * <p>No id, because the reference is the handle for everything this module offers. Live figures where
     * the listing is still public, the snapshot taken at save time where it is not — and
     * {@link #available} says which of the two the buyer is looking at, so a withdrawn listing reads as
     * news rather than as a stale price.
     */
    public record SavedListingResponse(
            String reference,
            String title,
            String propertyType,
            BigDecimal price,
            String currency,
            Short bedrooms,
            Short bathrooms,
            String county,
            String town,
            String estate,
            String imageUrl,
            String sellerName,
            boolean greenCertified,
            boolean available,
            String unavailableReason,
            String note,
            OffsetDateTime savedAt) {}

    public record SaveListingRequest(String reference, String note) {}

    // ── the shortlist ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<SavedListingResponse> mine(PagedDataRequest request) {
        Long userId = AuthContext.requireUserId();
        var page = repository.findByUserId(
                userId, request.toPageable(Sort.by(Sort.Direction.DESC, "savedAt")));

        // One query for the whole page rather than one per row: a shortlist of forty would otherwise be
        // forty lookups for the live price.
        Map<Long, Property> live = properties.findAllById(
                        page.getContent().stream().map(SavedListing::getPropertyId).toList()).stream()
                .collect(Collectors.toMap(Property::getId, Function.identity()));

        return PagedResponse.from(page, saved -> toCard(saved, live.get(saved.getPropertyId())));
    }

    /**
     * Every reference this person has saved.
     *
     * <p>So the marketplace can draw the hearts filled without a request per card — and, with infinite
     * scroll, without a request per page either. Small enough to send whole: a reference is twelve
     * characters, and a shortlist of two hundred is a two-kilobyte answer to a question the client would
     * otherwise ask two hundred times.
     */
    @Transactional(readOnly = true)
    public List<String> myReferences() {
        return repository.findByUserId(
                        AuthContext.requireUserId(),
                        org.springframework.data.domain.PageRequest.of(
                                0, 500, Sort.by(Sort.Direction.DESC, "savedAt")))
                .map(SavedListing::getReference)
                .getContent();
    }

    @Transactional(readOnly = true)
    public long myCount() {
        return repository.countByUserId(AuthContext.requireUserId());
    }

    /**
     * Saves a listing, or updates the note on one already saved.
     *
     * <p>Idempotent on purpose. A heart pressed twice on two tabs is not an error worth showing somebody,
     * and the unique index means the alternative to this branch is a constraint violation surfacing as a
     * 500.
     */
    @Transactional
    public SavedListingResponse save(SaveListingRequest request) {
        Long userId = AuthContext.requireUserId();
        String reference = request.reference() == null ? "" : request.reference().trim();

        // findLiveByReference, not findByReference: a buyer can only save something the marketplace is
        // currently showing them. Saving a withdrawn listing by quoting its reference would be a way to
        // learn that the reference exists.
        Property property = properties.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));

        SavedListing row = repository.findByUserIdAndPropertyId(userId, property.getId())
                .orElseGet(() -> SavedListing.builder()
                        .userId(userId)
                        .propertyId(property.getId())
                        .reference(property.getReference())
                        .savedAt(OffsetDateTime.now())
                        .createdBy(AuthContext.username())
                        .build());

        // The snapshot is (re)taken on every save, including a note edit: the buyer is looking at the
        // listing as it is now, so that is the version their shortlist should remember it as.
        row.setTitleSnapshot(property.getTitle());
        row.setPriceSnapshot(property.getPrice());
        row.setCurrencySnapshot(property.getCurrency());
        row.setTownSnapshot(property.getTown());
        row.setImageSnapshot(property.getPrimaryImageKey());
        if (request.note() != null) row.setNote(request.note().isBlank() ? null : request.note().trim());

        return toCard(repository.save(row), property);
    }

    /**
     * Removes a listing from the shortlist.
     *
     * <p>A real delete, not an archive. See {@link SavedListing} — a bookmark somebody removed should stop
     * existing. Silent when it was not there: "un-save something I have not saved" has already reached the
     * state the caller asked for.
     */
    @Transactional
    public void remove(String reference) {
        Long userId = AuthContext.requireUserId();
        properties.findByReference(reference == null ? "" : reference.trim())
                .flatMap(p -> repository.findByUserIdAndPropertyId(userId, p.getId()))
                .ifPresent(repository::delete);
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    /**
     * @param property the live row, or null when the listing has been archived out from under the shortlist
     */
    private SavedListingResponse toCard(SavedListing saved, Property property) {
        boolean available = property != null
                && AppConstant.LISTING_LIVE.equals(property.getListingState())
                && AppConstant.isLive(property.getStatus());

        if (available) {
            return new SavedListingResponse(
                    saved.getReference(),
                    property.getTitle(),
                    property.getPropertyType(),
                    property.getPrice(),
                    property.getCurrency(),
                    property.getBedrooms(),
                    property.getBathrooms(),
                    property.getCounty(),
                    property.getTown(),
                    property.getEstate(),
                    storage.urlFor(property.getPrimaryImageKey()),
                    property.getTenantName(),
                    property.isGreenCertified(),
                    true,
                    null,
                    saved.getNote(),
                    saved.getSavedAt());
        }

        // The snapshot's turn. Everything shown is what the buyer themselves saw when they saved it, which
        // is the only version anybody can honestly be shown once the listing is no longer public.
        return new SavedListingResponse(
                saved.getReference(),
                saved.getTitleSnapshot(),
                property == null ? null : property.getPropertyType(),
                saved.getPriceSnapshot(),
                saved.getCurrencySnapshot(),
                null,
                null,
                null,
                saved.getTownSnapshot(),
                null,
                storage.urlFor(saved.getImageSnapshot()),
                null,
                false,
                false,
                unavailableReason(property),
                saved.getNote(),
                saved.getSavedAt());
    }

    private static String unavailableReason(Property property) {
        if (property == null) return "This listing has been removed";
        return switch (property.getListingState()) {
            case AppConstant.LISTING_SOLD -> "Sold";
            case AppConstant.LISTING_WITHDRAWN -> "Taken off the market";
            default -> "No longer listed";
        };
    }
}
