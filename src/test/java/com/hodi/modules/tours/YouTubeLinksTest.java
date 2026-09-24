package com.hodi.modules.tours;

import com.hodi.common.exception.HodiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shape a seller is likely to paste, reduced to the same id.
 *
 * <p>The list is what share sheets, address bars and embed codes actually produce, not a list of what the
 * parser happens to support. A shape missing here is a correct link refused for a reason nobody could guess.
 */
class YouTubeLinksTest {

    private static final String ID = "dQw4w9WgXcQ";

    @ParameterizedTest
    @ValueSource(strings = {
            "dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&feature=shared",
            "http://m.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "www.youtube.com/watch?v=dQw4w9WgXcQ",
            "youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=Ab12Cd34Ef56Gh78",
            "youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share",
            "https://www.youtube.com/v/dQw4w9WgXcQ",
            "  https://youtu.be/dQw4w9WgXcQ  ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2",
    })
    @DisplayName("every link shape comes down to the one id")
    void everyShapeIsTheSameVideo(String pasted) {
        assertEquals(ID, YouTubeLinks.parse(pasted).videoId());
    }

    @Test
    @DisplayName("a timestamp on the link becomes where the tour starts, in any of YouTube's spellings")
    void timestampsAreKept() {
        assertEquals(42, YouTubeLinks.parse("https://youtu.be/dQw4w9WgXcQ?t=42").startSeconds());
        assertEquals(42, YouTubeLinks.parse("https://youtu.be/dQw4w9WgXcQ?t=42s").startSeconds());
        assertEquals(90, YouTubeLinks.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s").startSeconds());
        assertEquals(3723, YouTubeLinks.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s").startSeconds());
        assertEquals(15, YouTubeLinks.parse("https://www.youtube.com/embed/dQw4w9WgXcQ?start=15").startSeconds());
        assertEquals(90, YouTubeLinks.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=90").startSeconds());
        assertEquals(0, YouTubeLinks.parse("https://youtu.be/dQw4w9WgXcQ").startSeconds());
    }

    @Test
    @DisplayName("an unreadable timestamp starts at the beginning rather than refusing a good link")
    void badTimestampsAreForgiven() {
        assertEquals(0, YouTubeLinks.parse("https://youtu.be/dQw4w9WgXcQ?t=soon").startSeconds());
        assertEquals(0, YouTubeLinks.parse("https://youtu.be/dQw4w9WgXcQ?t=99999999999999999999").startSeconds());
        assertEquals(0, YouTubeLinks.parse("https://youtu.be/dQw4w9WgXcQ?t=999999").startSeconds(),
                "longer than a day is a typo, not a walkthrough");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://vimeo.com/123456789",
            "https://www.tiktok.com/@agent/video/7300000000000000000",
            "https://evil.example/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "javascript:alert(1)",
    })
    @DisplayName("another site is refused by name, including one that only looks like YouTube")
    void otherHostsAreRefused(String pasted) {
        HodiException e = assertThrows(HodiException.class, () -> YouTubeLinks.parse(pasted));
        assertTrue(e.getMessage().startsWith("That is not a YouTube link")
                        || e.getMessage().startsWith("That does not look like a link"), e.getMessage());
    }

    @Test
    @DisplayName("a playlist, a channel and a truncated id each get their own explanation")
    void wrongKindsOfYouTubeLinkAreNamed() {
        assertTrue(assertThrows(HodiException.class,
                () -> YouTubeLinks.parse("https://www.youtube.com/playlist?list=PL0123456789"))
                .getMessage().contains("playlist"));
        assertTrue(assertThrows(HodiException.class,
                () -> YouTubeLinks.parse("https://www.youtube.com/@SomeAgency"))
                .getMessage().contains("channel"));
        assertTrue(assertThrows(HodiException.class,
                () -> YouTubeLinks.parse("https://youtu.be/dQw4w9W"))
                .getMessage().contains("does not name a video"));
        assertTrue(assertThrows(HodiException.class,
                () -> YouTubeLinks.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ\"><script>"))
                .getMessage().contains("does not"));
        assertThrows(HodiException.class, () -> YouTubeLinks.parse("   "));
        assertThrows(HodiException.class, () -> YouTubeLinks.parse(null));
    }
}
