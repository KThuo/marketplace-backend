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

    /**
     * Who we say we are.
     *
     * <p>Not decoration. Java's HTTP client identifies itself as {@code Java-http-client/<version>} by
     * default, and the firewall in front of Co-op's gateway answers that with an F5 "Request Rejected"
     * page — HTTP 200, HTML body, no mention of a payment. The same request from Postman is allowed,
     * which is what makes it look like the application is at fault when nothing about the request but
     * this header differs.
     */
    private static final String AGENT = "Hodi-Marketplace/1.0";

    private static final Duration QUICK = Duration.ofSeconds(30);
    private static final Duration PATIENT = Duration.ofMinutes(3);

    private final ConfigurationService configs;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
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
    public record Outcome<T>(T value, String failure, boolean sent) {

        public static <T> Outcome<T> ok(T value) {
            return new Outcome<>(value, null, true);
        }

        /**
         * We tried, and cannot say what happened.
         *
         * <p>The request may have reached the bank. A caller must treat this as unknown and let the status
         * query settle it — never as a failure.
         */
        public static <T> Outcome<T> failed(String why) {
            return new Outcome<>(null, why, true);
        }

        /**
         * Nothing left this process, so nothing happened at the bank.
         *
         * <p>A missing host, a missing endpoint, credentials nobody has filled in: all of them are reasons
         * the call was never attempted. Conflating this with a transport failure is what left a payment
         * "in flight" — and burning status queries against the bank — for a prompt no customer ever saw.
         * The difference is not cosmetic: one is settled by fixing a setting, the other by waiting.
         */
        public static <T> Outcome<T> unsent(String why) {
            return new Outcome<>(null, why, false);
        }

        public boolean succeeded() {
            return failure == null;
        }

        /** True when the request never left us, so the caller may conclude nothing happened. */
        public boolean neverSent() {
            return failure != null && !sent;
        }
    }

    /**
     * Posts a body to a channel's own endpoint.
     *
     * <p>One endpoint, not a choice of several: each payment type is one operation against the bank, and
     * where a second is needed — a status query against a prompt — it is a payment type of its own, which
     * keeps "which path" from being a parameter every caller can get wrong.
     *
     * @param patientCall true when a person is waiting on the other end of it
     */
    public Outcome<Map<String, Object>> post(PaymentType channel, Map<String, Object> body,
                                             boolean patientCall) {
        String base = baseUrl();
        String path = config(channel, "endpoint");
        if (base == null) {
            return Outcome.unsent("The Co-op host is not configured yet. Set it under Integration "
                    + "settings.");
        }
        if (path == null) {
            return Outcome.unsent(channel.getName() + " has no endpoint configured yet. Set it on the "
                    + "payment method.");
        }

        Outcome<String> token = token(channel);
        if (!token.succeeded()) {
            return token.neverSent() ? Outcome.unsent(token.failure()) : Outcome.failed(token.failure());
        }

        return send(join(base, path), token.value(), body, patientCall, channel.getCode());
    }

    /**
     * The call itself.
     *
     * @param called what to name in a log line — never the body, which carries a customer's phone
     *               number and an amount
     */
    private Outcome<Map<String, Object>> send(String url, String token, Map<String, Object> body,
                                              boolean patientCall, String called) {
        try {
            /*
             * Taken as text, then parsed.
             *
             * <p>Asking for a Map directly means a gateway answering with an error page raises "no
             * suitable HttpMessageConverter found ... content type [text/html]" — a sentence about Java
             * that says nothing about what went wrong. The bank's proxies answer with HTML routinely:
             * a wrong path, an expired route, a gateway between us and them having a bad minute. What
             * somebody needs to be told is that an HTML page came back from this URL, and its status.
             */
            org.springframework.http.ResponseEntity<String> answer =
                    (patientCall ? patient : quick).post()
                            .uri(url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.USER_AGENT, AGENT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .toEntity(String.class);

            String text = answer.getBody();
            if (text == null || text.isBlank()) return Outcome.failed("Co-op answered with nothing.");

            String trimmed = text.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                log.error("Co-op answered {} with {} for {}: {}", url, answer.getStatusCode(), called,
                        firstLineOf(trimmed));
                /*
                 * A firewall, not the bank. F5 answers a blocked request with exactly this: HTTP 200,
                 * an HTML page titled "Request Rejected", and a support ID. Telling somebody the
                 * endpoint is wrong would send them to change a setting that is already correct.
                 */
                boolean blocked = trimmed.toLowerCase(java.util.Locale.ROOT).contains("request rejected");

                /*
                 * unsent, not failed — and the difference is two and a half minutes of somebody's time.
                 *
                 * <p>A web page is never a payment response. Whatever answered — a firewall, a proxy's
                 * 404 — the bank's own application did not process this request, so no prompt exists and
                 * there is nothing for a status query to find. Reporting it as "we tried and cannot say"
                 * left the payment in flight, and the screen then waited the full wait for a customer who
                 * was never asked anything.
                 */
                return Outcome.unsent(blocked
                        ? "The firewall in front of Co-op rejected the request before it reached them. "
                                + "Nothing was prompted. The support ID is in the server log — Co-op's "
                                + "team need it to say why."
                        : "Co-op answered with a web page rather than a payment response ("
                                + answer.getStatusCode() + "). The endpoint on this method may be wrong "
                                + "for this host.");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(trimmed, Map.class);
            return Outcome.ok(parsed);
        } catch (Exception e) {
            log.error("Co-op call failed for {} at {}: {}", called, url, e.getMessage());
            return Outcome.failed("Could not reach Co-op just now.");
        }
    }

    /**
     * The readable part of an error page.
     *
     * <p>Tags stripped and whitespace collapsed, because the one thing worth having off a firewall's
     * rejection page is its support ID — the reference Co-op's own people need to say why the request
     * was blocked — and it is never on the first line.
     */
    private static String firstLineOf(String text) {
        String stripped = text.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return stripped.length() > 400 ? stripped.substring(0, 400) + "…" : stripped;
    }

    /** Whether the body says it worked. Co-op's status, not the HTTP code — they disagree routinely. */
    public static boolean succeeded(Map<String, Object> response) {
        Object status = response == null ? null : response.get("MessageCode");
        if (status == null && response != null) status = response.get("status");
        return status != null && OK.equals(String.valueOf(status).trim());
    }

    // ── the token ─────────────────────────────────────────────────────────────

    private Outcome<String> token(PaymentType channel) {
        return token(channel.getCode());
    }

    private Outcome<String> token(String cacheKey) {
        Token held = tokens.get(cacheKey);
        if (held != null && held.usable()) return Outcome.ok(held.value());

        // Trimmed, because these are pasted from a bank's onboarding e-mail and a trailing space in a
        // credential fails authentication with a message that blames the credential rather than the space.
        String key = trimmed(configs.getString(ConfigKey.COOP_CONSUMER_KEY));
        String secret = trimmed(configs.getString(ConfigKey.COOP_CONSUMER_SECRET));
        if (key == null || secret == null) {
            return Outcome.unsent("Co-op credentials are not configured yet. Set the consumer key and "
                    + "secret under Integration settings.");
        }
        String base = baseUrl();
        String path = trimmed(configs.getString(ConfigKey.COOP_TOKEN_PATH));
        if (base == null || path == null) {
            return Outcome.unsent("The Co-op host or token path is not configured yet.");
        }

        String basic = Base64.getEncoder().encodeToString(
                (key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) quick.post()
                    .uri(join(base, path))
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .header(HttpHeaders.USER_AGENT, AGENT)
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
            tokens.put(cacheKey, new Token(token, System.currentTimeMillis() / 1000 + ttl));
            return Outcome.ok(token);
        } catch (Exception e) {
            log.error("Could not get a Co-op token for {}: {}", cacheKey, e.getMessage());
            // Tried and could not: a token call that failed means nothing was posted afterwards either,
            // but we cannot prove the bank was never reached, so the caller keeps its own judgement.
            return Outcome.failed("Could not authenticate with Co-op.");
        }
    }

    /**
     * A base and a configured path, joined — or the path alone when it is already a whole address.
     *
     * <p>People configure both. A bank's onboarding sheet lists the token endpoint as a full URL, so that
     * is what gets pasted into a field labelled "path", and concatenating it onto the host produces
     * {@code https://bank.example/https://bank.example/token} — a call that fails with a message about the
     * host rather than about the mistake. Accepting either spelling is cheaper than a support call.
     */
    private static String join(String base, String path) {
        if (path.regionMatches(true, 0, "http://", 0, 7)
                || path.regionMatches(true, 0, "https://", 0, 8)) {
            return path;
        }
        boolean baseEnds = base.endsWith("/");
        boolean pathStarts = path.startsWith("/");
        if (baseEnds && pathStarts) return base + path.substring(1);
        if (!baseEnds && !pathStarts) return base + "/" + path;
        return base + path;
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }

    /**
     * Which host — the platform's, shared by every channel.
     *
     * <p>There is no environment switch beside it, and that is the point: an address that reads sandbox
     * <em>is</em> the sandbox. A separate setting saying which environment is live is a second source of
     * truth for the same fact, and the way it fails is paying the wrong bank.
     *
     * <p>Null while it is blank, so an unconfigured deployment makes no call at all rather than guessing.
     */
    private String baseUrl() {
        String url = configs.getString(ConfigKey.COOP_BASE_URL);
        return url == null || url.isBlank() ? null : url.trim();
    }

    private String config(PaymentType channel, String key) {
        String value = ChannelConfig.value(
                channel.getRequiredConfigFields(), channel.getConfig(), key, crypto);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
