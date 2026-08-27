package com.hodi.modules.developments;

import com.hodi.modules.developments.UnitLabels.Plan;
import com.hodi.modules.developments.UnitLabels.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door numbers, before two hundred of them are written down.
 *
 * <p>A generator that puts a unit on the wrong floor or repeats a label is a mistake somebody undoes by hand,
 * row by row. So the arithmetic is pure and pinned here, and the screen shows the result before anything is
 * saved.
 */
class UnitLabelsTest {

    private static List<String> labels(Plan plan) {
        return UnitLabels.expand(plan).stream().map(Slot::label).toList();
    }

    @Test
    @DisplayName("the case from the brief: seventy two-beds, block B, seven a floor from floor one")
    void theBriefsExample() {
        List<Slot> slots = UnitLabels.expand(
                new Plan(70, "B", (short) 1, (short) 7, "{block}-{floor}{nn}"));

        assertEquals(70, slots.size());
        assertEquals("B-101", slots.getFirst().label());
        assertEquals("B-107", slots.get(6).label(), "the seventh is still on floor one");
        assertEquals("B-201", slots.get(7).label(), "the eighth starts floor two");
        assertEquals("B-1007", slots.getLast().label(), "and the last is the seventh on floor ten");
        assertEquals((short) 10, slots.getLast().floorNo());
        assertFalse(UnitLabels.hasDuplicates(slots));
    }

    @Test
    @DisplayName("a count that does not divide evenly leaves the last floor short, as a building does")
    void unevenLastFloor() {
        List<Slot> slots = UnitLabels.expand(new Plan(10, "A", (short) 1, (short) 4, null));
        assertEquals("A-101", slots.getFirst().label());
        assertEquals((short) 3, slots.getLast().floorNo(), "ten over four is three floors");
        assertEquals("A-302", slots.getLast().label(), "the third floor has two of them");
    }

    @Test
    @DisplayName("no floors given: every unit numbered straight through")
    void noFloors() {
        assertEquals(List.of("1", "2", "3"),
                labels(new Plan(3, null, null, null, "{i}")));
    }

    @Test
    @DisplayName("padding exists so a list sorts the way somebody reads it")
    void padding() {
        List<String> padded = labels(new Plan(11, null, null, null, "U{iii}"));
        assertEquals("U001", padded.getFirst());
        assertEquals("U011", padded.getLast());

        // Unpadded, "U10" sorts before "U2" in every table in the product.
        List<String> plain = labels(new Plan(11, null, null, null, "U{i}"));
        assertEquals("U11", plain.getLast());
    }

    @Test
    @DisplayName("the default pattern adapts to whether there is a block, rather than leaving a stray hyphen")
    void defaults() {
        assertEquals("B-101", labels(new Plan(1, "B", (short) 1, (short) 7, null)).getFirst());
        assertEquals("101", labels(new Plan(1, null, (short) 1, (short) 7, null)).getFirst(),
                "no block, no leading hyphen");
    }

    @Test
    @DisplayName("a pattern with no varying token is caught before it is written, not after the first row")
    void duplicatesDetected() {
        List<Slot> slots = UnitLabels.expand(new Plan(5, "B", null, null, "{block}"));
        assertTrue(UnitLabels.hasDuplicates(slots),
                "five units all called B is a plan the preview must refuse");
    }

    @Test
    @DisplayName("{nn} and {n} do not collide when both appear")
    void tokenOrdering() {
        // {nn} is substituted first on purpose: replacing {n} first would turn "{nn}" into "11".
        assertEquals("1-01", labels(new Plan(1, null, null, null, "{n}-{nn}")).getFirst());
    }
}
