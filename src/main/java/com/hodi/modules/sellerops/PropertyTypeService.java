package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * The kinds of property, and which questions each one is asked (M13).
 *
 * <p>The list was six values in a Java enum, three Vue arrays and a form's validation. Adding one meant a
 * deploy in two repositories, and the fields a form asked for came from a switch nobody could see.
 *
 * <p>Like the vendor taxonomy, nothing deletes. A type with listings under it can only be deactivated —
 * which stops it being offered to new listings and leaves the existing ones exactly where they are.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyTypeService {

    private final PropertyTypeConfigRepository repository;
    private final PropertyRepository properties;
    private final AuditService audit;

    public record TypeResponse(
            String code,
            String name,
            String description,
            String icon,
            int sortOrder,
            boolean hasBedrooms,
            boolean hasBathrooms,
            boolean hasFloorArea,
            boolean hasPlotArea,
            boolean hasYearBuilt,
            boolean lettable,
            /** How many listings are filed under it. What makes deactivation a considered act. */
            long listingCount,
            Integer status,
            String statusFlag) {}

    public record SaveTypeRequest(
            @NotBlank(message = "A code is required") @Size(max = 32) String code,
            @NotBlank(message = "A name is required") @Size(max = 120) String name,
            String description,
            @Size(max = 8) String icon,
            Integer sortOrder,
            Boolean hasBedrooms,
            Boolean hasBathrooms,
            Boolean hasFloorArea,
            Boolean hasPlotArea,
            Boolean hasYearBuilt,
            Boolean lettable) {}

    /** Everything, for the platform's own screen. */
    @Transactional(readOnly = true)
    public List<TypeResponse> all() {
        return repository.findAllLive().stream().map(this::toResponse).toList();
    }

    /** What a listing form may choose from, and what each choice implies. Public — the form is. */
    @Transactional(readOnly = true)
    public List<TypeResponse> active() {
        return repository.findAllActive().stream().map(this::toResponse).toList();
    }

    @Transactional
    public TypeResponse create(SaveTypeRequest request) {
        String code = normalise(request.code());
        if (repository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A property type with the code \"" + code
                    + "\" already exists");
        }
        PropertyTypeConfig config = PropertyTypeConfig.builder()
                .code(code)
                .createdBy(AuthContext.username())
                .build();
        apply(config, request);
        PropertyTypeConfig saved = repository.save(config);
        audit.record(AppConstant.ACTION_CREATE, "PropertyTypeConfig", saved.getId(), null,
                snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public TypeResponse update(String code, SaveTypeRequest request) {
        PropertyTypeConfig config = require(code);
        String before = snapshot(config);
        // The code is not editable: every listing already filed under it points at this string.
        apply(config, request);
        config.setStatus(AppConstant.STATUS_EDITED);
        config.setStatusFlag(AppConstant.FLAG_EDITED);
        config.setUpdatedBy(AuthContext.username());
        PropertyTypeConfig saved = repository.save(config);
        audit.record(AppConstant.ACTION_UPDATE, "PropertyTypeConfig", saved.getId(), before,
                snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public TypeResponse setActive(String code, boolean active) {
        PropertyTypeConfig config = require(code);
        String before = snapshot(config);
        config.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        config.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        config.setUpdatedBy(AuthContext.username());
        PropertyTypeConfig saved = repository.save(config);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "PropertyTypeConfig", saved.getId(), before, snapshot(saved));
        if (!active) {
            long affected = properties.countByPropertyType(saved.getCode());
            if (affected > 0) {
                log.info("Property type {} withdrawn with {} listing(s) still filed under it",
                        code, affected);
            }
        }
        return toResponse(saved);
    }

    /** For the listing service: the type must exist and still be offered. */
    public PropertyTypeConfig requireUsable(String code) {
        PropertyTypeConfig config = require(code);
        if (!AppConstant.isLive(config.getStatus())) {
            throw new HodiException("That kind of property is no longer offered — choose another.",
                    HttpStatus.CONFLICT);
        }
        return config;
    }

    private PropertyTypeConfig require(String code) {
        return repository.findByCodeIgnoreCase(normalise(code))
                .orElseThrow(() -> new ResourceNotFoundException("Property type", code));
    }

    private void apply(PropertyTypeConfig config, SaveTypeRequest request) {
        config.setName(request.name().trim());
        config.setDescription(blankToNull(request.description()));
        config.setIcon(blankToNull(request.icon()));
        if (request.sortOrder() != null) config.setSortOrder(request.sortOrder());
        if (request.hasBedrooms() != null) config.setHasBedrooms(request.hasBedrooms());
        if (request.hasBathrooms() != null) config.setHasBathrooms(request.hasBathrooms());
        if (request.hasFloorArea() != null) config.setHasFloorArea(request.hasFloorArea());
        if (request.hasPlotArea() != null) config.setHasPlotArea(request.hasPlotArea());
        if (request.hasYearBuilt() != null) config.setHasYearBuilt(request.hasYearBuilt());
        if (request.lettable() != null) config.setLettable(request.lettable());
    }

    private TypeResponse toResponse(PropertyTypeConfig c) {
        return new TypeResponse(c.getCode(), c.getName(), c.getDescription(), c.getIcon(),
                c.getSortOrder(), c.isHasBedrooms(), c.isHasBathrooms(), c.isHasFloorArea(),
                c.isHasPlotArea(), c.isHasYearBuilt(), c.isLettable(),
                properties.countByPropertyType(c.getCode()), c.getStatus(), c.getStatusFlag());
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static String snapshot(PropertyTypeConfig c) {
        return "{\"code\":\"%s\",\"name\":\"%s\",\"status\":%d}".formatted(
                c.getCode(), c.getName(), c.getStatus());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
