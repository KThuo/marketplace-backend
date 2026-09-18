package com.hodi.infra.coop;

import com.hodi.common.EncryptionUtil;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.payments.ChannelConfig;
import com.hodi.modules.payments.PaymentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talking to Co-operative Bank.
 *
 * <h2>Directly, and nothing in between</h2>
 *
 * <p>This marketplace is Co-op's end system. There is no gateway fronting the bank, so this is where the
 * bank's own protocol is spoken: OAuth2 client credentials for a bearer token, then the channel's own
 * endpoint with the bank's header envelope on the body.
 *
 * <h2>Where the calls go is configuration</h2>
 *
 * <p>No host and no path appears here. Each channel carries its own — sandbox host, production host, token
 * path, request path, status path — because a bank that moves a path, opens an environment or versions an
 * endpoint would otherwise need a release. The credentials and which environment is live are platform
 * settings; everything else belongs to the channel.
 *
 * <h2>Two timeouts</h2>
 *
 * <p>Asking about money is quick: nothing is waiting on a person. Moving it is slow, because a customer is
 * deciding on their handset. A read timeout short enough for the first would abandon the second on exactly
 * the payments that go on to succeed — money taken and no record of it — which is the worst outcome
 * available and the reason these are two clients rather than one.
 *
 * <h2>It never throws</h2>
 *
 * <p>A bank having a bad minute is an expected condition, not an exception: callers record an intent, get a
 * named failure and leave the payment pending for the status query to settle. An exception here would
 * escape mid-flow and roll back the row that remembers the attempt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoopClient {

    /** Co-op's own success marker, in the body. The HTTP status says only that it was processed. */
    private static final String OK = "0";

    private static final Duration QUICK = Duration.ofSeconds(30);
    private static final Duration PATIENT = Duration.ofMinutes(3);

    private final ConfigurationService configs;
    private final EncryptionUtil crypto;

    private final RestClient quick = client(QUICK);
    private final RestClient patient = client(PATIENT);

    /**
     * Explicit read timeouts on both.
     *
     * <p>{@code RestClient.builder()} leaves the JDK's defaults, where a read has none at all — a bank that
     * accepts the connection and then goes quiet would hold the request thread for ever.
     */
    private static RestClient client(Duration read) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Tokens, kept until they expire.
     *
     * <p>One token serves many calls. Fetching one per request is how an integration meets a rate limit and
     * starts failing under exactly the load it was built for. Keyed by channel, because two channels may
     * hold different credentials.
     */
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    private record Token(String value, long expiresAtEpochSecond) {
        boolean usable() {
            // Thirty seconds of headroom: a token that expires in flight fails a payment for no reason.
            return value != null && expiresAtEpochSecond - 30 > System.currentTimeMillis() / 1000;
        }
    }

    /**
     * The answer, or a named failure. Never null, never thrown.
     *
     * @param failure null when it worked; otherwise something a person could act on
     */
    public record Outcome<T>(T value, String failure) {

        public static <T> Outcome<T> ok(T value) {
            return new Outcome<>(value, null);
        }

        public static <T> Outcome<T> failed(String why) {
            return new Outcome<>(null, why);
        }

        public boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * Posts a body to one of a channel's configured paths.
     *
     * @param pathKey which path in the channel's own configuration — the request one, the status one
     * @param patientCall true when a person is waiting on the other end of it
     */
    public Outcome<Map<String, Object>> post(PaymentType channel, String pathKey,
                                             Map<String, Object> body, boolean patientCall) {
        String base = baseUrlOf(channel);
        String path = config(channel, pathKey);
        if (base == null || path == null) {
            return Outcome.failed("This channel has no " + pathKey + " configured yet.");
        }

        Outcome<String> token = token(channel);
        if (!token.succeeded()) return Outcome.failed(token.failure());

        String url = base.endsWith("/") && path.startsWith("/")
                ? base + path.substring(1)
                : base + path;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (patientCall ? patient : quick).post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return Outcome.failed("Co-op answered with nothing.");
            return Outcome.ok(response);
        } catch (Exception e) {
            // Logged with the channel, never the body: it carries a customer's phone number and an amount.
            log.error("Co-op call failed for {} ({}): {}", channel.getCode(), pathKey, e.getMessage());
            return Outcome.failed("Could not reach Co-op just now.");
        }
    }

    /** Whether the body says it worked. Co-op's status, not the HTTP code — they disagree routinely. */
    public static boolean succeeded(Map<String, Object> response) {
        Object status = response == null ? null : response.get("MessageCode");
        if (status == null && response != null) status = response.get("status");
        return status != null && OK.equals(String.valueOf(status).trim());
    }

    // ── the token ─────────────────────────────────────────────────────────────

    private Outcome<String> token(PaymentType channel) {
        Token held = tokens.get(channel.getCode());
        if (held != null && held.usable()) return Outcome.ok(held.value());

        String key = configs.getString(ConfigKey.COOP_CONSUMER_KEY);
        String secret = configs.getString(ConfigKey.COOP_CONSUMER_SECRET);
        if (key == null || key.isBlank() || secret == null || secret.isBlank()) {
            return Outcome.failed("Co-op credentials are not configured yet.");
        }
        String base = baseUrlOf(channel);
        String path = config(channel, "tokenPath");
        if (base == null || path == null) {
            return Outcome.failed("This channel has no token path configured yet.");
        }

        String basic = Base64.getEncoder().encodeToString(
                (key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) quick.post()
                    .uri(base + path)
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(Map.class);

            String token = body == null ? null : String.valueOf(body.get("access_token"));
            if (token == null || token.isBlank() || "null".equals(token)) {
                return Outcome.failed("Co-op did not return an access token.");
            }
            long ttl = 3000;
            Object expires = body.get("expires_in");
            if (expires != null) {
                try {
                    ttl = Long.parseLong(String.valueOf(expires).trim());
                } catch (NumberFormatException ignored) {
                    // Co-op's own default, kept rather than failing over a field we can live without.
                }
            }
            tokens.put(channel.getCode(),
                    new Token(token, System.currentTimeMillis() / 1000 + ttl));
            return Outcome.ok(token);
        } catch (Exception e) {
            log.error("Could not get a Co-op token for {}: {}", channel.getCode(), e.getMessage());
            return Outcome.failed("Could not authenticate with Co-op.");
        }
    }

    /**
     * Which host, decided by the platform's environment switch.
     *
     * <p>A setting rather than a build profile, so moving a deployment to production is an edit somebody
     * can make and can undo.
     */
    private String baseUrlOf(PaymentType channel) {
        boolean production = "PRODUCTION".equalsIgnoreCase(
                String.valueOf(configs.getString(ConfigKey.COOP_ENVIRONMENT)).trim());
        String url = config(channel, production ? "productionBaseUrl" : "sandboxBaseUrl");
        // A deployment switched to production before its production host is set falls back rather than
        // calling the sandbox by accident — there is nothing safe about guessing which bank to pay.
        return url == null || url.isBlank() ? null : url.trim();
    }

    private String config(PaymentType channel, String key) {
        String value = ChannelConfig.value(
                channel.getRequiredConfigFields(), channel.getConfig(), key, crypto);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
