package com.hodi.modules.ratings;

import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.vendors.CatalogueItemRepository;
import com.hodi.modules.vendors.VendorProfileRepository;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Turning "rate PR260826N3P" into a row, and deciding who may answer it (M7).
 *
 * <p>The five subject types live behind one interface here so that {@code RatingService} does not carry a
 * switch over five repositories in three places. A rating names its subject by reference, never by id: a
 * numeric id in a public request body is an invitation to try the next one.
 *
 * <p><strong>Only live subjects can be rated.</strong> A draft listing, an unapproved vendor and a withdrawn
 * catalogue item are all invisible to the public, and a rating against something nobody can see is either a
 * mistake or somebody probing what exists.
 */
@Component
@RequiredArgsConstructor
public class RatingSubjectResolver {

    private final PropertyRepository properties;
    private final TenantRepository tenants;
    private final AgentProfileRepository agents;
    private final VendorProfileRepository vendors;
    private final CatalogueItemRepository items;

    public record Subject(Long id, String reference, String label, Long ownerTenantId) {}

    public Subject resolve(String subjectType, String reference) {
        return switch (subjectType) {
            case RatingSubject.PROPERTY -> properties.findLiveByReference(reference)
                    .map(p -> new Subject(p.getId(), p.getReference(), p.getTitle(), p.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));

            case RatingSubject.SELLER -> tenants.findByTenantRef(reference)
                    .map(t -> new Subject(t.getId(), t.getTenantRef(), t.getName(), t.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Organisation", reference));

            case RatingSubject.AGENT -> agents.findByReference(reference)
                    .filter(a -> a.isWorking())
                    .map(a -> new Subject(a.getId(), a.getReference(), a.tradingName(), a.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Agent", reference));

            case RatingSubject.VENDOR -> vendors.findPublicByReference(reference)
                    .map(v -> new Subject(v.getId(), v.getReference(), v.getBusinessName(),
                            v.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor", reference));

            case RatingSubject.CATALOGUE_ITEM -> items.findLiveByReference(reference)
                    .map(i -> new Subject(i.getId(), i.getReference(), i.getTitle(), i.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Catalogue item", reference));

            default -> throw new HodiException(
                    "\"" + subjectType + "\" is not something that can be rated.", HttpStatus.BAD_REQUEST);
        };
    }

    /**
     * Whether the caller is the party being rated.
     *
     * <p>Platform staff may reply too — they moderate, and a reply from the platform is sometimes the only
     * honest answer to a rating about something the platform itself did.
     */
    public void assertMayReply(String subjectType, Long subjectId) {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return;

        Long ownerTenantId = switch (subjectType) {
            case RatingSubject.PROPERTY -> properties.findById(subjectId)
                    .map(p -> p.getTenantId()).orElse(null);
            case RatingSubject.SELLER -> subjectId;
            case RatingSubject.AGENT -> agents.findById(subjectId)
                    .map(a -> a.getTenantId()).orElse(null);
            case RatingSubject.VENDOR -> vendors.findById(subjectId)
                    .map(v -> v.getTenantId()).orElse(null);
            case RatingSubject.CATALOGUE_ITEM -> items.findById(subjectId)
                    .map(i -> i.getTenantId()).orElse(null);
            default -> null;
        };

        if (ownerTenantId == null || !ownerTenantId.equals(caller.getTenantId())) {
            throw new HodiException("That rating is not about you.", HttpStatus.FORBIDDEN);
        }
    }
}
