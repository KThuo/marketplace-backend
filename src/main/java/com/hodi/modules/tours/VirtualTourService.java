package com.hodi.modules.tours;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.tours.TourDtos.EditTourRequest;
import com.hodi.modules.tours.TourDtos.SaveTourRequest;
import com.hodi.modules.tours.TourDtos.TourResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Walkthrough videos, for whichever owner the caller has already authorised.
 *
 * <p>The same arrangement as {@code MediaAssetService}: this class decides nothing about who may do what. It
 * takes an owner type and an id that {@code PropertyTourService} or {@code DevelopmentTourService} has
 * resolved and checked. A store that authorised three owner types would have to know about two modules, and
 * would be the place a fourth forgets to check.
 *
 * <h2>Saving is two steps, and the split is on purpose</h2>
 *
 * <p>{@link #prepare} parses the link and the rooms and asks YouTube whether the video will play. It runs
 * outside any transaction, because it can wait four seconds on somebody else's server and a database
 * connection held open for that is one the rest of the site cannot have. {@link #add} is the short
 * transactional write that follows. The caller authorises <em>before</em> preparing, so nobody without the
 * right can make this server call out.
 */
@Service
@RequiredArgsConstructor
public class VirtualTourService {

    public static final String PROVIDER_YOUTUBE = "YOUTUBE";

    /** A plain house or a listing of any kind that owns its own tour. */
    public static final String OWNER_PROPERTY = "PROPERTY";
    public static final String OWNER_UNIT_TYPE = AppConstant.MEDIA_OWNER_UNIT_TYPE;
    public static final String OWNER_DEVELOPMENT = AppConstant.MEDIA_OWNER_DEVELOPMENT;
    private static final Set<String> OWNER_TYPES = Set.of(OWNER_PROPERTY, OWNER_UNIT_TYPE, OWNER_DEVELOPMENT);

    /** Where a tour on a listing came from — the same words the photographs use. */
    public static final String SOURCE_OWN = "OWN";
    public static final String SOURCE_TYPOLOGY = "TYPOLOGY";
    public static final String SOURCE_DEVELOPMENT = "DEVELOPMENT";

    /**
     * Six: the house, the garden, the drive to the gate, and room to spare.
     *
     * <p>A product judgement. Past this it is a playlist, and YouTube already does playlists better than a row
     * of tabs under a listing could.
     */
    static final int MAX_PER_OWNER = 6;

    private final VirtualTourRepository repository;
    private final VideoCheck videoCheck;
    private final AuditService audit;

    /** An owner, as the authorising caller resolved it. */
    public record Owner(String type, Long id, Long tenantId, Long institutionId) {
        public Owner {
            if (!OWNER_TYPES.contains(type)) {
                throw new HodiException("Tours cannot be attached to a " + type + ".", HttpStatus.BAD_REQUEST);
            }
        }
    }

    /** A tour that has been read, checked and is ready to write. */
    public record Prepared(YouTubeLinks.Parsed link, String title, List<TourChapters.Chapter> chapters,
                           boolean unverified) {}

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TourResponse> list(String ownerType, Long ownerId, String source) {
        return repository.findForOwner(ownerType, ownerId).stream().map(t -> toResponse(t, source, null)).toList();
    }

    /** Which of these owners hold at least one tour. Empty in, empty out, with no query. */
    @Transactional(readOnly = true)
    public Set<Long> ownersWithTours(String ownerType, Collection<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) return Set.of();
        return Set.copyOf(repository.ownersWithTours(ownerType, ownerIds));
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Reads the link and the rooms and asks YouTube about the video. No transaction; see the class comment.
     *
     * <p>Refusals are the seller's to act on and say so: a video that does not exist, and one that exists but
     * will not play on anybody else's page — which is what a private or embed-disabled video does, and which
     * a seller cannot see from their own signed-in YouTube tab, where it plays perfectly.
     */
    public Prepared prepare(SaveTourRequest request) {
        YouTubeLinks.Parsed link = YouTubeLinks.parse(request.url());
        List<TourChapters.Chapter> chapters = TourChapters.parse(request.chapters());

        VideoCheck.Result checked = videoCheck.check(link.videoId());
        switch (checked.verdict()) {
            case MISSING -> throw new HodiException(
                    "YouTube has no video at that link. It may have been deleted, or the link was cut short.",
                    HttpStatus.BAD_REQUEST);
            case NOT_EMBEDDABLE -> throw new HodiException(
                    "That video is private, or its owner has turned off embedding, so it would not play on the "
                            + "listing. In YouTube Studio set it to Public or Unlisted and allow embedding.",
                    HttpStatus.BAD_REQUEST);
            default -> { }
        }
        String title = blankToNull(request.title());
        if (title == null) title = truncate(checked.title(), 160);
        return new Prepared(link, title, chapters, checked.verdict() == VideoCheck.Verdict.UNKNOWN);
    }

    @Transactional
    public TourResponse add(Owner owner, Prepared prepared, String source) {
        if (repository.countForOwner(owner.type(), owner.id()) >= MAX_PER_OWNER) {
            throw new HodiException("That is the most tours this can carry (%d). Remove one first."
                    .formatted(MAX_PER_OWNER), HttpStatus.CONFLICT);
        }
        if (repository.holds(owner.type(), owner.id(), prepared.link().videoId())) {
            throw new HodiException("That video is already one of the tours here.", HttpStatus.CONFLICT);
        }
        VirtualTour row = repository.save(VirtualTour.builder()
                .ownerType(owner.type())
                .ownerId(owner.id())
                .tenantId(owner.tenantId())
                .institutionId(owner.institutionId())
                .provider(PROVIDER_YOUTUBE)
                .videoId(prepared.link().videoId())
                .startSeconds(prepared.link().startSeconds())
                .title(prepared.title())
                .chapters(new ArrayList<>(prepared.chapters()))
                .sortOrder((int) repository.countForOwner(owner.type(), owner.id()))
                .createdBy(AuthContext.username())
                .build());
        audit.record(AppConstant.ACTION_CREATE, "VirtualTour", row.getId(), null,
                owner.type() + " " + owner.id() + " gained a tour");
        return toResponse(row, source, prepared.unverified() ? Boolean.TRUE : null);
    }

    /** The title, the rooms and where it starts. Which video it is does not change: that is a new tour. */
    @Transactional
    public TourResponse edit(Owner owner, String tourHashId, EditTourRequest request, String source) {
        VirtualTour row = requireOf(owner, tourHashId);
        row.setTitle(blankToNull(request.title()));
        row.setChapters(new ArrayList<>(TourChapters.parse(request.chapters())));
        if (request.startSeconds() != null) {
            if (request.startSeconds() < 0) {
                throw new HodiException("A tour cannot start before the video does.", HttpStatus.BAD_REQUEST);
            }
            row.setStartSeconds(request.startSeconds());
        }
        row.setUpdatedBy(AuthContext.username());
        audit.record(AppConstant.ACTION_UPDATE, "VirtualTour", row.getId(), null, "tour edited");
        return toResponse(repository.save(row), source, null);
    }

    /**
     * Moves a tour to a position, and renumbers the rest.
     *
     * <p>The first tour is the one a buyer's page opens on, so order is a choice the seller makes, not an
     * accident of which they pasted first.
     */
    @Transactional
    public List<TourResponse> move(Owner owner, String tourHashId, int toIndex, String source) {
        VirtualTour moving = requireOf(owner, tourHashId);
        List<VirtualTour> rows = new ArrayList<>(repository.findForOwner(owner.type(), owner.id()));
        rows.removeIf(t -> t.getId().equals(moving.getId()));
        rows.add(Math.max(0, Math.min(toIndex, rows.size())), moving);
        renumber(rows);
        return rows.stream().map(t -> toResponse(t, source, null)).toList();
    }

    @Transactional
    public void remove(Owner owner, String tourHashId) {
        VirtualTour row = requireOf(owner, tourHashId);
        row.setStatus(AppConstant.STATUS_DELETED);
        row.setStatusFlag(AppConstant.FLAG_DELETED);
        row.setUpdatedBy(AuthContext.username());
        repository.save(row);
        // Closed up, so the next paste lands at the end rather than in a gap nobody can see.
        renumber(new ArrayList<>(repository.findForOwner(owner.type(), owner.id())));
        audit.record(AppConstant.ACTION_DELETE, "VirtualTour", row.getId(), null, "tour removed");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void renumber(List<VirtualTour> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getSortOrder() != i) {
                rows.get(i).setSortOrder(i);
                repository.save(rows.get(i));
            }
        }
    }

    /** The tour, and it has to be this owner's — an id from anywhere else is not found here. */
    private VirtualTour requireOf(Owner owner, String tourHashId) {
        VirtualTour row = repository.findById(HashIdUtil.decodeId(tourHashId))
                .filter(t -> t.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", tourHashId));
        if (!row.getOwnerType().equals(owner.type()) || !row.getOwnerId().equals(owner.id())) {
            throw new ResourceNotFoundException("Tour", tourHashId);
        }
        return row;
    }

    /**
     * The addresses are built here, from the id, on hosts chosen here.
     *
     * <p>{@code hqdefault} because it exists for every video; {@code maxresdefault} does not, and the client
     * tries it first and falls back on the error.
     */
    TourResponse toResponse(VirtualTour t, String source, Boolean unverified) {
        String id = t.getVideoId();
        String watch = "https://www.youtube.com/watch?v=" + id
                + (t.getStartSeconds() > 0 ? "&t=" + t.getStartSeconds() + "s" : "");
        List<TourChapters.Chapter> chapters = t.getChapters() == null ? List.of() : List.copyOf(t.getChapters());
        return new TourResponse(
                HashIdUtil.encodeId(t.getId()),
                t.getProvider(),
                id,
                t.getTitle(),
                t.getStartSeconds(),
                chapters,
                TourChapters.format(chapters),
                "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg",
                watch,
                t.getSortOrder(),
                source,
                unverified,
                null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
