package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The channels — what money <em>can</em> be taken by, before any organisation says where it lands.
 *
 * <p>Read-mostly on purpose. A channel is a fact about a gateway, so rows arrive by migration; this service
 * renames them, reorders them and turns them on and off. There is no create: a row with no provider is
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
    private final ConfigurationService configs;
    /** The same AES-256-GCM that protects a secret setting, for the secrets in a channel's own config. */
    private final com.hodi.common.EncryptionUtil crypto;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PagedResponse<ChannelResponse> list(ChannelListRequest request) {
        Specification<PaymentType> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("category", blankToNull(request.getCategory())),
                offeredProviders(),
                SearchSpecs.statusIn(request.effectiveStatuses()));
        Map<Long, Long> inUse = scopedCounts(AuthContext.require());
        var page = types.findAll(spec, request.toPageable(Sort.by("sortOrder", "id")));
        return PagedResponse.from(page, t -> toResponse(t, inUse.getOrDefault(t.getId(), 0L)));
    }

    /**
     * One channel's configuration, for the screen that fills it in.
     *
     * <p>Driven by the channel's own descriptor rather than by what happens to be stored, so a field the
     * bank has added since appears empty and asking to be filled instead of not appearing at all.
     */
    @Transactional(readOnly = true)
    public PaymentTypeDtos.ChannelConfiguration configurationOf(String hashId) {
        PaymentType type = require(hashId);
        var fields = ChannelConfig.describe(type.getRequiredConfigFields(), type.getConfig(), crypto);
        var missing = ChannelConfig.missing(type.getRequiredConfigFields(), type.getConfig());
        return new PaymentTypeDtos.ChannelConfiguration(
                HashIdUtil.encodeId(type.getId()), type.getCode(), type.getName(),
                type.getProviderName(), fields, missing, missing.isEmpty());
    }

    /**
     * Saves it, keeping the secrets the form did not change.
     *
     * <p>Platform-only, like every other write here: where a channel points is shared by every organisation
     * on the platform, so pointing it somewhere else is not one organisation's decision.
     */
    @Transactional
    public PaymentTypeDtos.ChannelConfiguration configure(
            String hashId, PaymentTypeDtos.SaveChannelConfiguration request) {
        PaymentType type = require(hashId);
        String before = snapshot(type);
        type.setConfig(ChannelConfig.merge(type.getRequiredConfigFields(), type.getConfig(),
                request == null ? null : request.values(), crypto));
        type.setUpdatedBy(AuthContext.username());
        PaymentType saved = types.save(type);

        // The values are never audited: half of them are credentials, and the audit trail is the one table
        // designed to be widely readable. That it was configured, by whom, is the part worth keeping.
        audit.record(AppConstant.ACTION_UPDATE, "PaymentType", saved.getId(), before,
                saved.getCode() + " configuration updated");
        return configurationOf(hashId);
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
        CoopChannel.Category category = t.channelCategory();
        // Cash and cheque declare no fields, so they are configured by having nothing to configure.
        java.util.List<String> missing = ChannelConfig.missing(t.getRequiredConfigFields(), t.getConfig());
        return new ChannelResponse(
                HashIdUtil.encodeId(t.getId()), t.getCode(), t.getName(), t.getDescription(),
                t.getProviderName(), t.getProviderType(), t.getCategory(), category.renderAs(),
                t.getMethod(), PaymentMethods.label(t.getMethod()),
                t.isElectronic(), t.isAccountBased(), t.isRequiresShortCode(),
                t.getSortOrder(), t.getStatus(), t.getStatusFlag(), inUse,
                missing.isEmpty(), missing);
    }

    private static String snapshot(PaymentType t) {
        return t.getCode() + " " + t.getName() + " order=" + t.getSortOrder() + " status=" + t.getStatus();
    }

    /**
     * Restricts the catalogue to the gateway providers this deployment actually sells through.
     *
     * <p>The table was seeded with every bank the reference gateway fronts, which is right for a table
     * describing a gateway and wrong for a screen: four banks' channels offered when one of them is the
     * bank we take money through. The list is {@link ConfigKey#PAYMENT_PROVIDERS}, so widening it later is an edit rather than a
     * release.
     *
     * <p>A channel with no provider passes unconditionally, and that is the reason this is a predicate
     * rather than a plain equals. Cash and cheque belong to no gateway, and filtering on provider name
     * alone would take the two methods that always work off every screen.
     *
     * <p>Null when nothing is configured, so an empty setting adds no predicate rather than matching
     * nothing — {@code SearchSpecs.allOf} drops nulls, which is what makes that the no-op.
     */
    private Specification<PaymentType> offeredProviders() {
        List<String> offered = offeredProviderNames(configs);
        if (offered.isEmpty()) return null;
        return (root, query, cb) -> cb.or(
                cb.isNull(root.get("providerType")),
                root.get("providerName").in(offered));
    }

    /**
     * The configured allow-list, split and trimmed. Empty means no restriction.
     *
     * <p>Shared with {@code PaymentAccountService}, which asks the same question when it offers the channels
     * an organisation may be given: a catalogue that hides a bank while the account form still offers it
     * would be worse than not filtering at all.
     */
    static List<String> offeredProviderNames(ConfigurationService configs) {
        String configured = configs.getString(ConfigKey.PAYMENT_PROVIDERS);
        if (configured == null || configured.isBlank()) return List.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
