# The sidebar: fixed to the viewport, and a menu that fits in it

**Reported:** the sidebar scrolls with the page instead of staying put, and should be collapsible and not
scrollable — as axis's is.
**Touches:** `layouts/AdminLayout.vue`.

## 1. Why it scrolls

Not a missing `position: fixed`. The sidebar *is* `position: sticky; top: 0`, and the layout deliberately
uses `min-height: 100vh` rather than `height`, with this reasoning recorded in the file: shorter than the
viewport and sticky pins it; taller and the page scrolls to reveal the rest, with no threshold to maintain.

The menu is taller. **`AdminLayout` declares 35 nav items in 5 groups**, and a platform superadmin has
permission to see all of them. Thirty-five rows, five group labels, the brand bar, the organisation line and
a two-row footer come to well over 1,400px; no laptop viewport is that tall. A sticky element taller than the
viewport scrolls with the page until its bottom edge arrives — which is exactly the reported symptom, on
exactly the account most likely to be demonstrating the product.

So the fault is not the sticky positioning. **It is that the menu does not fit, and the layout's answer to
not fitting was to let the page grow.**

This also means the obvious fix is a trap: pinning the sidebar to `height: 100vh` on its own would clip the
last several items with nothing to indicate they exist. The file already records that exact bug from an
earlier attempt — `scrollHeight === clientHeight` reported no overflow, so it survived a check.

## 2. What axis does

Three things, and the third is the one that makes the other two safe:

1. `.admin-shell { height: 100vh }` — the shell is the viewport, not a minimum.
2. `.admin-main { height: 100vh; overflow: hidden }` with `.admin-content { height: calc(100vh - 60px);
   overflow-y: auto }` — **the content area is the only scroller on the page.**
3. The nav groups are an **accordion**: on each navigation only the group owning the current route is open.
   Its comment is the reason this works — *"arriving anywhere shows you where you are without a wall of
   links"* — and the side effect is that the menu is a handful of rows plus four headings, which fits.

## 3. The change

**Structure** — the page stops scrolling; one pane inside it does:

| | now | after |
|---|---|---|
| `.shell` | `min-height: 100vh` | `height: 100vh; overflow: hidden` |
| `.sidebar` | `sticky`, `min-height: 100vh` | a flex child of a viewport-tall row; no sticky needed |
| `.main` | `flex: 1` | `height: 100vh; overflow: hidden` |
| `.content` | `flex: 1` (scrolls with the page) | `flex: 1; overflow-y: auto` — the only scroller |
| `.topbar` | `sticky; top: 0` | plain; it cannot move once the page does not scroll |

**The menu** gains the accordion. Each group heading becomes a button with a chevron; the items sit in a
`grid-template-rows: 0fr → 1fr` wrapper so opening animates to the content's own height rather than to a
guessed `max-height`. On navigation, the group owning the route opens and the others close; a group closed by
hand stays closed until the next navigation, which needs open/closed stored per label rather than a single
"which one is open" ref.

The heading also carries its own active state when it holds the current page — otherwise a collapsed group
gives no sign of containing where you are, and "it happens to be open" is equally true of a group somebody
opened by hand.

## 4. Where "not scrollable" gives way, and why

With the accordion, the expanded menu is 5 headings plus one group's items — about 12 rows at worst. That
fits every desktop viewport, and in normal use nothing scrolls. Two cases still exceed it:

- **Somebody opens several groups at once.** Their choice, and reversible.
- **The collapsed rail.** Labels are hidden, so an accordion has nothing to collapse: the rail shows all 35
  icons, and 35 × 40px overflows any screen.

For those, `nav` gets `overflow-y: auto` with a thin scrollbar. This is a deliberate softening of "not
scrollable": a scrollbar that appears only in those two cases is worth having, because the alternative is
items that exist and cannot be seen or reached — the fault §1 describes. The default state, which is what the
instruction is really about, does not scroll.

The mobile drawer keeps the inner scroll it already has, for the reason already recorded there: a landscape
handset is 380px tall and clipping "Sign out" would be worse.

## 5. Not in this change

- **35 items in one menu is the deeper problem.** The accordion makes it navigable; it does not make it
  well-organised. Whether "Business" should hold fifteen items is a product question, not a layout one.
- **`AccountLayout`** has a sticky element of its own, but it is a buyer's account nav of a few links and is
  nowhere near viewport height. Left alone.
- **The rail's overflow.** A hover flyout per group would let 35 icons become 5, and is a bigger piece of
  interaction design than this fix.

## 6. Verification

`vue-tsc`, `vite build`, and — since the workspace needs a sign-in I cannot perform — the checks worth doing
by hand: scroll a long page and confirm the sidebar and topbar stay put; confirm the group holding the
current page is the one open after each navigation; collapse to the rail and confirm all icons remain
reachable.

---

## 7. As built

Structure and accordion as §2–§3. Three things surfaced while doing it that the plan had not anticipated.

**The router's scroll reset stopped working, silently.** `router/index.ts` carries
`scrollBehavior: () => ({ top: 0 })`, which scrolls the *window* — and the window no longer scrolls in this
layout, the content pane does. Leaving the bottom of a long list for another page would have arrived halfway
down it, with the previous page's scroll position still in the pane. The layout now resets its own pane on
navigation. The router's rule is untouched and still serves the marketplace and account layouts, which scroll
normally.

This is the kind of breakage that does not show up in a build or a type check, and would have read as "the
new sidebar broke navigation" rather than as a consequence of moving the scroller.

**`100vh` had to become `100dvh`.** Harmless while the shell was a *minimum* height; not harmless once it is
pinned, because on iOS Safari `100vh` counts the space behind the URL bar and the sidebar's footer would sit
underneath it. Declared twice, `vh` then `dvh`, so browsers without `dvh` keep the first.

**The active-link marker was nearly clipped.** The sidebar's `overflow: visible` was replaced with `hidden`,
and `.navlink.router-link-active::before` is positioned at `left: -8px`. It survives — `.navlink` carries
`margin: 1px 8px`, so the marker lands at x=0 to 3, flush inside the box rather than outside it — but it was
worth checking rather than discovering later, and the same arithmetic holds in the rail at `margin: 1px 10px`.
`overflow-x: hidden` is now explicit on the nav, because `overflow-y: auto` alone makes the x axis compute to
`auto` and a transient horizontal scrollbar in a 248px menu is noise.

Also: the group heading matches the current route on its name and then on a whole path segment, longest match
first. A detail page is its own route — `listing` at `/app/listings/:id` against a menu item called
`listings` — so name-only matching would have left every detail page with no group open, and a loose prefix
would have let `/app/users` claim `/app/usertypes`.

### Verified

`vue-tsc` and `vite build` clean. The only `IntersectionObserver` in the app is in the marketplace search,
under a layout this change does not touch.

**Not verified: any of it on screen**, for the usual reason — the workspace is behind a sign-in I will not
perform. Worth a minute each: scroll a long list and confirm the sidebar and topbar hold still; navigate and
confirm the pane returns to the top and the group holding the new page is the one open; collapse to the rail
and confirm every icon is still reachable.
