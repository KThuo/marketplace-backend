package com.hodi.modules.beneficiaries;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.SaveTypeRequest;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.TypeResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The beneficiary types: read by anyone who names a payee, written by the platform. */
@Service
@RequiredArgsConstructor
public class BeneficiaryTypeService {

    private final BeneficiaryTypeRepository types;
    private final BeneficiaryRepository beneficiaries;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TypeResponse> available() {
        Map<Long, Long> inUse = inUse();
        return types.findAvailable().stream().map(t -> toResponse(t, inUse)).toList();
    }

    @Transactional(readOnly = true)
    public List<TypeResponse> all() {
        Map<Long, Long> inUse = inUse();
        return types.findAllLive().stream().map(t -> toResponse(t, inUse)).toList();
    }

    @Transactional
    public TypeResponse create(SaveTypeRequest request) {
        String code = code(request.code());
        if (types.countByCodeExcept(code, -1L) > 0) {
            throw new HodiException("There is already a type coded " + code + ".", HttpStatus.CONFLICT);
        }
        BeneficiaryType saved = types.save(BeneficiaryType.builder()
                .code(code).name(request.name().trim())
                .description(blankToNull(request.description()))
                .sortOrder(request.sortOrder() == null ? 100 : request.sortOrder())
                .createdBy(AuthContext.username()).updatedBy(AuthContext.username())
                .build());
        audit.record(AppConstant.ACTION_CREATE, "BeneficiaryType", saved.getId(), null, code);
        return toResponse(saved, inUse());
    }

    @Transactional
    public TypeResponse update(String hashId, SaveTypeRequest request) {
        BeneficiaryType type = require(hashId);
        String code = code(request.code());
        if (types.countByCodeExcept(code, type.getId()) > 0) {
            throw new HodiException("There is already a type coded " + code + ".", HttpStatus.CONFLICT);
        }
        String before = type.getCode() + " " + type.getName();
        type.setCode(code);
        type.setName(request.name().trim());
        type.setDescription(blankToNull(request.description()));
        if (request.sortOrder() != null) type.setSortOrder(request.sortOrder());
        type.setUpdatedBy(AuthContext.username());
        BeneficiaryType saved = types.save(type);
        audit.record(AppConstant.ACTION_UPDATE, "BeneficiaryType", saved.getId(), before, code + " " + saved.getName());
        return toResponse(saved, inUse());
    }

    /** Suspended, never deleted: the beneficiaries already filed under it keep their name. */
    @Transactional
    public String setStatus(String hashId, boolean active) {
        BeneficiaryType type = require(hashId);
        type.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        type.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        type.setUpdatedBy(AuthContext.username());
        types.save(type);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "BeneficiaryType", type.getId(), null, type.getCode());
        long held = inUse().getOrDefault(type.getId(), 0L);
        return active
                ? type.getName() + " is offered again."
                : type.getName() + " is suspended. It is no longer offered"
                        + (held > 0 ? "; the " + held + " beneficiaries filed under it keep it." : ".");
    }

    private Map<Long, Long> inUse() {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : beneficiaries.countByType()) out.put((Long) row[0], (Long) row[1]);
        return out;
    }

    private BeneficiaryType require(String hashId) {
        return types.findById(HashIdUtil.decodeId(hashId))
                .filter(t -> t.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary type", hashId));
    }

    private static String code(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (code.isBlank()) throw new HodiException("Enter a code.", HttpStatus.BAD_REQUEST);
        return code;
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    static TypeResponse toResponse(BeneficiaryType t, Map<Long, Long> inUse) {
        return new TypeResponse(HashIdUtil.encodeId(t.getId()), t.getCode(), t.getName(), t.getDescription(),
                t.getSortOrder(), t.getStatus(), t.getStatusFlag(), inUse.getOrDefault(t.getId(), 0L));
    }
}
