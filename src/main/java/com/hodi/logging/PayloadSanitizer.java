package com.hodi.logging;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Set;

/**
 * Strips sensitive fields from any object tree before it lands in the log file or audit JSON.
 *
 * <p>Values for known-sensitive keys (case-insensitive, exact or substring match) are replaced
 * with {@code "***"}. Sanitization is non-destructive — produces a new JSON tree.
 *
 * <p>Uses an internal Jackson 2 {@link ObjectMapper} rather than relying on Spring's
 * auto-wired Jackson 3 one — we need mutable {@link ObjectNode}s to redact values in place.
 */
@Slf4j
@Component
public class PayloadSanitizer {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password",
            "currentpassword",
            "newpassword",
            "confirmpassword",
            "oldpassword",
            "secret",
            "totpsecret",
            "totp_secret",
            "otpcode",
            "otp",
            "apikey",
            "api_key",
            "token",
            "accesstoken",
            "refreshtoken",
            "access_token",
            "refresh_token",
            "authorization",
            "resetcode",
            "reset_code",
            "clientsecret",
            "client_secret",
            "privatekey",
            "private_key",
            "credentials",
            // Payment / banking — a gateway callback or bank import must never land in a log file.
            "pan",
            "cardnumber",
            "card_number",
            "cvv",
            "cvc",
            "pin",
            "iban",
            "accountnumber",
            "account_number",
            "consumersecret",
            "consumer_secret",
            "passkey"
    );

    private static final String REDACTED = "***";

    private final ObjectMapper objectMapper = buildMapper();

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    public String sanitize(Object payload) {
        if (payload == null) return "null";
        try {
            JsonNode tree = objectMapper.valueToTree(payload);
            redact(tree);
            return tree.toString();
        } catch (Exception e) {
            log.debug("Failed to sanitize payload (returning class name)", e);
            return payload.getClass().getSimpleName() + "(unserializable)";
        }
    }

    private void redact(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> fields = obj.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (isSensitive(field)) {
                    obj.put(field, REDACTED);
                } else {
                    redact(obj.get(field));
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    private boolean isSensitive(String fieldName) {
        String lower = fieldName.toLowerCase();
        if (SENSITIVE_KEYS.contains(lower)) return true;
        for (String key : SENSITIVE_KEYS) {
            if (lower.contains(key)) return true;
        }
        return false;
    }
}
