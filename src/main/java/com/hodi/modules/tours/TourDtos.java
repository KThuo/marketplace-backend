package com.hodi.modules.tours;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** What the tour endpoints take and return. */
public final class TourDtos {

    private TourDtos() {}

    /**
     * A tour as pasted.
     *
     * @param url      any YouTube link form, or a bare id
     * @param title    optional; YouTube's own title is used when this is blank
     * @param chapters the rooms, one per line, as {@link TourChapters} reads them
     */
    public record SaveTourRequest(
            @NotBlank(message = "Paste the link to the video") @Size(max = 500) String url,
            @Size(max = 160, message = "A title is at most 160 characters") String title,
            @Size(max = 4000, message = "That is more than a list of rooms") String chapters) {}

    /** Changing what a tour says, not which video it is. A different video is a different tour. */
    public record EditTourRequest(
            @Size(max = 160, message = "A title is at most 160 characters") String title,
            @Size(max = 4000, message = "That is more than a list of rooms") String chapters,
            Integer startSeconds) {}

    /** Where a tour goes in its owner's list. */
    public record MoveTourRequest(int toIndex) {}

    /**
     * One tour, for the editor and for a buyer alike — nothing in it is private.
     *
     * @param source        OWN, TYPOLOGY or DEVELOPMENT: where it came from, so an inherited one is shown
     *                      without the controls to delete somebody else's
     * @param chaptersText  the rooms as the editor's box would hold them
     */
    public record TourResponse(
            String id,
            String provider,
            String videoId,
            String title,
            int startSeconds,
            List<TourChapters.Chapter> chapters,
            String chaptersText,
            String thumbnailUrl,
            String watchUrl,
            int sortOrder,
            String source,
            /** Set only on a save, and only when YouTube could not be asked: the seller should know. */
            Boolean unverified,
            /**
             * What it is a tour of, where one page shows several owners' — "Two-bedroom" beside the project's
             * own flythrough. Null where the page is about one thing and the answer is obvious.
             */
            String ownerLabel) {

        public TourResponse labelled(String label) {
            return new TourResponse(id, provider, videoId, title, startSeconds, chapters, chaptersText,
                    thumbnailUrl, watchUrl, sortOrder, source, unverified, label);
        }
    }
}
