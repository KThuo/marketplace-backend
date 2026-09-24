# Virtual tours: a YouTube walkthrough that keeps playing while you read

**Date:** 24 September 2026 · **Repos:** `hodimp-b`, `hodimp-f`, branch `feature/virtual-tours`

## What was asked

Virtual house tours, played from YouTube, with picture-in-picture, "taking inspiration from goggy and doing
more". goggy turned out to have none of it: it stores `youtubeUrls text[]` on a vehicle and an import
listing, and its admin forms accept pasted links, but nothing in its storefront plays them. So this is built
from scratch.

## Shape

### Where a tour lives: one table, three owners, inherited on read

`virtual_tours (owner_type, owner_id, tenant_id, institution_id, provider, video_id, title, start_seconds,
chapters jsonb, sort_order, status…)`, where `owner_type` is `PROPERTY`, `UNIT_TYPE` or `DEVELOPMENT`.

It follows the same rule as photographs, for the same reason. A house owns its tour. A typology card and its
units share the show-unit tour on the unit type, so the card and the units cannot drift apart. The project's
drone flythrough belongs to the development, and every listing in it shows it as inherited and read-only.

`PROPERTY` is allowed here, unlike on `media_assets`. That table excluded it only because `property_media`
already existed. No such table exists for tours, so a second one would be a second place to look for no
reason.

### What a seller gives: a link, and optionally the rooms

- **Any YouTube link form.** `watch?v=`, `youtu.be/`, `/shorts/`, `/embed/`, `/live/`, `m.` and
  `music.` hosts, `youtube-nocookie.com`, a bare 11-character id, and `t=`/`start=` in seconds or `1m30s`
  form. `YouTubeLinks` reduces all of them to an id and a start. Anything else is refused by name, so a
  Vimeo or TikTok link gets "that is not a YouTube link" rather than a player that never loads.
- **Checked against YouTube before it is saved.** oEmbed is asked about the id, and only the id: the URL is
  built from a validated 11-character token on a fixed host, so it is not an SSRF door. A 404 means the video
  does not exist. A 401 or 403 means it is private or cannot be embedded. Either way the seller hears it at
  paste time instead of a buyer hitting a dead player. The title comes back with it. If YouTube cannot be
  reached, the link is accepted without a title, so our network trouble does not become the seller's problem.
- **Rooms (chapters).** A seller pastes lines like `0:00 Gate` and `1:12 Kitchen`, which is the format
  YouTube descriptions already use, so it can be copied straight across. The server parses and validates it:
  in order, under 12 hours, at most 30 rooms, labels of 60 characters or fewer. The buyer gets a "jump to the
  kitchen" strip instead of scrubbing.
- **At most six per owner.** Enough for a house plus its garden and a neighbourhood drive. Past that is a
  playlist, and YouTube already does playlists.

### What a buyer gets

1. **Click-to-play facade.** The thumbnail and a play button are all the page loads. No YouTube script runs
   and no iframe exists until the buyer asks, and then only from `youtube-nocookie.com`. That keeps page
   weight down and means no tracking before consent.
2. **One player for the whole marketplace, never moved in the DOM.** Moving an iframe reloads it, and a
   reload means the tour starts again. So one `TourStage` is mounted at the app root, and the in-page panel
   is only an *anchor*: the stage lays itself over the anchor's rectangle. Docking changes CSS, not the DOM.
3. **In-page picture-in-picture.** Scroll the player out of view while it plays and it docks to a corner:
   320px on desktop, full width above the offer dock on a phone. It can be dragged, snaps to the nearest
   corner, and remembers that corner. Scroll back and it returns to the page.
4. **Carries on across pages.** Leave the listing for the search results, the affordability check or
   another listing, and the tour keeps playing in the corner with "Back to <listing>" on it. Stopping it is
   a deliberate act (×). A paused tour does not follow anybody around.
5. **System picture-in-picture where the browser has it.** Document Picture-in-Picture (Chromium 116+)
   opens the tour in an always-on-top OS window, so it keeps playing while the buyer is in their banking app
   or another tab. The browser cannot hand a cross-origin iframe across windows without reloading it, so the
   PiP window gets its own player started at the current second. When it closes, the in-page player resumes
   from wherever the PiP window got to. Browsers without the API never see the button.
6. **Rooms under the player.** Chips jump to each room, the current room is highlighted as the video plays,
   and the same chips appear in the PiP window.
7. **Findable before scrolling.** A "Video tour" pill on marketplace cards and a "Watch the tour" button on
   the gallery. The card flag is one batched query per page, not one per card.
8. **Accessible.** Every control is a named button, the dock is reachable by keyboard, arrow keys move a
   focused mini-player between corners, and `prefers-reduced-motion` turns off the glide.

### CSP

`deploy/Caddyfile` gains `https://www.youtube.com` in `script-src` (the IFrame API and its widget script)
and `https://www.youtube-nocookie.com https://www.youtube.com` in `frame-src`. Thumbnails come from
`i.ytimg.com`, which `img-src https:` already covers. oEmbed is called server-side, so `connect-src` is
unchanged.

## Files

**Backend**
- `V20260924090000__a_listing_can_be_toured.sql`: the table and its indexes.
- `modules/tours/`: `VirtualTour`, `VirtualTourRepository`, `TourDtos`, `YouTubeLinks` (parse), `TourChapters`
  (parse/format), `YouTubeOEmbed` (verify), `VirtualTourService` (owner-trusting store, like
  `MediaAssetService`).
- `modules/properties/`: `PropertyTourService` (listing access, including the shared-typology rule), with
  endpoints on `PropertyController` and `PublicPropertyController`. `PublicPropertyResponse` gains `hasTour`.
- `modules/developments/`: `DevelopmentTourService` and endpoints on `DevelopmentController`, plus a public
  `/public/developments/{ref}/tours`.
- Tests: `YouTubeLinksTest`, `TourChaptersTest` (pure), and `VirtualToursIT` (inheritance, caps, guards,
  public visibility of a non-live listing).

**Frontend**
- `utils/youtube.ts`: the same parsing, for instant feedback while typing.
- `composables/useYouTubeApi.ts`: loads the IFrame API once, into any window (the PiP window has its own).
- `stores/tourStore.ts`: the one player's state.
- `components/tours/TourStage.vue`: the player, the dock, the drag, the PiP window.
- `components/tours/VirtualTourPanel.vue`: the anchor, facade, tour picker and room chips.
- `components/tours/TourEditor.vue`: paste, preview, rooms, reorder, remove. Used by the listing form, the
  unit-type editor and the development editor.
- Public pages: `PropertyDetail`, `UnitDetail`, `DevelopmentDetail`; cards in `MarketplaceSearch`.

## Not in scope

- Vimeo, TikTok and Matterport. `provider` is a column so they can be added without a migration, but each
  needs its own player adapter and PiP story.
- Tour analytics for sellers (plays, completion, which rooms get rewatched). The player already knows all
  of it, so this is a follow-up with a clear place to hook in.
- Uploading video files. Hosting and transcoding video is what YouTube is for.

## Found while testing in a browser

- **A focused YouTube iframe scrolls the host page.** Click the docked player, move the mouse back to the
  page, and about 1.5 s later YouTube hides its controls, moves focus inside its own document, and Chromium
  smooth-scrolls *our* page to the top. `TourStage` now releases focus from the iframe on the first
  `pointerover` back on the page, and on every change of place. `pointerleave` on the stage does not work for
  this: Chromium sends no leave event to the parent for a pointer exiting a cross-origin frame.
- **YouTube Error 153 in the PiP window.** Its document is `about:blank`, so an iframe placed in it sends no
  `Referer`, and YouTube refuses the embed. `widget_referrer` does not help. The PiP player now lives inside
  `public/tour-pip.html`, a same-origin page with a real address. CSP `frame-src` gains `'self'` for it.
- **The listing and unit pages overflowed on phones before this work.** Their layout was `1fr` below 880px,
  so the figures card pushed the main column 22px past the padding. With a full-width player in that column
  it became obvious. Changed to `minmax(0, 1fr)` on both pages.
- A bug the unit tests caught before it shipped: a `"…" + "…".formatted()` whose `formatted` bound to the second
  literal only, which would have shown a literal `%d` to a seller.

## Progress

- [x] Backend: migration, module, endpoints; 32 unit tests and 10 integration tests pass, and the
      existing listing, media and development suites still pass after the `ListingAccess` extraction
- [x] Frontend: player stage, panel, editor, three public pages, the card pill; `vue-tsc` and
      `npm run build` pass
- [x] CSP
- [x] Verified in Chromium (desktop and 390px): inline overlay, docking on scroll, drag to a corner and
      the saved corner, arrow keys, room chips, switching tours, playing on across navigation and the way
      back, a paused tour closing on navigation, the PiP window with its rooms and handing back the second
      it reached
- [ ] Not verified: the seller editors in a signed-in session (the dev bootstrap login is stale), and
      Safari/Firefox (neither has Document PiP, so the button is hidden; the corner player is plain CSS)
