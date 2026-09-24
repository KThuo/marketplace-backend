package com.hodi.modules.tours;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks YouTube's oEmbed endpoint about one video: does it exist, may it be embedded, what is it called.
 *
 * <p>oEmbed rather than the Data API, because it needs no key and answers exactly the question here. A 404
 * is a video that does not exist; a 401 or 403 is one that is private or has embedding switched off —
 * either would be a black box with an error in it on the listing page, found by a buyer rather than by the
 * seller who could fix it.
 *
 * <h2>Not a way to make the server fetch things</h2>
 *
 * <p>The request is built here from a fixed host and an id that {@link YouTubeLinks} has already confined to
 * eleven characters of {@code [A-Za-z0-9_-]}. Nothing the caller typed reaches the URL except that id, so
 * this cannot be pointed at an internal address.
 *
 * <h2>Failing open</h2>
 *
 * <p>If YouTube cannot be reached the answer is {@link VideoCheck.Verdict#UNKNOWN}, and the tour is saved
 * without a title. A seller should not be told their link is wrong because this server's outbound network
 * had a bad minute; the buyer's browser talks to YouTube directly and will very likely play it.
 */
@Slf4j
@Component
public class YouTubeOEmbed implements VideoCheck {

    /** Short. A seller is waiting on this with a spinner, and the fallback is harmless. */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;

    public YouTubeOEmbed() {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "Hodi-Marketplace/1.0").build();
    }

    @Override
    public Result check(String videoId) {
        String watch = "https://www.youtube.com/watch?v=" + videoId;
        String url = "https://www.youtube.com/oembed?format=json&url="
                + URLEncoder.encode(watch, StandardCharsets.UTF_8);
        try {
            String body = client.get().uri(java.net.URI.create(url)).retrieve().body(String.class);
            JsonNode json = body == null ? null : mapper.readTree(body);
            String title = json == null ? null : json.path("title").asText(null);
            return new Result(Verdict.PLAYABLE, title == null || title.isBlank() ? null : title.trim());
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 404 || code == 400) return new Result(Verdict.MISSING, null);
            if (code == 401 || code == 403) return new Result(Verdict.NOT_EMBEDDABLE, null);
            log.warn("YouTube oEmbed answered {} for {}; saving the tour unverified", code, videoId);
            return Result.unknown();
        } catch (Exception e) {
            log.warn("YouTube oEmbed unreachable for {} ({}); saving the tour unverified",
                    videoId, e.getClass().getSimpleName());
            return Result.unknown();
        }
    }
}
