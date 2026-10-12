package com.hodi.modules.assistant;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a conversation remembers between questions.
 *
 * <p>Kept on the conversation as JSON and handed to the provider with every question, so a follow-up refines
 * the last search rather than starting again, and "the second one" can be resolved against what was last
 * shown. Nothing personal goes in here: filters and public listing references only.
 *
 * @param filters   the search in force, after the last question merged into it
 * @param shown     the listings and developments the last answer named, in the order it named them
 * @param lastIntent what the last question was about
 */
public record AssistantContext(Filters filters, List<Shown> shown, String lastIntent) {

    public static AssistantContext empty() {
        return new AssistantContext(Filters.none(), List.of(), null);
    }

    public AssistantContext withFilters(Filters f) { return new AssistantContext(f, shown, lastIntent); }
    public AssistantContext withShown(List<Shown> s) { return new AssistantContext(filters, s, lastIntent); }
    public AssistantContext withIntent(String i) { return new AssistantContext(filters, shown, i); }

    public Filters filtersOrNone() { return filters == null ? Filters.none() : filters; }
    public List<Shown> shownOrNone() { return shown == null ? List.of() : shown; }

    /**
     * A search, as the marketplace's own search understands it. Every field optional.
     *
     * @param listingType SALE or RENT
     * @param propertyType a code from the property-type catalogue
     */
    public record Filters(Integer bedrooms, BigDecimal maxPrice, String town, String listingType,
                          String propertyType) {

        public static Filters none() { return new Filters(null, null, null, null, null); }

        public boolean isEmpty() {
            return bedrooms == null && maxPrice == null && town == null && listingType == null && propertyType == null;
        }

        /**
         * A follow-up over the search in force.
         *
         * <p>What the new question says wins and what it leaves out is kept, with two exceptions that the plain
         * merge got wrong: "two bedrooms in Upper Hill" followed by "what about a commercial hall" searched for a
         * two-bedroom commercial hall. So a change of kind (homes, commercial, land) keeps only the area and
         * whether it is to buy or rent, and commercial property and land never carry a bedroom count.
         */
        public Filters after(Filters older) {
            Filters merged;
            if (older == null || older.isEmpty()) {
                merged = this;
            } else if (propertyType != null && older.kind() != null && !kindOf(propertyType).equals(older.kind())) {
                merged = new Filters(bedrooms, maxPrice, town != null ? town : older.town,
                        listingType != null ? listingType : older.listingType, propertyType);
            } else {
                merged = new Filters(
                        bedrooms != null ? bedrooms : older.bedrooms,
                        maxPrice != null ? maxPrice : older.maxPrice,
                        town != null ? town : older.town,
                        listingType != null ? listingType : older.listingType,
                        propertyType != null ? propertyType : older.propertyType);
            }
            return merged.hasRooms() ? merged
                    : new Filters(null, merged.maxPrice, merged.town, merged.listingType, merged.propertyType);
        }

        /** The filters in {@code merged} that came from before rather than from this question. */
        public Filters carriedInto(Filters merged) {
            return new Filters(
                    bedrooms == null ? merged.bedrooms : null,
                    maxPrice == null ? merged.maxPrice : null,
                    town == null ? merged.town : null,
                    listingType == null ? merged.listingType : null,
                    propertyType == null ? merged.propertyType : null);
        }

        /** Whether a bedroom count means anything for this kind of property. */
        private boolean hasRooms() {
            return propertyType == null || "HOME".equals(kindOf(propertyType));
        }

        /** HOME, COMMERCIAL or LAND; null when nothing says. A bedroom count alone means a home. */
        private String kind() {
            if (propertyType != null) return kindOf(propertyType);
            return bedrooms != null ? "HOME" : null;
        }

        private static String kindOf(String type) {
            return switch (type) {
                case "COMMERCIAL", "LAND" -> type;
                default -> "HOME";
            };
        }
    }

    /**
     * One thing an answer named.
     *
     * @param kind PROPERTY or DEVELOPMENT
     */
    public record Shown(String kind, String reference, String title) {}
}
