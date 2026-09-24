package com.hodi.modules.properties;

import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who may look at a listing's files, and who may change them.
 *
 * <p>Lifted out of {@link PropertyMediaService} when a listing gained tours as well as photographs. Two
 * copies of these rules would be two places for the next fix to land in only one of — and the first of them
 * already records exactly that happening once, between the listing and the development screens.
 */
@Component
@RequiredArgsConstructor
public class ListingAccess {

    private final PropertyRepository properties;

    /**
     * A listing this caller may see — the same rule that let them open it.
     *
     * <p>Reading the gallery used to demand a matching tenant id, which the bank's own staff do not have:
     * they hold no tenant at all. So somebody from the platform could open a listing, see it, watch its
     * photographs on the marketplace — and be told by this one endpoint that it "belongs to another
     * organisation", which the screen reported as an empty gallery. Meanwhile the same person could add
     * photographs to that very album through the development screens, because those have always admitted
     * them. One album, two doors, and only one of them locked.
     */
    public Property requireVisible(String hashId) {
        Property property = properties.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", hashId));
        if (TenantScope.unrestricted()) return property;
        var visible = TenantScope.visibleIds();
        if (visible == null || !visible.contains(property.getTenantId())) {
            // Not found rather than forbidden, exactly as PropertyService answers: whether a listing exists
            // is itself information, and a partnered lender reading a portfolio should learn no more here.
            throw new ResourceNotFoundException("Listing", hashId);
        }
        return property;
    }

    /**
     * A listing this caller may change the pictures of: their organisation's, or the platform's oversight.
     *
     * <p>Platform staff are admitted for the same reason {@code PropertyService.requireManageable} admits
     * them to a withdrawal — whoever operates a marketplace has to be able to take down something unlawful
     * without waiting for the seller to agree, and a photograph is the commonest such thing. A partnered
     * lender is not: reading a seller's portfolio is not permission to edit it.
     */
    public Property requireOwn(String hashId) {
        Property property = requireVisible(hashId);
        if (AuthContext.require().isPlatformStaff()) return property;
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null || !tenantId.equals(property.getTenantId())) {
            throw new HodiException("That listing belongs to another organisation.",
                    HttpStatus.FORBIDDEN);
        }
        return property;
    }
}
