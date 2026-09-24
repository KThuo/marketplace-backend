package com.hodi.modules.tours;

import com.hodi.common.exception.HodiException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whatever somebody pasted, reduced to the one thing that identifies a YouTube video.
 *
 * <p>A seller copies from wherever they happen to be: the share sheet on a phone gives {@code youtu.be/…?si=…},
 * the address bar gives {@code watch?v=…&t=42s}, a vertical walkthrough is a {@code /shorts/} link, and the
 * embed code somebody's videographer sent is {@code /embed/…}. They are one video, and a form that accepted two
 * of the shapes would be refusing a correct link for a reason nobody could guess.
 *
 * <h2>Why only the id is kept</h2>
 *
 * <p>The id is eleven characters from a fixed alphabet. Everything a buyer's browser is later pointed at —
 * the iframe, the thumbnail — is built from it on a host this code chooses. Keeping the pasted URL instead
 * would put an arbitrary address into a column that ends up inside an {@code <iframe src>}.
 *
 * <p>The same rules exist in {@code hodimp-f/src/utils/youtube.ts} so the form can answer while somebody is
 * still typing. This class is the one that decides.
 */
public final class YouTubeLinks {

    private YouTubeLinks() {}

    /** What a link comes down to. {@code startSeconds} is zero when the link carried no timestamp. */
    public record Parsed(String videoId, int startSeconds) {}

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** Every host YouTube serves a watch page or a player from. Subdomains are reduced to these first. */
    private static final Set<String> HOSTS = Set.of("youtube.com", "youtube-nocookie.com", "youtu.be");

    /** The path shapes that carry the id as their second segment. */
    private static final Set<String> ID_PATHS = Set.of("embed", "shorts", "live", "v", "e");

    /** {@code 1h2m3s}, {@code 2m}, {@code 45s} — any subset, in that order. */
    private static final Pattern UNITS = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    /** A day. Nothing longer is a walkthrough, and an absurd number here is somebody's typo. */
    private static final int MAX_START = 24 * 60 * 60;

    public static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) throw refuse("Paste the link to the video.");

        // A bare id is what somebody copies out of YouTube Studio, and it is unambiguous.
        if (ID.matcher(text).matches()) return new Parsed(text, 0);

        URI uri = toUri(text);
        String host = hostOf(uri);
        if (!HOSTS.contains(host)) {
            throw refuse("That is not a YouTube link. Tours play from YouTube — upload the video there and paste "
                    + "its link here.");
        }

        String[] segments = segments(uri.getRawPath());
        String query = uri.getRawQuery();
        String id;
        if ("youtu.be".equals(host)) {
            id = segments.length > 0 ? segments[0] : null;
        } else if (segments.length >= 2 && ID_PATHS.contains(segments[0].toLowerCase(Locale.ROOT))) {
            id = segments[1];
        } else if (segments.length >= 1 && "watch".equalsIgnoreCase(segments[0])) {
            id = param(query, "v");
        } else {
            id = param(query, "v");
            if (id == null && param(query, "list") != null) {
                throw refuse("That is a playlist. Open the one video that is the tour and paste its link.");
            }
            if (id == null) {
                throw refuse("That YouTube link is to a channel or a page, not to one video. Open the video "
                        + "and copy its link.");
            }
        }

        if (id == null || !ID.matcher(id).matches()) {
            throw refuse("That YouTube link does not name a video. Copy it again from the video's Share button.");
        }

        String start = param(query, "t");
        if (start == null) start = param(query, "start");
        // The embed code's own fragment form, #t=90, which some players still produce.
        if (start == null && uri.getRawFragment() != null && uri.getRawFragment().startsWith("t=")) {
            start = uri.getRawFragment().substring(2);
        }
        return new Parsed(id, startOf(start));
    }

    /**
     * A timestamp in any of the forms YouTube writes one.
     *
     * <p>Unreadable is zero rather than a refusal: the timestamp is a convenience on a link that is otherwise
     * correct, and refusing the whole tour because somebody's share sheet appended something odd would be the
     * wrong trade.
     */
    static int startOf(String value) {
        if (value == null || value.isBlank()) return 0;
        String v = value.trim().toLowerCase(Locale.ROOT);
        // Digits only, and few enough of them that nothing below can overflow.
        if (v.length() > 12) return 0;
        long seconds;
        if (v.matches("\\d+")) {
            seconds = Long.parseLong(v);
        } else if (v.matches("\\d{1,2}(:\\d{1,2}){1,2}")) {
            seconds = TourChapters.clockToSeconds(v);
        } else {
            Matcher m = UNITS.matcher(v);
            if (!m.matches()) return 0;
            seconds = num(m.group(1)) * 3600 + num(m.group(2)) * 60 + num(m.group(3));
        }
        return seconds < 0 || seconds > MAX_START ? 0 : (int) seconds;
    }

    private static long num(String group) {
        return group == null ? 0 : Long.parseLong(group);
    }

    private static URI toUri(String text) {
        // People paste "youtu.be/abc" without a scheme. A URI with no scheme has no host, so one is supplied.
        String withScheme = text.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? text : "https://" + text;
        try {
            return new URI(withScheme);
        } catch (URISyntaxException e) {
            throw refuse("That does not look like a link. Copy it again from the video's Share button.");
        }
    }

    /** Lower-cased, with the subdomains YouTube uses stripped: www, m, music. */
    private static String hostOf(URI uri) {
        String host = uri.getHost();
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"www.", "m.", "music."}) {
            if (host.startsWith(prefix)) return host.substring(prefix.length());
        }
        return host;
    }

    private static String[] segments(String path) {
        if (path == null) return new String[0];
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static HodiException refuse(String message) {
        return new HodiException(message, HttpStatus.BAD_REQUEST);
    }
}
