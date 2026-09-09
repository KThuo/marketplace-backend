package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.payments.PaymentTypeDtos.ChannelListRequest;
import com.hodi.modules.payments.PaymentTypeDtos.ChannelResponse;
import com.hodi.modules.payments.PaymentTypeDtos.UpdateChannelRequest;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The channels — what money <em>can</em> be taken by, before any organisation says where it lands.
 *
 * <p>Read-mostly on purpose. A channel is a fact about a gateway, so rows arrive by migration; this service
 * renames them, reorders them and turns them on and off. There is no create: a row with no Pesi provider is
 * a payment method that cannot collect anything, offered until somebody notices.
 *
 * <h2>Only the platform writes it</h2>
 *
 * <p>The catalogue is shared by every organisation on the platform, so turning a method on turns it on for
 * everybody. {@code PAYMENT_CATALOGUE_MANAGE} is platform-only and the controller checks it, but the rule is
 * stated here as well: a permission row can be granted by mistake, and this is the write it would matter on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTypeService {

    private final PaymentTypeRepository types;
    private final PaymentAccountRepository accounts;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PagedResponse<ChannelResponse> list(ChannelListRequest request) {
        Specification<PaymentType> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("category", blankToNull(request.getCategory())),
                SearchSpecs.statusIn(request.effectiveStatuses()));
        Map<Long, Long> inUse = scopedCounts(AuthContext.require());
        var page = types.findAll(spec, request.toPageable(Sort.by("sortOrder", "id")));
        return PagedResponse.from(page, t -> toResponse(t, inUse.getOrDefault(t.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public ChannelResponse find(String hashId) {
        PaymentType type = require(hashId);
        return toResponse(type, scopedCounts(AuthContext.require()).getOrDefault(type.getId(), 0L));
    }

    /**
     * Rename or reorder. Behaviour is not editable.
     *
     * <p>Whether a channel is a phone prompt or an inbound credit is the gateway's fact, not anybody's
     * preference, and a screen that could change it would let somebody turn an IPN into an STK push and break
     * every reconciliation behind it.
     */
    @Transactional
    public ChannelResponse update(String hashId, UpdateChannelRequest request) {
        requirePlatform();
        PaymentType type = require(hashId);
        String before = snapshot(type);
        type.setName(request.name().trim());
        type.setDescription(blankToNull(request.description()));
        if (request.sortOrder() != null) type.setSortOrder(request.sortOrder());
        type.setUpdatedBy(AuthContext.username());
        PaymentType saved = types.save(type);
        audit.record(AppConstant.ACTION_UPDATE, "PaymentType", saved.getId(), before, snapshot(saved));
        return toResponse(saved, accounts.countOnChannel(saved.getId()));
    }

    /**
     * Turn a channel on or off for the whole platform.
     *
     * <p>Off means it cannot be assigned to anybody new. Accounts already configured on it keep working,
     * because switching off a channel is a decision about what to offer next — not a decision to stop
     * collecting money an organisation is already banking.
     */
    @Transactional
    public String setStatus(String hashId, boolean active) {
        requirePlatform();
        PaymentType type = require(hashId);
        long configured = accounts.countOnChannel(type.getId());
        String before = snapshot(type);

        type.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        type.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        type.setUpdatedBy(AuthContext.username());
        types.save(type);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "PaymentType", type.getId(), before, snapshot(type));
        log.info("{} switched {} {}", AuthContext.username(), type.getName(), active ? "on" : "off");

        if (active) return type.getName() + " can now be assigned to organisations.";
        return configured == 0
                ? type.getName() + " is off. It is no longer offered."
                : type.getName() + " is off for new set-ups. The " + configured + " account"
                        + (configured == 1 ? "" : "s") + " already configured on it keep working.";
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void requirePlatform() {
        UserPrincipal caller = AuthContext.require();
        if (!caller.isPlatformStaff()) {
            throw new HodiException("Payment methods are shared by every organisation on the platform, so only "
                    + "a platform administrator can change one. Set up an account on one instead — that is "
                    + "yours alone.", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * How many accounts sit on each channel, within the caller's own organisation.
     *
     * <p>The catalogue is platform-wide and meant to be; the count is not. Unscoped, it told a seller how many
     * accounts other organisations had configured on each channel.
     */
    private Map<Long, Long> scopedCounts(UserPrincipal caller) {
        List<Object[]> rows;
        if (caller.isPlatformStaff()) rows = accounts.countByChannel();
        else if (caller.getInstitutionId() != null) {
            rows = accounts.countByChannelForInstitution(caller.getInstitutionId());
        } else if (caller.getTenantId() != null) {
            rows = accounts.countByChannelForTenant(caller.getTenantId());
        } else {
            return Map.of();
        }
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : rows) out.put((Long) row[0], (Long) row[1]);
        return out;
    }

    private PaymentType require(String hashId) {
        return types.findById(HashIdUtil.decodeId(hashId))
                .filter(t -> t.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method", hashId));
    }

    static ChannelResponse toResponse(PaymentType t, long inUse) {
        PesiChannel.Category category = t.channelCategory();
        return new ChannelResponse(
                HashIdUtil.encodeId(t.getId()), t.getCode(), t.getName(), t.getDescription(),
                t.getProviderName(), t.getPesiProviderType(), t.getCategory(), category.renderAs(),
                t.getMethod(), PaymentMethods.label(t.getMethod()),
                t.isElectronic(), t.isAccountBased(), t.isRequiresShortCode(),
                t.getSortOrder(), t.getStatus(), t.getStatusFlag(), inUse);
    }

    private static String snapshot(PaymentType t) {
        return t.getCode() + " " + t.getName() + " order=" + t.getSortOrder() + " status=" + t.getStatus();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
