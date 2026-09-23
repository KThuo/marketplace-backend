# Empty states draw something; loading states hold the shape

**Date:** 23 September 2026 · **Repo:** `hodimp-f`, branch `feature/coop-bank`

## What was wrong

- **Empty tables were a glyph and a line.** `EmptyState` showed a small inbox icon. The reference project
  (`new-hodi/hodi-f`) draws a line illustration per domain — homes, people, money, a diary — so an empty page
  still looks designed.
- **Empty could show before anything was asked.** `DataTable` showed its empty state whenever `loading` was
  false and there were no rows, which on a page whose loading starts false, or whose first request is
  deferred, is before the first request. The reader was told "nothing here" and then contradicted.
- **Fifty-six places said "Loading…" in grey text** while tables and the dashboard used skeletons. The
  application waited in two different ways.

## What changed

1. **`EmptyArt.vue`** — one file of ten line illustrations on a shared ground: generic, search, houses,
   people, documents, payments, calendar, chat, error, forbidden. 160 × 120 viewBox, 1.6px strokes on
   `--border-strong`, fills on `--surface-2`, one `--accent` detail each. Nothing drawn in `--brand`, which a
   themed organisation may set to anything.
2. **`EmptyState.vue`** takes `art` (default generic) and `size`; `icon: false` still means no drawing, for
   the small inline notices on the dashboard and analytics.
3. **`DataTable`** waits to be asked. With `loading` bound, the empty state appears only after loading has
   been true once and is false again (or rows have arrived). Unbound, rows are trusted as given. `empty-art`
   passes the drawing through.
4. **`SkeletonBlock.vue`** — page (title, line, cards), list (two-line rows), lines, form (label and field).
   Every `<p v-if="loading">Loading…</p>` in the codebase became one of these, chosen by what the page is; the
   two account headers that said "Loading…" as a subtitle show a short shimmer bar instead.
5. **Every table and empty state names its drawing**, chosen by the page's domain: bookings and the diary get
   the calendar, money pages the receipt, leads and conversations the bubbles, people pages the card, homes
   the terrace, reports and reviews the documents, searches the lens, failures the cable.

## Not in scope

- Per-empty-state calls to action beyond the existing slot.
- A "forbidden" page; the drawing exists for when one is written.

## Progress

| Step | State |
|---|---|
| Illustrations, EmptyState, SkeletonBlock, DataTable guard | **Done, 23 September** |
| Sweep of 56 text loaders and the art on every table and empty state | **Done, 23 September** |
