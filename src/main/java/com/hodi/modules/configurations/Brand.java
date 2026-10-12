package com.hodi.modules.configurations;

import com.hodi.enums.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The platform's name and its assistant's, read from configuration every time.
 *
 * <p>The name is a placeholder until the client chooses one, so nothing that people read may spell it out: a
 * sign-in code, a text message, an authenticator entry and the assistant's greeting all ask here, and renaming
 * the platform is {@code company.name} in Settings and nothing else.
 */
@Component
@RequiredArgsConstructor
public class Brand {

    private final ConfigurationService configs;

    /** The platform's name, or "the marketplace" while none is set. */
    public String name() {
        String n = configs.getString(ConfigKey.COMPANY_NAME);
        return n == null || n.isBlank() ? "the marketplace" : n.trim();
    }

    /** "The Acme team": who replies when nobody else does. */
    public String team() {
        String n = configs.getString(ConfigKey.COMPANY_NAME);
        return n == null || n.isBlank() ? "The platform's team" : "The " + n.trim() + " team";
    }

    /** What the assistant calls itself. */
    public String assistant() {
        String n = configs.getString(ConfigKey.ASSISTANT_NAME);
        return n == null || n.isBlank() ? "the assistant" : n.trim();
    }

    /** The start of a text message, so the reader knows who it is from before the link. */
    public String smsPrefix() {
        return name() + ": ";
    }
}
