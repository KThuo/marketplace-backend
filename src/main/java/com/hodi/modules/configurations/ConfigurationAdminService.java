package com.hodi.modules.configurations;

import com.hodi.common.AppConstant;
import com.hodi.common.EncryptionUtil;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The write side of configuration: the global settings screen, the per-organisation override screen, and the
 * change log.
 *
 * <p>Reads go through {@link ConfigurationService}, which is cached. Everything here evicts what it changes —
 * and evicts it <em>under the same tenant binding</em> as the write, because the tenant cache key includes the
 * organisation. Evicting under the wrong binding leaves the stale value serving and looks like the save
 * silently failed.
 *
 * <h2>Masking</h2>
 *
 * <p>A secret is never returned in the clear by the list or by a single read. It comes back as a fixed mask,
 * and revealing it is a separate call behind its own permission, which is audited. The mask is a constant
 * rather than a length-preserving blob on purpose: the length of a credential is itself a hint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationAdminService {

    /** What a secret looks like until somebody with the permission asks for it. */
    private static final String MASK = "••••••••";

    private final ConfigurationRepository repository;
    private final TenantConfigurationRepository tenantRepository;
    private final ConfigurationLogRepository logRepository;
    private final ConfigurationService configs;
    private final EncryptionUtil encryption;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ConfigurationResponse(
            String id,
            String configKey,
            String value,
            String valueType,
            String category,
            String label,
            String description,
            boolean secret,
            boolean overridable,
            boolean editable,
            /** Which layer the value currently resolves from for the caller: GLOBAL or TENANT. */
            String resolvedLayer,
            Integer status,
            String statusFlag,
            OffsetDateTime updatedAt,
            String updatedBy) {}

    /** One overridable key, as the organisation's own settings screen shows it. */
    public record OverrideResponse(
            String configKey,
            String label,
            String description,
            String category,
            String valueType,
            boolean secret,
            String globalValue,
            String overrideValue,
            boolean overridden) {}

    public record UpdateConfigRequest(String value, String reason) {}

    public record ConfigurationLogResponse(
            String id, String configKey, String scope, String tenantName,
            String previousValue, String newValue, String reason,
            String actorUsername, OffsetDateTime createdAt) {}

    // ── global layer ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ConfigurationResponse> list(PagedDataRequest request, String category) {
        Specification<Configuration> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("category", category == null || category.isBlank() ? null : category));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "category", "configKey")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return repository.findCategories();
    }

    @Transactional
    public ConfigurationResponse updateGlobal(String hashId, UpdateConfigRequest request) {
        Configuration config = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Setting", hashId));
        if (!config.isEditable()) {
            throw new HodiException("That setting is not editable.", HttpStatus.CONFLICT);
        }

        String previousStored = config.getConfigValue();
        String newStored = config.isSecret()
                ? encryption.encryptString(request.value())
                : request.value();

        config.setConfigValue(newStored);
        config.setStatus(AppConstant.STATUS_EDITED);
        config.setStatusFlag(AppConstant.FLAG_EDITED);
        config.setUpdatedBy(AuthContext.username());
        Configuration saved = repository.save(config);

        configs.evict(saved.getConfigKey());
        /*
         * The tenant layer goes too, and this is not belt-and-braces.
         *
         * ConfigurationService resolves `tenant override ?? global`, and it checks the *global* row's
         * overridable flag before it will look for an override at all. So a global change can alter which
         * layer wins for an organisation that has an override — most sharply when a key stops being
         * overridable, at which point every existing override should go inert immediately. Their cached
         * values would otherwise keep being served until they aged out.
         *
         * Dropping the whole tenant layer rather than one organisation's is deliberate: there is no way to
         * enumerate which organisations hold an override of this key without scanning for them, and a
         * cold cache costs one query per read while a stale one costs correctness.
         */
        configs.evictAllTenantValues();
        writeLog(saved.getConfigKey(), "GLOBAL", null, previousStored, newStored,
                request.reason(), saved.isSecret());
        audit.record(AppConstant.AUDIT_CONFIG_UPDATE, "Configuration", saved.getId(),
                maskIfSecret(previousStored, saved.isSecret()),
                maskIfSecret(newStored, saved.isSecret()));
        log.info("Global config {} changed by {}", saved.getConfigKey(), AuthContext.username());
        return toResponse(saved);
    }

    /**
     * Reveals a secret in the clear, once, behind its own permission.
     *
     * <p>Audited as a read, which is unusual and deliberate: for a credential, "who looked at this" is as
     * interesting as "who changed it".
     */
    @Transactional
    public String reveal(String configKey) {
        Configuration config = repository.findByConfigKey(configKey)
                .orElseThrow(() -> new ResourceNotFoundException("Setting", configKey));
        if (!config.isSecret()) return config.getConfigValue();

        audit.record("REVEAL_SECRET", "Configuration", config.getId(), null, configKey);
        log.warn("Secret {} revealed to {}", configKey, AuthContext.username());
        return config.getConfigValue() == null ? null
                : encryption.decryptString(config.getConfigValue());
    }

    // ── tenant override layer ─────────────────────────────────────────────────

    /**
     * The overridable keys, with this organisation's value where it has one.
     *
     * <p>Shows the global value alongside, so somebody can see what they are diverging from — a screen that
     * only showed the override would make "am I on the platform's gateway or my own" unanswerable without
     * asking support.
     */
    @Transactional(readOnly = true)
    public List<OverrideResponse> overrides() {
        Long tenantId = requireTenant();
        var mine = tenantRepository.findByTenantId(tenantId);

        return repository
                .findByOverridableTrueAndStatusNotOrderByCategoryAscConfigKeyAsc(
                        AppConstant.STATUS_DELETED)
                .stream()
                .map(global -> {
                    var override = mine.stream()
                            .filter(o -> o.getConfigKey().equals(global.getConfigKey()))
                            .findFirst();
                    boolean overridden = override
                            .map(o -> AppConstant.isLive(o.getStatus())
                                    && o.getConfigValue() != null && !o.getConfigValue().isBlank())
                            .orElse(false);
                    return new OverrideResponse(
                            global.getConfigKey(),
                            global.getLabel(),
                            global.getDescription(),
                            global.getCategory(),
                            global.getValueType(),
                            global.isSecret(),
                            maskIfSecret(global.getConfigValue(), global.isSecret()),
                            overridden
                                    ? maskIfSecret(override.get().getConfigValue(), global.isSecret())
                                    : null,
                            overridden);
                })
                .toList();
    }

    /**
     * Writes this organisation's override of one key.
     *
     * <p>A non-overridable key is <strong>refused</strong>, not silently ignored. Silently ignoring it would
     * leave somebody believing they had changed their session timeout, and platform policy keys are exactly
     * the ones where that belief is dangerous.
     */
    @Transactional
    public void setOverride(String configKey, UpdateConfigRequest request) {
        Long tenantId = requireTenant();
        Configuration global = repository.findByConfigKey(configKey)
                .orElseThrow(() -> new ResourceNotFoundException("Setting", configKey));

        if (!global.isOverridable()) {
            throw new HodiException(
                    "\"%s\" is set by the platform and cannot be overridden."
                            .formatted(global.getLabel() == null ? configKey : global.getLabel()),
                    HttpStatus.FORBIDDEN);
        }

        var existing = tenantRepository.findByTenantIdAndConfigKey(tenantId, configKey);
        String previousStored = existing.map(TenantConfiguration::getConfigValue).orElse(null);
        String newStored = global.isSecret()
                ? encryption.encryptString(request.value())
                : request.value();

        TenantConfiguration row = existing.orElseGet(() -> TenantConfiguration.builder()
                .tenantId(tenantId)
                .configKey(configKey)
                .createdBy(AuthContext.username())
                .build());
        row.setConfigValue(newStored);
        row.setValueType(global.getValueType());
        row.setCategory(global.getCategory());
        // Mirrored from the global row rather than joined, because it decides whether this value is
        // encrypted and the decryption path must not need a second lookup to find out.
        row.setSecret(global.isSecret());
        row.setStatus(AppConstant.STATUS_ACTIVE);
        row.setStatusFlag(AppConstant.FLAG_ACTIVE);
        row.setUpdatedBy(AuthContext.username());
        tenantRepository.save(row);

        // Evicted under the current tenant binding — the cache key includes it.
        configs.evictTenantValue(configKey);
        writeLog(configKey, "TENANT", tenantId, previousStored, newStored,
                request.reason(), global.isSecret());
        audit.record(AppConstant.AUDIT_CONFIG_UPDATE, "TenantConfiguration", row.getId(),
                maskIfSecret(previousStored, global.isSecret()),
                maskIfSecret(newStored, global.isSecret()));
    }

    /**
     * Clears an override, so the key falls back to the global value.
     *
     * <p>Deletes the row rather than copying the global value into it. Copying would freeze today's global
     * value into this organisation's row, and a later platform-wide change would then silently stop reaching
     * them — the failure being avoided is one nobody would notice for months.
     */
    @Transactional
    public void clearOverride(String configKey) {
        Long tenantId = requireTenant();
        var existing = tenantRepository.findByTenantIdAndConfigKey(tenantId, configKey);
        if (existing.isEmpty()) return;

        String previousStored = existing.get().getConfigValue();
        boolean secret = existing.get().isSecret();
        tenantRepository.delete(existing.get());

        configs.evictTenantValue(configKey);
        writeLog(configKey, "TENANT", tenantId, previousStored, null,
                "override cleared", secret);
        audit.record(AppConstant.AUDIT_CONFIG_UPDATE, "TenantConfiguration", null,
                maskIfSecret(previousStored, secret), "cleared");
    }

    // ── change log ────────────────────────────────────────────────────────────

    /**
     * The change log.
     *
     * <p>Scoped by who is asking: platform staff see global changes and every organisation's, while an
     * organisation sees its own tenant-layer rows and the global ones that affect it. A seller seeing another
     * seller's gateway change would be a leak of both the fact and the timing.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ConfigurationLogResponse> log(PagedDataRequest request) {
        Long tenantId = TenantContext.getTenantId();
        Specification<ConfigurationLog> spec = SearchSpecs.allOf(
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.between("createdAt",
                        request.getFrom() == null ? null
                                : request.getFrom().atStartOfDay().atOffset(
                                        OffsetDateTime.now().getOffset()),
                        request.getTo() == null ? null
                                : request.getTo().atTime(java.time.LocalTime.MAX).atOffset(
                                        OffsetDateTime.now().getOffset())),
                visibleLog(tenantId));
        var page = logRepository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toLogResponse);
    }

    private Specification<ConfigurationLog> visibleLog(Long tenantId) {
        if (tenantId == null) return null;
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("tenantId"), tenantId),
                cb.isNull(root.get("tenantId")));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new HodiException(
                    "Only a seller organisation has settings of its own to override.",
                    HttpStatus.BAD_REQUEST);
        }
        return tenantId;
    }

    /**
     * Writes the change log, masking secret values.
     *
     * <p>The log's job is to record that a credential changed, who changed it and why — not to become a
     * second, unencrypted copy of every credential the platform has ever held.
     */
    private void writeLog(String configKey, String scope, Long tenantId,
                          String previous, String next, String reason, boolean secret) {
        logRepository.save(ConfigurationLog.builder()
                .configKey(configKey)
                .scope(scope)
                .tenantId(tenantId)
                .tenantName(TenantContext.getTenantName())
                .previousValue(maskIfSecret(previous, secret))
                .newValue(maskIfSecret(next, secret))
                .reason(reason)
                .actorUsername(AuthContext.username())
                .build());
    }

    private static String maskIfSecret(String value, boolean secret) {
        if (value == null || value.isBlank()) return null;
        return secret ? MASK : value;
    }

    private ConfigurationResponse toResponse(Configuration config) {
        return new ConfigurationResponse(
                HashIdUtil.encodeId(config.getId()),
                config.getConfigKey(),
                maskIfSecret(config.getConfigValue(), config.isSecret()),
                config.getValueType(),
                config.getCategory(),
                config.getLabel(),
                config.getDescription(),
                config.isSecret(),
                config.isOverridable(),
                config.isEditable(),
                configs.resolvedLayer(config.getConfigKey()),
                config.getStatus(),
                config.getStatusFlag(),
                config.getUpdatedAt(),
                config.getUpdatedBy());
    }

    private ConfigurationLogResponse toLogResponse(ConfigurationLog row) {
        return new ConfigurationLogResponse(
                HashIdUtil.encodeId(row.getId()),
                row.getConfigKey(),
                row.getScope(),
                row.getTenantName(),
                row.getPreviousValue(),
                row.getNewValue(),
                row.getReason(),
                row.getActorUsername(),
                row.getCreatedAt());
    }
}
