# Progress posts as a blog — a post page, and more than one photograph

Branch: `feature/coop-bank`, both repos.

---

## 1. What is being asked

A development's progress posts should read as a blog: a feed of headers, and a **drill-down page per
post** carrying the full text and a **carousel of every photograph on it**. Posting should accept
several images at once rather than one.

## 2. What already works, and what does not

Most of the data is there. The gap is narrower than it looks.

| | Today | Needed |
|---|---|---|
| Images per post | `media_assets` caps a `PROGRESS_UPDATE` at **12**, and `imagesFor()` already returns every public one | — |
| Feed payload | `PublicProgressItem` already carries `imageUrls` (all of them) | — |
| Per-project posts | `PublicPost` already carries `imageUrls` | — |
| **A post's identity** | **None.** Neither record has an id, reference or slug | A stable public reference |
| **A post page** | **None.** There is no endpoint for one post | `GET /public/progress/{reference}` |
| **Upload** | `ProgressUpdateForm` takes `input.files?.[0]` — one file, no `multiple` | Several at once |
| Feed UI | A card list with a photo and a "+N" count that links nowhere | Cards link to the post |
| Carousel | None | One |

So the work is: **give a post a public identity**, serve one by it, and build the two screens.

## 3. Why a reference rather than a hash id

Every public marketplace route in this codebase is addressed by reference, and the router says why:

> *By reference, not by id. A reference is what a buyer is given, quotes down the phone and writes
> down — and it survives the id obfuscation.*

A post is shared — that is the whole point of a blog page — so it gets the same treatment as a
listing and a development: an `RrnGenerator` reference with a `PU` prefix, unique, backfilled for
rows that already exist.

The alternative, the public hash id, works and is one fewer column. It is rejected because a post URL
is pasted into WhatsApp, and `PU260914H4KQ` survives that legibly while an opaque hash does not.

## 4. Backend

1. **`listing_progress_updates.reference`** — `VARCHAR(32)`, unique. `V20260914150000` adds it,
   backfills every existing row, then sets `NOT NULL`. Backfilled in SQL rather than by the
   application, because a nullable-forever column is how a "temporary" nullable column ends.
2. `ProgressUpdate.reference`, allocated in `DevelopmentProgressService.create` and in
   `ProgressUpdateService` (a listing's diary shares the table and must not produce null references).
3. `PublicProgressItem` and `PublicPost` gain `reference`, so a feed card can link.
4. **`GET /api/v1/public/progress/{reference}`** on the existing `PublicProgressController` →
   `PublicPostDetail`: the post, all its image URLs, and the project's identity for the header.
   Published + `audience = PUBLIC` + the project live — the same three conditions the feed applies,
   applied in the query rather than after it, so a detailed post cannot be read by guessing a URL.

**The rule worth stating**: `findPublicByReference` must re-check the *development* is live, not only
the post. A post on a withdrawn project is not public any more, and checking only the post's own
flags is exactly how that leaks.

## 5. Frontend

5. `ProgressUpdateForm` — `multiple` on the input, uploading sequentially and reporting what failed.
   Sequential rather than parallel: `StorageService` re-encodes each image, and twelve at once is a
   heap spike on the server for no gain to a person watching a progress count.
6. **`ImageCarousel.vue`** — one image, prev/next, dot indicators, keyboard arrows, swipe. Falls back
   to a single static image when there is one, and renders nothing when there are none.
7. **`ProgressPostView.vue`** at `/progress/:reference` in the marketplace layout — header (project,
   place, date), carousel, body.
8. `ProgressFeedView` — cards become links; the "+N" count becomes real.

## 6. Out of scope

- Comments, likes, sharing metadata, RSS.
- Rich text. `body` is plain text today and stays so; making it HTML is a sanitiser decision, not a
  layout one.
- Reordering a post's photographs. `media_assets.sort_order` and the cover flag exist and the
  carousel honours them, but no UI to set them.

## 7. Status

**Done**, both repos, on `feature/coop-bank`. 232 backend tests pass (six new), frontend typechecks
and builds.

Verified against the running system rather than only in tests: all five existing posts backfilled with
unique references; `GET /public/progress/PU2608270024` returns the post with its project header; and
the three refusals each answer 404 — a STAKEHOLDERS post, an unpublished one, and an invented
reference.

One thing found along the way that was not in the scope: the workspace's attach button said "Replace"
and took a single file. Photographs accumulate — they always have, up to twelve — so the label was
wrong about what the button did. `imageCount` is now on the workspace response and the button says
"3 photos".
