package com.hodi.modules.payments;

import com.hodi.common.EncryptionUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A channel's configuration: what it needs, what is set, and which of it nobody may read back.
 *
 * <h2>The descriptor is the design</h2>
 *
 * <p>A channel says which fields it needs — a host, a token path, a callback URL, a password — and this
 * reads and writes them by key. Nothing in the application knows what any particular field <em>means</em>,
 * which is the property worth having: a bank that moves a path, opens a second environment or versions an
 * endpoint is an edit to a row, not a release. The alternative, a column per field, needs a migration and a
 * deploy for every one of those, and banks do all three routinely.
 *
 * <h2>Secrets go in and do not come back</h2>
 *
 * <p>A field whose descriptor says {@code "type":"password"} is encrypted with the platform's own
 * AES-256-GCM before it is stored, and a read answers {@link #MASK} rather than the value. A save that
 * sends the mask back leaves the stored value alone — which is what makes it possible to edit the host
 * without retyping the secret, and what stops a form round-trip from overwriting a real password with four
 * bullet characters.
 *
 * <p>The same rule {@code ConfigurationService} already applies to secret settings, stated again here
 * because the failure mode is silent: nothing would break at save time, and the next outbound call would
 * fail authentication for a reason nobody could see on the screen.
 *
 * <h2>One descriptor grammar, two columns</h2>
 *
 * <p>A channel's own configuration and one of its accounts are described the same way — a {@code fields}
 * list of {@code {key, label, type, required}} — so everything below reads both without knowing which it
 * was given. What differs is who fills the form in: the platform sets a channel's hosts and paths once,
 * an organisation sets an account's codes and credentials per account.
 */
public final class ChannelConfig {

    private ChannelConfig() {}

    /** What a secret reads back as. Sent back unchanged on a save, it means "leave it alone". */
    public static final String MASK = "••••••••";

    private static final String FIELDS = "fields";
    /**
     * A field the form shows but nobody fills in.
     *
     * <p>The URL Co-op should call is <em>ours</em>. It came in as an input because the gateway this was
     * modelled on asked each business for its own address — that gateway called businesses. Nothing calls
     * out to anybody here: Co-op calls this platform, at a route this application defines. Asking an
     * operator to type it invites a typo into the one address that cannot be wrong, and a value typed into
     * a box is one nobody updates when the route changes.
     *
     * <p>So it is declared, labelled, and filled in by the server from {@code platform.public.url} plus the
     * route. It is never stored and never required, because there is nothing to store or to require.
     */
    public static final String DISPLAY = "display";

    private static final String KEY = "key";
    private static final String TYPE = "type";
    private static final String PASSWORD = "password";

    /** One field as the screen needs it: what it is called, whether it is secret, whether it is set. */
    public record Field(String key, String label, String type, boolean required, boolean fullWidth,
                        String value, boolean set) {}

    /**
     * The channel's fields, with values — secrets masked.
     *
     * <p>Driven by the descriptor rather than by what happens to be stored, so a field the bank has added
     * appears empty and asking to be filled rather than not appearing at all.
     */
    public static List<Field> describe(Map<String, Object> descriptor, Map<String, Object> stored,
                                       EncryptionUtil crypto) {
        return describe(descriptor, stored, crypto, null);
    }

    /** The same, with the values for any {@link #DISPLAY} fields the descriptor declares. */
    public static List<Field> describe(Map<String, Object> descriptor, Map<String, Object> stored,
                                       EncryptionUtil crypto, Map<String, String> computed) {
        List<Field> out = new ArrayList<>();
        for (Map<String, Object> field : fieldsOf(descriptor)) {
            String key = text(field.get(KEY));
            if (key == null) continue;
            if (DISPLAY.equalsIgnoreCase(text(field.get(TYPE)))) {
                // Filled from what the caller computed, never from what is stored — there is nothing stored.
                String shown = computed == null ? null : computed.get(key);
                out.add(new Field(key, text(field.get("label")), DISPLAY, false,
                        Boolean.TRUE.equals(field.get("fullWidth")), shown, shown != null));
                continue;
            }
            boolean secret = PASSWORD.equalsIgnoreCase(text(field.get(TYPE)));
            String raw = stored == null ? null : text(stored.get(key));
            boolean set = raw != null && !raw.isBlank();
            out.add(new Field(
                    key,
                    text(field.getOrDefault("label", key)),
                    secret ? PASSWORD : "text",
                    Boolean.TRUE.equals(field.get("required")),
                    Boolean.TRUE.equals(field.get("fullWidth")),
                    secret ? (set ? MASK : null) : raw,
                    set));
        }
        return List.copyOf(out);
    }

    /**
     * The values to store, given what was submitted and what is already there.
     *
     * <p>Three rules, each of which exists because the obvious alternative loses something:
     *
     * <ul>
     *   <li>A key the descriptor does not name is dropped, so a form cannot smuggle a field the channel
     *       has no use for into the row.</li>
     *   <li>A secret sent back as the mask keeps its stored value — the edit was about some other field.</li>
     *   <li>A secret sent blank is <em>cleared</em>, not kept: emptying a credential has to be possible,
     *       and treating blank as "no change" would make a revoked key unremovable.</li>
     * </ul>
     */
    public static Map<String, Object> merge(Map<String, Object> descriptor,
                                            Map<String, Object> stored,
                                            Map<String, Object> submitted,
                                            EncryptionUtil crypto) {
        Map<String, Object> next = new LinkedHashMap<>();
        if (submitted == null) return stored == null ? next : new LinkedHashMap<>(stored);

        for (Map<String, Object> field : fieldsOf(descriptor)) {
            String key = text(field.get(KEY));
            // A display field is the platform's own answer. Storing whatever a form posted back for it
            // would let a client overwrite the address Co-op is told to call.
            if (DISPLAY.equalsIgnoreCase(text(field.get(TYPE)))) continue;
            if (key == null || !submitted.containsKey(key)) {
                // Not submitted at all: keep whatever is there. A partial form is an edit, not a wipe.
                if (key != null && stored != null && stored.containsKey(key)) {
                    next.put(key, stored.get(key));
                }
                continue;
            }
            String value = text(submitted.get(key));
            boolean secret = PASSWORD.equalsIgnoreCase(text(field.get(TYPE)));

            if (secret && MASK.equals(value)) {
                if (stored != null && stored.get(key) != null) next.put(key, stored.get(key));
                continue;
            }
            if (value == null || value.isBlank()) continue;
            next.put(key, secret ? crypto.encryptString(value) : value);
        }
        return next;
    }

    /**
     * One value, ready to use — decrypted if it was a secret.
     *
     * <p>The only door to a stored secret, so there is one place to look when asking who can read them.
     */
    public static String value(Map<String, Object> descriptor, Map<String, Object> stored, String key,
                               EncryptionUtil crypto) {
        if (stored == null) return null;
        String raw = text(stored.get(key));
        if (raw == null) return null;
        boolean secret = fieldsOf(descriptor).stream()
                .anyMatch(f -> key.equals(text(f.get(KEY))) && PASSWORD.equalsIgnoreCase(text(f.get(TYPE))));
        return secret ? crypto.decryptString(raw) : raw;
    }

    /** Which required fields are still empty — what "not configured yet" means, in the channel's words. */
    public static List<String> missing(Map<String, Object> descriptor, Map<String, Object> stored) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> field : fieldsOf(descriptor)) {
            if (DISPLAY.equalsIgnoreCase(text(field.get(TYPE)))) continue;
            if (!Boolean.TRUE.equals(field.get("required"))) continue;
            String key = text(field.get(KEY));
            String raw = stored == null || key == null ? null : text(stored.get(key));
            if (raw == null || raw.isBlank()) {
                missing.add(text(field.getOrDefault("label", key)));
            }
        }
        return List.copyOf(missing);
    }

    /**
     * The value an inbound notification will be matched on, composed from the fields the descriptor
     * names in {@code accountKey}.
     *
     * <h2>Why this is not simply "the account number"</h2>
     *
     * <p>Co-op does not call back with an id. A biller advice carries an <b>institution code</b> and a
     * <b>service name</b> in its header, and the only way to know which biller it is for is to compose the
     * two and look that up. Whitespace stripped and lowercased, because the two halves are typed by
     * different people at different times and a space would make the advice unmatchable.
     *
     * <p>Named by the descriptor rather than written as a Co-op method, so a channel keyed on one field —
     * an operator code, a till number — needs no code at all, and a third way of keying needs a row.
     *
     * <p>Falls back to {@code accountNumber}: a descriptor that does not say how it is keyed is keyed on
     * the one field every channel has.
     */
    public static String accountKey(Map<String, Object> descriptor, Map<String, Object> values) {
        if (values == null) return null;
        StringBuilder composed = new StringBuilder();
        for (String key : accountKeyFields(descriptor)) {
            String part = text(values.get(key));
            if (part != null) composed.append(part);
        }
        String key = composed.toString().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        return key.isBlank() ? null : key;
    }

    /** Which fields compose it, so a caller can say what is missing by name. */
    @SuppressWarnings("unchecked")
    public static List<String> accountKeyFields(Map<String, Object> descriptor) {
        if (descriptor == null) return List.of("accountNumber");
        Object declared = descriptor.get("accountKey");
        if (!(declared instanceof List<?> list) || list.isEmpty()) return List.of("accountNumber");
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String name = text(item);
            if (name != null && !name.isBlank()) out.add(name);
        }
        return out.isEmpty() ? List.of("accountNumber") : List.copyOf(out);
    }

    /**
     * What one account of this channel is called — "Biller" on the Co-op biller, "Account" elsewhere.
     *
     * <p>A screen that says "Add a biller" where the bank's onboarding sheet says biller is one somebody
     * can follow that sheet against.
     */
    public static String accountsLabel(Map<String, Object> descriptor) {
        String label = descriptor == null ? null : text(descriptor.get("accountsLabel"));
        return label == null || label.isBlank() ? "Account" : label;
    }

    /**
     * The fields a descriptor declares.
     *
     * <p>A descriptor that is absent, or declares none, is a form with no fields rather than an error —
     * cash and cheque legitimately ask for nothing, and a channel whose descriptor has not been written
     * yet should render empty rather than break the screen that would fix it.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldsOf(Map<String, Object> descriptor) {
        if (descriptor == null) return List.of();
        Object fields = descriptor.get(FIELDS);
        if (!(fields instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
