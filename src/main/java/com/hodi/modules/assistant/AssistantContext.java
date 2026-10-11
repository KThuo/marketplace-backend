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

        /** The new question's filters over the old ones: what it says wins, what it leaves out is kept. */
        public Filters over(Filters older) {
            if (older == null) return this;
            return new Filters(
                    bedrooms != null ? bedrooms : older.bedrooms,
                    maxPrice != null ? maxPrice : older.maxPrice,
                    town != null ? town : older.town,
                    listingType != null ? listingType : older.listingType,
                    propertyType != null ? propertyType : older.propertyType);
        }
    }

    /**
     * One thing an answer named.
     *
     * @param kind PROPERTY or DEVELOPMENT
     */
    public record Shown(String kind, String reference, String title) {}
}
