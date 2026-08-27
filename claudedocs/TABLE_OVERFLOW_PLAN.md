# Two overflow faults in the tables, and what changed app-wide

**Scope:** the row action menu being clipped, and the sticky table header that had never stuck.
**Touches:** `components/ui/RowActions.vue`, `components/ui/DataTable.vue` — so every one of the 16 pages
with a table, and every page with a row menu.

## 1. Both faults have the same cause

`DataTable` wraps its table twice:

```
.wrap    overflow: hidden        the card, for its rounded corners
.scroll  overflow-x: auto        the horizontal scroll a wide table needs
```

CSS does not allow one overflow axis to be `auto` while the other stays `visible` — the `visible` one
becomes `auto`. So `.scroll` is a scroll container on **both** axes, and `.wrap` is one too, since `hidden`
is a scroll container that simply cannot be scrolled by hand.

That single fact caused both bugs:

- **The row menu** was `position: absolute` inside a cell, and an absolutely positioned box is clipped by any
  ancestor whose overflow is not `visible`. On the last row of a table, almost the whole menu was cut off at
  the card's edge. No `z-index` helps: clipping is not stacking.
- **The sticky header** resolves against the nearest scroll container, which is `.scroll` — and `.scroll` had
  no height limit, so it never scrolled, so `top: 0` had nothing to stick to. The header had been declared
  sticky since the component was written and had never once stuck: twenty rows down, nobody could see which
  column was which.

## 2. The menu leaves the card

Rendered to `body` and positioned from the trigger's rectangle, which is outside every clipping ancestor by
construction. Right edges aligned, since the actions column is the last one; below by preference, flipped
above when below does not fit, clamped to the viewport on both axes, and the transform origin follows the
corner it is anchored by so a flipped menu does not appear to fall upwards.

`fixed` positioning means the menu does not travel with its row on its own, so a scroll repositions it and
closes it once the row has left. Listeners are attached only while it is open — a fifty-row table was holding
a hundred document listeners to serve one menu at a time.

## 3. The header gets something to stick to

`.scroll` gets a measured `max-height`, which turns it into a real scroll region and makes the existing
sticky declaration work. **This is the app-wide behaviour change**: on a page where the rows do not fit, the
table now scrolls inside its card instead of the page scrolling. The header stays visible, and so does the
pagination — the page itself usually stops scrolling at all.

The height cannot be a constant. Measured on a 900px window: 152px of chrome above a plain list, 92px of
pagination below. The 92 is the same on every page, because it is the same shared component; the 152 is not —
a page carrying stat cards or a notice pushes its table further down. So the space above is measured at
runtime and the space below is the constant:

```
cap = pane.clientHeight − (space above this table) − 96
```

measured against the workspace's scrolling pane, and skipped entirely where there is no such pane, so
marketplace and account tables keep their old behaviour. Below a floor of 260px the cap is dropped and the
page scrolls instead: on a short window, a cramped strip of a table is worse than a page that scrolls.

## 4. Verification

Driven in a browser against a network-stubbed session — the workspace rendered with a fake session and canned
rows, no credentials involved, which is what made any of this observable.

Confirmed: the menu is teleported out of the card and stays anchored to its trigger; a 260px menu with 112px
below it flips above, and re-flips both ways as its row moves through a scroll; the menu closes when its row
leaves the region and Escape returns focus; the header stays pinned to the top of the region with 25 rows
scrolling under it; the cap adapts (196px of injected chrome above shrank it by 195px) and settles again when
that chrome goes; a 560px window drops the cap and lets the pane scroll; and no `ResizeObserver` loop
warnings in the console.

### Three faults the browser found that reading had not

1. **`document.addEventListener` stored as a bare reference** and called unbound throws *Illegal invocation*.
   The menu would not have opened at all.
2. **Closing the menu on any scroll** — the first design — broke it: clicking a trigger near the edge of the
   pane scrolls it into view, that scroll event arrives on the next frame, and by then the menu had opened
   and closed itself.
3. **Observing the siblings above the table** only watches the ones that exist at mount, so a notice arriving
   on a `v-if` — a *new* element, and the likeliest case of all — was missed. The observer is on the parent
   instead, which converges rather than looping because the measurement does not depend on the cap it sets.

## 5. Worth knowing

- **Nested scrolling.** On a page where the table is capped, there are two scrollers: the content pane and
  the table. In practice the pane usually stops needing to scroll, so this is rarely felt — but on a page
  with a great deal above the table it will be.
- **The reserve is 96px for every page**, including the few with no pagination. Those tables are 96px shorter
  than they could be. Measuring what is below instead would feed the table's own height back into its own
  limit, which is the loop §4.3 describes.
