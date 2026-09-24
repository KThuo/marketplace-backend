package com.hodi.modules.tours;

import com.hodi.common.exception.HodiException;
import com.hodi.modules.tours.TourChapters.Chapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rooms of a walkthrough, as a seller types them and as a YouTube description already has them. */
class TourChaptersTest {

    @Test
    @DisplayName("a YouTube description's chapters paste straight in")
    void youTubeDescriptionFormat() {
        List<Chapter> rooms = TourChapters.parse("""
                0:00 The gate
                0:24 Living room
                1:12 - Kitchen
                2:05 – Master bedroom
                1:02:05 | Rooftop
                """);
        assertEquals(List.of(
                new Chapter(0, "The gate"),
                new Chapter(24, "Living room"),
                new Chapter(72, "Kitchen"),
                new Chapter(125, "Master bedroom"),
                new Chapter(3725, "Rooftop")), rooms);
    }

    @Test
    @DisplayName("the name may come first, bullets and brackets are ignored, blank lines skipped")
    void theOtherWayRound() {
        List<Chapter> rooms = TourChapters.parse("""
                • Gate 0:00

                Two bedroom wing - 1:30
                - (2:10) Garden
                """);
        assertEquals(List.of(
                new Chapter(0, "Gate"),
                new Chapter(90, "Two bedroom wing"),
                new Chapter(130, "Garden")), rooms);
    }

    @Test
    @DisplayName("nothing typed is no rooms, not an error")
    void emptyIsFine() {
        assertTrue(TourChapters.parse(null).isEmpty());
        assertTrue(TourChapters.parse("  \n \n").isEmpty());
    }

    @Test
    @DisplayName("each refusal names the line it is about")
    void refusalsNameTheLine() {
        assertTrue(assertThrows(HodiException.class, () -> TourChapters.parse("0:00 Gate\nKitchen"))
                .getMessage().startsWith("Line 2 has no time"));
        assertTrue(assertThrows(HodiException.class, () -> TourChapters.parse("0:00 Gate\n0:40 Hall\n0:30 Kitchen"))
                .getMessage().startsWith("Line 3 is at 0:30"));
        assertTrue(assertThrows(HodiException.class, () -> TourChapters.parse("0:00 Gate\n0:00 Hall"))
                .getMessage().contains("not after"), "two rooms at one second is a typo");
        assertTrue(assertThrows(HodiException.class, () -> TourChapters.parse("1:75 Kitchen"))
                .getMessage().contains("cannot be right"), "seventy-five seconds is not wrapped into a minute");
        assertTrue(assertThrows(HodiException.class, () -> TourChapters.parse("0:10 " + "x".repeat(61)))
                .getMessage().contains("longer than 60"));
    }

    @Test
    @DisplayName("more than thirty rooms is a shot list")
    void aCap() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 31; i++) many.append("0:").append(String.format("%02d", i)).append(" Room ").append(i).append('\n');
        assertTrue(assertThrows(HodiException.class, () -> TourChapters.parse(many.toString()))
                .getMessage().contains("31 rooms"));
    }

    @Test
    @DisplayName("what is saved goes back into the box exactly as it would be typed")
    void roundTrips() {
        String typed = "0:00 Gate\n1:12 Kitchen\n1:02:05 Rooftop";
        assertEquals(typed, TourChapters.format(TourChapters.parse(typed)));
    }
}
