# Mobile first, followed through

**Date:** 22 September 2026 · **Branch:** `feature/coop-bank` (frontend; no backend change)

## What is wrong

The app was meant to be mobile first. It is desktop first with patches. Measured at 390px (Playwright, real
viewport) and read through every layout, shared component and page:

- **The public site has no navigation on a phone.** `MarketplaceLayout.vue:173` sets `nav { display: none }`
  below 700px and offers nothing in its place: Browse, What can I afford?, New developments, Site updates,
  Auctions, Services and List a property are unreachable. The bar wraps to two rows (100px) to fit "Sign in"
  and "Create account".
- **The search page shows everything at once.** Heading on two lines, five filter chips, sort, list/map,
  save search — before the first listing.
- **Tables scroll sideways inside their card.** `DataTable` has no stacked mode. Payments declares 1,068px of
  columns, Statements 1,008px, against a 322px card interior: three screens of sideways travel. Text is cut
  to "Kevi…", "B-2…".
- **The step wizard rail cannot shrink, wrap or scroll** (`StepWizard.vue:289-299`). Development edit has 7
  steps (~680px rail in 362px); property edit 5; Send money, seller onboarding and auction lots 4. Inside a
  modal the outer steps are clipped and unreachable.
- **Payment detail's five-column ledger** (`PaymentDetailView.vue:335`) has no stacking rule and sits inside
  `overflow: hidden`: clipped, not scrollable.
- **Every input is 14px** (`FormField.vue:169` `font: inherit`), so iOS zooms the page on every focus.
- **Touch targets are desktop-sized**: toolbar controls 38px, row menu trigger 28px, modal close 30px, small
  buttons 33px, pagination 32px.
- **No safe-area insets anywhere**, `100vh` on three of four layouts, and two failure modes for overflow:
  clipped on public pages (`html { overflow-x: clip }`), a scrollbar in the back office.
- Long money in the dashboard tiles clips ("KES 176,406,355" at 2 columns); the dashboard receipts table and
  the admin property detail table have no scroller; the results map is a fixed 620px tall.

What already works and stays: the back-office drawer and hamburger (`AdminLayout.vue:761-787`), modal
max-widths, the teleported and edge-clamped row menu, the select's viewport cap, the chart container query,
the mobile-first `.filter-row` / `.grid-2` / `.grid-auto` utilities, the auth layout, and the unit, property
and development detail pages, which stack correctly.

## Decisions taken

- **Public top bar on a phone:** the logo mark only (name hidden), Sign in as an icon, and a hamburger that
  holds every other top-bar item: the seven nav links, Create account, and the theme toggle. Signed in, the
  hamburger holds the account links too.
- **Search page on a phone:** the heading goes; the search input stays alone with a **Filters** button that
  opens the existing filter modal, which also takes sort and the list/map switch. The active filter count sits
  on the button.
- **Tables on a phone stack into cards** rather than scroll: the first column is the card's title, the rest
  become label/value lines, actions sit at the card's foot. Sideways scrolling remains for tablets and up.
- **Breakpoints become tokens**, two of them: `phone` at 640px and `tablet` at 1024px. Seventeen ad-hoc
  numbers today become these two, mobile-first (`min-width`) wherever a rule is touched.

## What changes

**Step 0 — foundations.** `index.html` gets `viewport-fit=cover`. `theme.css` gains the two breakpoint
tokens as documented constants and a touch block: below 640px and on coarse pointers, inputs and selects
are 16px, `--control-h` is 44px, and the shared small controls (row menu trigger, modal close, pagination,
`AppButton.sm`, icon buttons) get a 44px hit area through padding or a pseudo-element without changing
their look on desktop. Sticky bars, the drawer, modals and any fixed bottom bar pad by
`env(safe-area-inset-*)`. Layouts use `100dvh`. `AppModal` becomes a bottom sheet below 640px: full width,
top corners rounded, no 40px top gap, footer padded for the home indicator, body still scrolls.

**Step 1 — the public top bar.** `MarketplaceLayout`: below 640px the brand shows the mark only, Sign in is
an icon button, and a hamburger opens a right-hand drawer with the nav links, Create account and the theme
toggle, with a scrim and Escape to close, focus moved into the drawer. `AccountLayout` gets the same drawer
for its pills. The bar returns to one row (62px).

**Step 2 — the search page.** `MarketplaceSearch`: below 640px hide the heading and the chip row; show the
search input and a Filters button with a count; the existing filter modal grows a sort and a list/map
section. Results map height becomes `60dvh` on phones. Listing cards already fit at one column.

**Step 3 — tables stack.** `DataTable` gains a `stackBelow` prop (default: the phone token). Below it, rows
render as cards using the column labels; a column may opt out with `hideOnPhone`; the actions slot renders in
the card foot. Applied by default, then the heavy lists are tuned: Payments, Statements, Bookings, Unit
inventory, Property list (which columns hide, which is the title). The dashboard receipts table and the
admin property detail table get the same treatment or a scroller.

**Step 4 — the wizard rail.** `StepWizard` below 640px shows "Step 3 of 7 · Title" with a progress bar and
Back/Next; the full rail returns at tablet width. Fixes development edit, property edit, Send money, seller
onboarding and auction lots at once.

**Step 5 — detail pages and dashboards.** Payment detail's ledger stacks to one column below 640px and the
sheet loses `overflow: hidden` on phones; the sum and stamp sizes drop. Booking detail's fact grid goes to one
column; disbursement detail's `140px 1fr` facts stack; the dashboard funnel rows and analytics stage rows
lose their fixed pixel columns; the dashboard tiles go to one column below 400px so long money fits. Unit and
property pages get a fixed bottom action bar on phones (Ask a question · Arrange a viewing · Make an offer)
so the three calls to action are not a screen's length below the price.

**Step 6 — popovers.** The date pickers are teleported to `body` like the select and the row menu, so a
picker inside a scrolling table is not cut off, and their inputs go to 16px on phones.

**Step 7 — verification.** A Playwright pass at 390px and 768px over every route (public, account, back
office with a signed-in session) asserting no horizontal overflow, no element wider than the viewport, no
input under 16px, no tap target under 44px on coarse pointers. Kept as a checked-in script so it can be
rerun.

## Not in scope
- A native app shell or offline behaviour.
- Redesigning page content; this is layout, navigation and touch.
- The desktop experience above 1024px, which stays as it is.

## Progress

| Step | State | Notes |
|---|---|---|
| 0 | **Done, 22 September** | `--bp-phone: 640px`, `--bp-tablet: 1024px` documented in `theme.css`; `viewport-fit=cover`. Touch block (`max-width: 639.98px` or `pointer: coarse`): `--control-h` 44px, inputs/selects/textareas 16px (the stylesheet's one `!important`; one-character code boxes exempt), `.hit` pseudo-element gives a 44px target without changing the drawn size — applied to the row-menu trigger, modal close, pagination buttons, `AppButton.sm`, the bars' icon buttons and the workspace burger. Safe-area insets on the marketplace and account bars (including their phone rules), the workspace topbar and drawer, and the content's bottom padding; `100dvh` beside `100vh` on the four layouts. `AppModal` below 640px is a bottom sheet: full width, top corners only, header and footer fixed with a scrolling body, footer padded past the home indicator. Verified in Playwright at 390px. |
| 1 | **Done, 22 September** | `useViewport.ts` (`usePhone`, `useBelowTablet` on the two tokens via matchMedia) and `NavDrawer.vue` (teleported right-edge panel with scrim, Escape, focus into the panel, body scroll lock, safe-area padding). `MarketplaceLayout`: below 1024px the seven links move to the drawer behind a Menu button, with Create account / Sign in (or My account / Workspace) and the theme toggle; below 640px the brand is the mark only and Sign in / My account / Workspace are icon buttons; the bar is one row (57px at 390). `AccountLayout`: the nine tabs move to the same drawer below 1024px. Route change closes the drawer. Verified in Playwright at 390 and 768. |
| 2 | **Done, 22 September** | `MarketplaceSearch` below 640px: heading hidden, count kept as one small line; head tools become the search box (flex) and a `Filters` button with the active count, both at `--control-h`; sort, list/map and Save this search hidden from the bar and shown in a new "Order and view" section at the top of the filter dialog (`.phone-only`); the select row (`.filterbar`) hidden; page padding 14px; map `60dvh`. Active chips remain above the results. Verified in Playwright at 390: tools row 44px, search 266px wide, Filters 87×44, dialog sections Order and view / Kind / Bedrooms / Price / Town / Features as a 390px sheet. |
| 3 | Pending | |
| 4 | Pending | |
| 5 | Pending | |
| 6 | Pending | |
| 7 | Pending | |
