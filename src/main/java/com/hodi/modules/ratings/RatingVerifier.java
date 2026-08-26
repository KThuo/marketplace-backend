package com.hodi.modules.ratings;

import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.leads.PurchaseRequestRepository;
import com.hodi.modules.leads.SiteVisitRepository;
import com.hodi.modules.properties.PropertyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Whether the platform can see a transaction behind a rating (M7).
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>Because "verified" is a claim the platform makes to strangers, and the evidence for it should be in one
 * readable place rather than scattered through a service. Everything here answers the same question: has this
 * person and this subject actually met on this platform?
 *
 * <h2>What it can and cannot see</h2>
 *
 * <p>A property or a seller can be checked — the enquiry, the viewing and the offer are all rows here. A
 * <strong>vendor cannot</strong>: the platform introduces the two parties and takes no part in what they
 * agree, so there is no record of the work being done. Rather than pretend otherwise, a vendor rating is
 * recorded as unverified and the screens say so. An unverified review is worth less than a verified one and
 * should look it.
 */
@Component
@RequiredArgsConstructor
public class RatingVerifier {

    private final SiteVisitRepository visits;
    private final PurchaseRequestRepository offers;
    private final EnquiryTicketRepository enquiries;
    private final PropertyRepository properties;
    private final AgentProfileRepository agents;

    /** @return how it was verified, or empty when the platform has nothing to go on */
    public Optional<String> verify(Long userId, String subjectType, Long subjectId) {
        return switch (subjectType) {
            case RatingSubject.PROPERTY -> forProperty(userId, subjectId);
            case RatingSubject.SELLER -> forTenant(userId, subjectId);
            // An agent's organisation is a tenant, so their evidence is a seller's evidence.
            case RatingSubject.AGENT -> agents.findById(subjectId)
                    .map(a -> a.getTenantId())
                    .flatMap(tenantId -> forTenant(userId, tenantId));
            // See the class comment. Nothing on this platform records the work.
            default -> Optional.empty();
        };
    }

    /**
     * Strongest evidence first.
     *
     * <p>An accepted offer outranks a completed viewing, which outranks having asked a question — and the
     * row records which, so a reader can weigh "they bought it" against "they enquired once".
     */
    private Optional<String> forProperty(Long userId, Long propertyId) {
        if (offers.existsByUserIdAndPropertyId(userId, propertyId)) {
            return Optional.of(RatingSubject.VIA_OFFER);
        }
        if (visits.existsByUserIdAndPropertyId(userId, propertyId)) {
            return Optional.of(RatingSubject.VIA_SITE_VISIT);
        }
        if (enquiries.existsByUserIdAndPropertyId(userId, propertyId)) {
            return Optional.of(RatingSubject.VIA_ENQUIRY);
        }
        return Optional.empty();
    }

    private Optional<String> forTenant(Long userId, Long tenantId) {
        if (tenantId == null) return Optional.empty();
        if (offers.existsByUserIdAndTenantId(userId, tenantId)) {
            return Optional.of(RatingSubject.VIA_OFFER);
        }
        if (visits.existsByUserIdAndTenantId(userId, tenantId)) {
            return Optional.of(RatingSubject.VIA_SITE_VISIT);
        }
        if (enquiries.existsByUserIdAndTenantId(userId, tenantId)) {
            return Optional.of(RatingSubject.VIA_ENQUIRY);
        }
        return Optional.empty();
    }
}
