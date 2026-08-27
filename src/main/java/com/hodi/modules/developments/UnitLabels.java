package com.hodi.modules.developments;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "seventy two-beds, block B, ten floors, seven a floor" into seventy door numbers.
 *
 * <p>Pure, and separated from the service for the same reason {@link InventoryMaths} is: the part that can be
 * wrong is the arithmetic, and a generator that produces "B-1013" where somebody expected "B-103" is a
 * two-hundred-row mistake to undo by hand. So the labels are computed here, tested here, and shown to the
 * person before anything is written.
 *
 * <h2>The pattern</h2>
 *
 * <p>Five tokens, and nothing else is substituted:
 *
 * <ul>
 *   <li>{@code {block}} — the block, as given</li>
 *   <li>{@code {floor}} — the floor this unit is on</li>
 *   <li>{@code {n}} — its position on that floor, from 1</li>
 *   <li>{@code {nn}} — the same, padded to two digits, so 1 sorts beside 10 in a list</li>
 *   <li>{@code {i}} / {@code {iii}} — its position in the whole batch, plain and padded to three</li>
 * </ul>
 *
 * <p>No expression language and no arithmetic in the pattern. Somebody's label template is a string a sales
 * office types, and the moment it can compute it is a string that can fail.
 */
final class UnitLabels {

    private UnitLabels() {}

    /** What to generate. Floors are optional: without them every unit is on the same notional floor. */
    record Plan(int count, String block, Short firstFloor, Short unitsPerFloor, String pattern) {}

    /** One generated unit's identity, before it becomes a row. */
    record Slot(String label, String block, Short floorNo, int index) {}

    static final String DEFAULT_PATTERN = "{block}-{floor}{nn}";
    static final String NO_BLOCK_PATTERN = "{floor}{nn}";

    /**
     * The slots a plan describes, in order.
     *
     * <p>Floors advance every {@code unitsPerFloor}, so a plan of seventy with seven a floor from floor one
     * produces floors one to ten. A count that does not divide evenly leaves the last floor short, which is
     * what a real building does.
     */
    static List<Slot> expand(Plan plan) {
        String pattern = pattern(plan);
        List<Slot> slots = new ArrayList<>(plan.count());

        int perFloor = plan.unitsPerFloor() == null || plan.unitsPerFloor() < 1
                ? plan.count()
                : plan.unitsPerFloor();
        int firstFloor = plan.firstFloor() == null ? 0 : plan.firstFloor();

        for (int i = 0; i < plan.count(); i++) {
            int floorOffset = i / perFloor;
            int positionOnFloor = (i % perFloor) + 1;
            Short floor = plan.firstFloor() == null ? null : (short) (firstFloor + floorOffset);

            String label = pattern
                    .replace("{block}", plan.block() == null ? "" : plan.block())
                    .replace("{floor}", floor == null ? "" : String.valueOf(floor))
                    .replace("{nn}", String.format("%02d", positionOnFloor))
                    .replace("{n}", String.valueOf(positionOnFloor))
                    .replace("{iii}", String.format("%03d", i + 1))
                    .replace("{i}", String.valueOf(i + 1));

            // A pattern that referenced no token, or referenced only a block that was not given, would
            // produce the same label for every unit — and the unique index would refuse the second one after
            // the first had been written. Cheaper to notice here.
            slots.add(new Slot(label.trim(), plan.block(), floor, i + 1));
        }
        return slots;
    }

    /** True when a plan would produce the same label twice — the check the preview screen shows. */
    static boolean hasDuplicates(List<Slot> slots) {
        return slots.stream().map(Slot::label).distinct().count() != slots.size();
    }

    /**
     * The pattern to use: the caller's, or one that suits what they gave.
     *
     * <p>Without a block the default would leave a leading hyphen, so there are two defaults rather than one
     * with a special case in the substitution.
     */
    private static String pattern(Plan plan) {
        if (plan.pattern() != null && !plan.pattern().isBlank()) return plan.pattern().trim();
        return plan.block() == null || plan.block().isBlank() ? NO_BLOCK_PATTERN : DEFAULT_PATTERN;
    }
}
