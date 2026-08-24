package com.hodi.infra.notify;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.tenant.TenantContext;
import com.hodi.modules.tenants.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Works out the From address and display name for an outbound email.
 *
 * <p>Only the <strong>domain</strong> is configured. The local part is whoever is sending: the platform sends
 * as {@code hodi@<domain>}, a seller organisation as {@code <their slug>@<domain>}. Derived rather than
 * stored per organisation so a seller onboarded a minute ago already sends under their own name, with nothing
 * to configure and nothing to forget — and so changing the domain moves every address with it in one edit.
 *
 * <p>The domain is a tenant-overridable key, which is the escape hatch for a seller who owns their own domain
 * and has pointed its SPF and DKIM records at the gateway.
 *
 * <p><strong>Deployment note:</strong> every derived address sits on one domain, so that domain's SPF and
 * DKIM records must authorise the notify service, or all of this mail is spam-foldered together.
 *
 * <p>The display name is not configured either: it is the seller's own name when a tenant is bound and the
 * platform name otherwise. A seller's mail should say who it is from before it says which platform sent it,
 * and both names already exist.
 *
 * <p>The derivation is deliberately one-way. Nothing reads a From address back to identify an organisation —
 * the slug's own uniqueness constraint is what keeps addresses distinct, and it is enforced where it belongs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSender {

    /** The platform's own local part, for mail sent with no organisation bound. */
    private static final String PLATFORM_LOCAL_PART = "hodi";

    private final ConfigurationService configs;
    private final TenantRepository tenants;

    public record From(String address, String name) {}

    /**
     * The From identity for right now.
     *
     * <p>Resolved per send rather than cached, because it depends on the organisation bound to the current
     * request — the same JVM sends as the platform and as any number of sellers within a second of each other.
     */
    public From resolve() {
        String domain = normaliseDomain(configs.getString(ConfigKey.NOTIFY_EMAIL_DOMAIN));

        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new From(PLATFORM_LOCAL_PART + "@" + domain,
                    configs.getString(ConfigKey.COMPANY_NAME));
        }

        // The slug, not the name: a name has spaces and punctuation in it and would not survive being the
        // local part of an address. The slug is already constrained to be safe there.
        return tenants.findById(tenantId)
                .map(t -> new From(t.getSlug() + "@" + domain, t.getName()))
                .orElseGet(() -> {
                    // A bound tenant that does not resolve is a bug worth seeing, but not one worth failing a
                    // send over — the platform identity is a correct, if less personal, answer.
                    log.warn("Tenant {} is bound but has no row — sending as the platform", tenantId);
                    return new From(PLATFORM_LOCAL_PART + "@" + domain,
                            configs.getString(ConfigKey.COMPANY_NAME));
                });
    }

    /**
     * Falls back to the key's own default when unset, and strips a leading {@code @} and any casing.
     *
     * <p>Its own method so the result is effectively final and can be read from the lambda below — and
     * because a configured value arriving as {@code @hodi.io} rather than {@code hodi.io} is the sort of
     * thing somebody types once and nobody notices until every address has two at-signs in it.
     */
    private static String normaliseDomain(String configured) {
        String domain = (configured == null || configured.isBlank())
                ? ConfigKey.NOTIFY_EMAIL_DOMAIN.getDefaultValue()
                : configured;
        return domain.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
    }
}
