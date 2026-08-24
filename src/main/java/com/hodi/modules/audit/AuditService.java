package com.hodi.modules.audit;

import com.hodi.logging.PayloadSanitizer;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the audit trail. Service-invoked rather than an interceptor: only the service knows the
 * before state, which is the half of the record that matters when reconstructing what changed.
 *
 * <p>Every write runs in its own transaction. An audit row must survive the rollback of the operation
 * it describes — a failed or rejected attempt is often exactly what someone is looking for later, and
 * joining the doomed transaction would erase it.
 *
 * <p>Payloads pass through {@link PayloadSanitizer}, so a password or API key in a request body never
 * reaches the audit table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final PayloadSanitizer sanitizer;
    private final TransactionTemplate newTransaction;

    public void record(String operation, String entity, Long entityId, Object before, Object after) {
        record(operation, entity, entityId, before, after, "SUCCESS");
    }

    public void recordFailure(String operation, String entity, Long entityId, String reason) {
        record(operation, entity, entityId, null, reason, "FAILED");
    }

    public void record(String operation, String entity, Long entityId,
                       Object before, Object after, String outcome) {
        try {
            UserPrincipal actor = AuthContext.current().orElse(null);
            AuditLog row = AuditLog.builder()
                    .actionId(MDC.get("actionId"))
                    .tenantId(TenantContext.getTenantId())
                    .actorUserId(actor == null ? null : actor.getUserId())
                    .actorUsername(actor == null ? "system" : actor.getUsername())
                    .actorUserType(actor == null ? null : actor.getUserTypeCode())
                    .actorClass(actor == null ? null : actor.getActorClass())
                    .institutionId(actor == null ? null : actor.getInstitutionId())
                    .operation(operation)
                    .entity(entity)
                    .entityId(entityId)
                    .outcome(outcome)
                    .beforePayload(before == null ? null : sanitizer.sanitize(before))
                    .afterPayload(after == null ? null : sanitizer.sanitize(after))
                    .changedFields(diff(before, after))
                    .build();
            newTransaction.executeWithoutResult(status -> repository.save(row));
        } catch (RuntimeException e) {
            // Auditing must never be the reason a legitimate operation fails. Losing a row is bad;
            // rejecting the user's work because we could not describe it is worse.
            log.error("Failed to write audit row for {} {} {}", operation, entity, entityId, e);
        }
    }

    /**
     * Field names whose sanitized JSON differs between the two states. Shallow and string-based on
     * purpose: this is a reviewer's index into the full before/after payloads, not a merge tool.
     */
    private String diff(Object before, Object after) {
        if (before == null || after == null) return null;
        try {
            String b = sanitizer.sanitize(before);
            String a = sanitizer.sanitize(after);
            if (b.equals(a)) return "[]";
            List<String> changed = new ArrayList<>();
            for (String field : fieldNames(a)) {
                if (!valueOf(b, field).equals(valueOf(a, field))) changed.add(field);
            }
            return changed.isEmpty() ? "[]" : "[\"" + String.join("\",\"", changed) + "\"]";
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> fieldNames(String json) {
        List<String> names = new ArrayList<>();
        int i = 0;
        while ((i = json.indexOf("\"", i)) >= 0) {
            int end = json.indexOf("\"", i + 1);
            if (end < 0) break;
            String token = json.substring(i + 1, end);
            if (end + 1 < json.length() && json.charAt(end + 1) == ':') names.add(token);
            i = end + 1;
        }
        return names;
    }

    private String valueOf(String json, String field) {
        int key = json.indexOf("\"" + field + "\":");
        if (key < 0) return "";
        int start = key + field.length() + 3;
        int comma = json.indexOf(',', start);
        int brace = json.indexOf('}', start);
        int end = comma < 0 ? brace : (brace < 0 ? comma : Math.min(comma, brace));
        return end < 0 ? json.substring(start) : json.substring(start, end);
    }
}
