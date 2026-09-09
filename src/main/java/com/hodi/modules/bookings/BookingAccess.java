package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.modules.properties.Property;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Who may see, and who may sell, a home.
 *
 * <p>A booking is on a property, and a property is one of two things. A unit belongs to a development, and the
 * development's own rule decides — {@code DevelopmentVisibility}, which knows about lenders, selling
 * organisations and collaborators. A house belongs to a seller, and the tenant scope decides, as it does for
 * every other row that has a tenant. One place says which rule applies, so the booking service and the payment
 * service cannot drift apart on it.
 */
@Component
@RequiredArgsConstructor
public class BookingAccess {

    private final DevelopmentUnitRepository properties;
    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;

    /** The home a hash names, or not found. Any kind of row: the repository is not narrowed for {@code findById}. */
    public Property requireProperty(String hashId) {
        return properties.findById(HashIdUtil.decodeId(hashId))
                .filter(p -> p.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Property", hashId));
    }

    /** The home a booking is on. A booking whose home is gone is a broken record, said so. */
    public Property propertyOf(UnitBooking booking) {
        return properties.findById(booking.getPropertyId())
                .orElseThrow(() -> new HodiException("The home this booking is on no longer exists.",
                        HttpStatus.CONFLICT));
    }

    /** The project above a home, or null for a house. */
    public Development developmentOf(Property property) {
        if (property.getDevelopmentId() == null) return null;
        return developments.findById(property.getDevelopmentId()).orElse(null);
    }

    public boolean mayRead(Property property, UserPrincipal caller) {
        Development development = developmentOf(property);
        if (development != null) return visibility.mayRead(development, caller);
        if (caller.isPlatformStaff()) return true;
        Set<Long> visible = TenantScope.visibleIds();
        return property.getTenantId() != null && visible != null && visible.contains(property.getTenantId());
    }

    /** Not found rather than forbidden, like everywhere else a home is addressed. */
    public Property requireReadable(String hashId, UserPrincipal caller) {
        Property property = requireProperty(hashId);
        if (!mayRead(property, caller)) throw new ResourceNotFoundException("Property", hashId);
        return property;
    }

    /**
     * Whether the caller may book, sell or take money for this home.
     *
     * <p>A unit: the development's unit-writing rule. A house: the seller's own staff, or the platform. A
     * lender may read a partner's house through the tenant scope but does not sell it for them.
     */
    public void assertMayWrite(Property property, UserPrincipal caller) {
        Development development = developmentOf(property);
        if (development != null) {
            visibility.assertMayWriteUnits(development, caller);
            return;
        }
        if (caller.isPlatformStaff()) return;
        if (caller.getTenantId() == null || !caller.getTenantId().equals(property.getTenantId())) {
            throw new HodiException("That listing belongs to another organisation.", HttpStatus.FORBIDDEN);
        }
    }
}
