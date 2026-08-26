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
import java.util.Map;
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

    /**
     * Personal data, masked rather than removed (NFR: "logs exclude PII").
     *
     * <h2>Why partial and not total</h2>
     *
     * <p>Credentials above are replaced outright: there is no version of a password that is useful in a log.
     * Personal data is different — a support engineer reading a request trace needs to know *which* buyer,
     * and a log where every address, phone number and email has become {@code ***} answers no question
     * anybody actually asks. So each of these keeps just enough to correlate two lines of a log with each
     * other, and not enough to be worth anything to somebody who has stolen the file.
     *
     * <h2>What is removed outright</h2>
     *
     * <p>Identity documents and exact addresses. An ID number, a KRA PIN and a licence number are the
     * things an impersonation is built from, and there is no partial form of them that is both safe and
     * useful. The exact address is the one thing the public marketplace deliberately withholds — writing it
     * into a log file would be the platform leaking through the back what it protects at the front.
     */
    private enum Mask { EMAIL, PHONE, NAME, FULL }

    private static final Map<String, Mask> PII_KEYS = Map.ofEntries(
            Map.entry("email", Mask.EMAIL),
            Map.entry("contactemail", Mask.EMAIL),
            Map.entry("buyeremail", Mask.EMAIL),
            Map.entry("bidderemail", Mask.EMAIL),
            Map.entry("owneremail", Mask.EMAIL),
            Map.entry("adminemail", Mask.EMAIL),

            Map.entry("phone", Mask.PHONE),
            Map.entry("contactphone", Mask.PHONE),
            Map.entry("buyerphone", Mask.PHONE),
            Map.entry("bidderphone", Mask.PHONE),
            Map.entry("ownerphone", Mask.PHONE),
            Map.entry("adminphone", Mask.PHONE),
            Map.entry("clientownerphone", Mask.PHONE),

            Map.entry("fullname", Mask.NAME),
            Map.entry("buyername", Mask.NAME),
            Map.entry("biddername", Mask.NAME),
            Map.entry("ratername", Mask.NAME),
            Map.entry("clientownername", Mask.NAME),
            Map.entry("typedname", Mask.NAME),

            // No partial form of these is both safe and useful.
            Map.entry("idnumber", Mask.FULL),
            Map.entry("id_number", Mask.FULL),
            Map.entry("krapin", Mask.FULL),
            Map.entry("kra_pin", Mask.FULL),
            Map.entry("licencenumber", Mask.FULL),
            Map.entry("licence_number", Mask.FULL),
            Map.entry("registrationnumber", Mask.FULL),
            Map.entry("registration_number", Mask.FULL),
            Map.entry("addressline", Mask.FULL),
            Map.entry("address_line", Mask.FULL),
            Map.entry("depositreference", Mask.FULL),
            // The address a signature was captured from is evidence held in one row on purpose; a copy of
            // it scattered through log files is a copy nobody is guarding.
            Map.entry("ipaddress", Mask.FULL),
            Map.entry("ip_address", Mask.FULL));

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
                    continue;
                }
                Mask mask = PII_KEYS.get(field.toLowerCase());
                JsonNode value = obj.get(field);
                if (mask != null && value != null && value.isTextual()) {
                    obj.put(field, applyMask(mask, value.asText()));
                } else {
                    redact(value);
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    /**
     * Enough to recognise the same person twice, and not enough to find them.
     *
     * <p>An email keeps its first and last local characters and its domain, a phone number its last three
     * digits, a name its first name and an initial. Two log lines about the same buyer still look like the
     * same buyer, which is the only thing the log was for.
     */
    private static String applyMask(Mask mask, String value) {
        if (value.isBlank()) return value;
        return switch (mask) {
            case FULL -> REDACTED;
            case EMAIL -> {
                int at = value.indexOf('@');
                if (at <= 0) yield REDACTED;
                String local = value.substring(0, at);
                String domain = value.substring(at);
                yield local.length() <= 2
                        ? "**" + domain
                        : local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
            }
            case PHONE -> {
                String digits = value.replaceAll("\\D", "");
                yield digits.length() <= 3 ? REDACTED : "•••••" + digits.substring(digits.length() - 3);
            }
            case NAME -> {
                String[] parts = value.trim().split("\\s+");
                if (parts.length == 1) yield parts[0];
                yield parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
            }
        };
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
