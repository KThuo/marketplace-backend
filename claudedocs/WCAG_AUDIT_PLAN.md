# Accessibility audit — WCAG 2.1 AA against `hodimp-f`

**Companion to:** `QUANTUMNEX_IMPLEMENTATION_PLAN.md` §5 (non-functional checklist) and §24 (phase 7, first pass).
**Target:** `hodimp-f` — 105 `.vue` files, 71 pages, 4 layouts, one design system in `src/styles/theme.css`.
**Standard:** WCAG 2.1 level AA. Level AAA is noted where a token is close, but not treated as a defect.

Phase 7 listed "a real WCAG audit" as deferred deployment work. That was right about the *sign-off* — a
conformance statement needs assistive-technology testing by someone who uses one — and wrong about the
*code*. Most of what fails AA on this platform fails measurably, in the token file and in the templates, and
can be found and fixed here. This document is that part: the measurable half, done properly, with the half
that needs a human on a screen reader named rather than pretended.

---

## 1. What this audit covers, and what it cannot

**Covered, because it is decidable from the code or the rendered DOM:**

| Area | Criteria |
|---|---|
| Colour contrast, text and non-text | 1.4.3, 1.4.11 |
| Accessible names on every control | 4.1.2 |
| Label/field association, required and error state | 1.3.1, 3.3.1, 3.3.2 |
| Input purpose declared for a person's own details | 1.3.5 |
| Landmarks, headings, bypass blocks | 1.3.1, 2.4.1, 2.4.6 |
| Keyboard reachability and focus order | 2.1.1, 2.4.3 |
| Visible focus indicator | 2.4.7 |
| Dialog semantics and focus containment | 2.4.3, 4.1.2 |
| Page titles on a single-page app | 2.4.2 |
| Status messages announced without focus | 4.1.3 |
| Meaning not carried by colour alone | 1.4.1 |
| Reflow and text spacing | 1.4.10, 1.4.12 |
| Motion respecting the OS preference | 2.3.3 |

**Not covered, and why:**

- **Screen-reader behaviour.** An accessible name being present is decidable; whether NVDA reads a
  Naive UI data table in an order a blind operator can work with is not. That is a testing session with a
  person, not a grep.
- **Cognitive load and plain language** (3.1.5 is AAA, but relevant): the wording of a mortgage screen is
  worth reviewing with the business, not by me.
- **Media captions** (1.2.x): the platform has no audio or video.

---

## 2. Method — three passes, in this order

### Pass 1 — the token maths (done, §3)

Every foreground/background pair the design system actually puts together, computed against the WCAG
relative-luminance formula, in **both themes** and on the **ink** surfaces. Contrast is the one part of
accessibility that is arithmetic rather than judgement, so it goes first and it goes in whole. Threshold
4.5:1 for text under 18.66px, 3:1 for large text and for UI component boundaries and focus rings.

This pass is run by a script kept with the audit rather than done by eye, so re-running it after a token
change is one command.

### Pass 2 — the templates

Static analysis over all 105 components for the classes of defect that are visible in the source:

- interactive elements whose entire content is an icon, with no `aria-label`
- `<img>` with no `alt`
- non-native controls (`role="combobox"`, custom triggers) inside a wrapping `<label>`, which does **not**
  name them
- dialogs without `aria-labelledby`, without initial focus, or without focus restore
- pages with no `h1`
- `tabindex` above 0, and click handlers on non-interactive elements

### Pass 3 — the live walk

The static passes cannot see what the browser computes. This pass drives the running app at
`localhost:3020`, signs in as each of the eight seeded users, and on each screen in the navigation:

- computes the accessible name of every focusable element from the rendered DOM and lists the nameless ones
- walks the tab order and records anything reachable-but-invisible or visible-but-unreachable
- checks the focus ring is actually painted at 3:1 against what sits behind it
- resizes to 320px wide and 400% zoom to check reflow

Runs happen at **full screen for this display — 1512×982 CSS pixels** — rather than a nominal 1280×900,
plus the 320px pass. A layout tested only at a middling width is tested at the one width nobody has.

Phase 7 found its one defect by walking the navigation rather than by testing endpoints. The same applies
here: an audit that reads templates and never opens the app finds the missing `aria-label` and misses the
button you cannot reach with a keyboard.

---

## 3. Findings — pass 1, measured

Ratios below are computed, not estimated. `scripts/contrast-audit.py` in this repo reproduces them.

### 3.1 Text contrast, light theme — 12 failures

| Pair | Ratio | Need | Where it shows |
|---|---|---|---|
| `--text-subtle` on `--surface` | **3.10** | 4.5 | `.subtle`, `.cell-sub` — every table's second line |
| `--text-subtle` on `--bg` | **2.86** | 4.5 | subtle text directly on the workspace |
| `--text-subtle` on `--surface-subtle` | **2.89** | 4.5 | `.ref` — every reference code on the platform |
| `--text-subtle` on `--surface-3` | **2.75** | 4.5 | subtle text in chips and wells |
| `--chart-label` on `--surface` | **4.27** | 4.5 | every chart axis label |
| `--chart-label` on `--bg` | **3.94** | 4.5 | charts sitting on the workspace |
| `--brand` on `--bg` | **4.41** | 4.5 | links on the workspace background |
| `--brand-hover` on `--surface` | **3.63** | 4.5 | every link and `.cell-link` **on hover** |
| `--accent` on `--surface` | **3.70** | 4.5 | accent text on a card |
| `--warning` on `--surface` | **4.20** | 4.5 | the Pending state, everywhere |
| `--success` on `--success-bg` | **4.04** | 4.5 | Approved tag text on its own tag |
| `--warning` on `--warning-bg` | **3.75** | 4.5 | Pending tag text on its own tag |
| `--danger` on `--danger-bg` | **4.50** | 4.5 | Rejected tag — fails by rounding |
| `--info` on `--info-bg` | **4.35** | 4.5 | Info tag text on its own tag |

`--brand-hover` failing is the one worth pausing on: the resting link colour passes at 4.78 and the hover
state drops it to 3.63. A hover that *reduces* legibility is backwards — the point of the state is to
confirm the target, so it should be at least as readable as the rest.

### 3.2 Text contrast, dark theme — 7 failures

| Pair | Ratio | Need | Where it shows |
|---|---|---|---|
| `--brand` on `--surface` | **3.40** | 4.5 | every link and `.cell-link` on a card |
| `--brand` on `--bg` | **4.07** | 4.5 | links on the workspace |
| `--brand-hover` on `--surface` | **4.48** | 4.5 | link hover — fails by rounding |
| `--accent` on `--surface` | **4.39** | 4.5 | accent text |
| `--accent-600` on `--surface` | **3.28** | 4.5 | the **Sold** state on a card |
| `--text-subtle` on `--surface` | **4.07** | 4.5 | `.subtle`, `.cell-sub` |
| `--text-subtle` on `--surface-3` | **3.24** | 4.5 | `.ref`, chips |

The dark theme lifted `--success`, `--warning`, `--danger` and `--info` for the dark background and left
`--brand`, `--accent` and `--accent-600` at their light-theme values. Those three are the failures.

### 3.3 Ink surfaces — 1 failure

| Pair | Ratio | Need | Where |
|---|---|---|---|
| `--on-ink-faint` on `--ink-800` | **4.12** | 4.5 | sidebar section headings and the org sub-line |

`--brand` on the two ink surfaces measures 3.44 and 3.86, and **that is not a defect**: checking every
`color: var(--brand)` in the codebase found none of them on an ink surface. The sidebar's active item is
white on a brand tint and its icon is `--brand-light`. Recorded here because a pair matrix that includes
combinations nothing renders produces findings that push the palette around for nothing.

`--ink-700` is likewise excluded from the matrix: it is a border and hover tint on the sidebar, never a
background for text.

### 3.4 Button text on filled backgrounds — 3 failures

| Pair | Ratio | Need |
|---|---|---|
| white on `--brand-hover` | **3.63** | 4.5 |
| white on `--accent` | **3.70** | 4.5 |
| white on `--warning` | **4.20** | 4.5 |

White on `--brand` is 4.78 and passes; the **hover** state of the primary button fails. Same shape of bug as
the link hover, and the same fix.

### 3.5 Non-text contrast — the input boundary

`--border` (#e4e8ed) on `--surface` (#ffffff) is **1.23:1**; dark is **1.44:1**. Against 1.4.11's 3:1 that
is a failure *for the elements where the border is the only thing identifying a control* — text inputs,
selects and textareas, which `FormField` styles with exactly that token and `outline: none`.

It is **not** a failure for card and table borders. 1.4.11 applies to the boundary of a control that has to
be perceived as a control; a card is not a control, it has a shadow and a surface change of its own, and
raising every hairline on the platform to 3:1 would be a visual redesign under an accessibility pretext.
So the fix is scoped to the form controls, and the decorative borders are recorded as deliberately unchanged.

---

## 4. Findings — pass 2, from the templates

| # | Finding | Criterion | Files |
|---|---|---|---|
| A | Gallery thumbnails are `<button><img alt=""></button>` — no accessible name at all | 4.1.2 | `marketplace/PropertyDetail.vue:131` |
| B | Calendar month prev/next are icon-only, unlabelled | 4.1.2 | `operations/CalendarView.vue:189,191` |
| C | Two "reset to default" buttons are icon-only, unlabelled | 4.1.2 | `settings/AppearanceTab.vue:271,323` |
| D | No skip link — every screen puts the whole sidebar before the content | **2.4.1** | `layouts/AdminLayout.vue`, and the other three |
| E | `AppModal` has no `aria-labelledby`, does not move focus in, does not trap it, does not restore it | 2.4.3, 4.1.2 | `components/ui/AppModal.vue` |
| F | `AppSelect`'s trigger is a `<button role="combobox">`; inside `FormField`'s wrapping `<label>` it gets **no name**, because a button is not a labelable element | 4.1.2 | `components/ui/AppSelect.vue` + every form using it |
| G | `FormField` marks required with an `aria-hidden` asterisk and sets no `aria-required`, and its error text is not tied to the control with `aria-describedby` | 3.3.2 | `components/ui/FormField.vue` |
| H | `AppSelect`'s `role="listbox"` contains `<p>` group headings, which is not a permitted child | 1.3.1 | `components/ui/AppSelect.vue` |
| I | Route change does not move focus; a keyboard user lands back at the top of the sidebar | 2.4.3 | `router/index.ts` |
| J | `CompliancePage` renders no `h1` | 1.3.1, 2.4.6 | `kyc/CompliancePage.vue` |

**Already correct, and worth recording so it is not "fixed" twice:** `lang="en"` on the document;
`document.title` set per route from `meta.title` (2.4.2); a global `:focus-visible` ring; `prefers-reduced-motion`
honoured across every animation (2.3.3); `alt` on every `<img>` with decorative ones correctly empty;
`aria-label` on the theme toggle, sign-out, burger and modal close; `aria-current="page"` on pagination;
landmark elements (`header`/`nav`/`main`/`aside`/`footer`) in all four layouts; `Escape` closing dialogs.

---

## 5. What gets changed

**Tokens** (`src/styles/theme.css`, and the derivations in `stores/themeStore.ts` so a configured brand
lands in the same place): darken the failing light-theme foregrounds, lighten the three dark-theme brand
and accent values, fix both hover states so hovering never reduces contrast, raise the tag foregrounds
against their own tag backgrounds, and give form controls a boundary token at 3:1.

The constraint on every one of these is that **the palette has to stay recognisably itself**. A teal that
passes AA by becoming navy has solved the wrong problem. Each change is the smallest luminance step that
clears the threshold, verified by re-running the script rather than judged by eye.

`--brand` is runtime-configurable, so a token fix cannot guarantee an operator's chosen accent passes. What
this audit can do is make the *shipped default* pass and make the derivations (`hover`, `pressed`) preserve
the direction of the relationship. A configured colour that fails is a settings problem, and the honest
answer is to say so in the appearance screen rather than silently render it.

**Components:** the ten template findings in §4, each in the shared component where there is one, so the fix
lands on every screen at once rather than 71 times.

## 6. Verification

1. `scripts/contrast-audit.py` — zero failures in both themes and on ink.
2. `npm run build` (which runs `vue-tsc --noEmit` first) clean.
3. The live walk of pass 3 re-run after the fixes, as at least the platform admin and a buyer, confirming:
   every focusable element has a name, tab order reaches the primary action on each screen, the skip link
   works, a dialog returns focus to what opened it, and 320px reflow does not lose content.
4. What remains for a human: a session with an actual screen reader, and a keyboard-only run by somebody who
   does not already know where the controls are.

---

## 7. What was changed — as built

`npm run build` (which runs `vue-tsc --noEmit` first) is clean, and `scripts/contrast-audit.py` reports
**166 pairs checked in both themes — all pass**. 69 files changed.

### 7.1 The palette, and one structural decision

The tokens moved by the smallest luminance step that clears the threshold, computed rather than chosen.
The one decision worth recording is **splitting brand-as-fill from brand-as-text**:

| Token | Job |
|---|---|
| `--brand` | a fill — button background, focus tint, checkbox. Carries white text at 4.8:1 |
| `--brand-text` / `--brand-text-hover` | brand-coloured *words* — links, `.cell-link`, selected states, the focus ring |

They exist because the two are read against opposite backgrounds. `--brand` at 4.41:1 on the workspace is
under AA for a link; the same colour as a button fill with white on it is fine. One token could not be both,
and darkening `--brand` until links passed would have repainted every button on the platform to fix text.

Two hover states were **inverted**, which is the finding I would keep if I could keep only one. Hover
*lightened* the fill — `shade(accent, +0.12)` on the token, `filter: brightness(1.05)` on the primary
button and the marketplace call-to-action — so white label text fell to 3.63:1 and 4.43:1. The state whose
whole purpose is to confirm what you are about to click was the state you could not read. Hover and press
now both darken: 4.8 at rest → 5.7 on hover → 6.3 pressed.

Also: `--on-success` / `--on-warning` / `--on-danger` / `--on-info`, because the four semantic tokens flip
between themes. White on the dark theme's green is 2.42:1 — a destructive button in dark mode was failing
AA outright. The `--on-*` pair is white in one theme and deep ink in the other, so one rule serves both.
And `--border-control`, at 3:1, for the boundary of anything that has to be perceived as a control: inputs,
selects, textareas, secondary buttons, chips, icon buttons, the search box. Cards keep `--border` at
1.23:1 deliberately — 1.4.11 is about controls, and raising every hairline on the platform would be a
redesign wearing an accessibility badge.

### 7.2 A configured brand is computed, not assumed

`--brand` is runtime-configurable, so fixing the shipped default would have fixed the one palette nobody
runs. `themeStore` now derives `--brand-text` by stepping the configured accent — darker on a light
surface, lighter on a dark one, using HSL lightness so the chroma survives — until it clears 4.5:1 against
the least forgiving surface of the current mode. The hover variant goes one step further, so hovering can
no longer reduce legibility whatever colour an operator picks.

Verified against the live app, which is running a **configured** accent of `#1B7F79` rather than the
compiled default: the store produced `--brand-text: #26b1a9` in dark mode, measuring 4.90:1 on the inset
well and 6.15:1 on a card, with white on the fill at 4.82:1 and its hover at 5.73:1. The derivation is
mode-dependent, so `toggleMode` re-runs it — an inline style on `:root` outranks the `[data-theme]` block,
and without that call the text colour would be a theme behind.

### 7.3 The components

| Fixed | Criterion |
|---|---|
| Skip link on all four layouts, `<main>` given `id="main"` and `tabindex="-1"` | 2.4.1 |
| Route change moves focus to `<main>`; skipped on first load | 2.4.3 |
| `AppModal`: `aria-labelledby` its own `<h2>`, focus moved to the first control, Tab trapped and wrapped, focus handed back to the opener on close | 2.4.3, 4.1.2 |
| `FormField` publishes label/hint/error ids and provides them; sets `aria-required`, `aria-invalid`, `aria-describedby` on a native control from outside; error carries `role="alert"` | 1.3.1, 3.3.1, 3.3.2, 4.1.3 |
| `AppSelect` takes its name from the enclosing field, or an `aria-label` when bare; gained `aria-controls`; group headings made presentational and folded into each option's name | 4.1.2, 1.3.1 |
| **33 filter selects** named — every one previously announced its own current value ("Every state", then "Approved") and never what it filtered | 4.1.2 |
| Gallery thumbnails, calendar month steppers, two reset buttons, the marketplace search box and both price inputs | 4.1.2, 3.3.2 |

The `FormField` change is the one with reach. The wrapping `<label>` names a native input for free and
names nothing else — and `AppSelect`'s trigger is a `<button role="combobox">`, because a native `<select>`
cannot carry a two-line option. So every select inside a form read as "button". Publishing the label by id
and having the control inject it fixes all 37 of those at once, with no call-site changes.

### 7.4 What the live walk found that static analysis could not

Three defects, all reflow (1.4.10), all invisible in the source:

1. **The marketplace header clipped "Create account" at 320px.** Brand lockup, theme toggle, Sign in and
   Create account need about 340px between them; the bar hid its nav below 700px but never wrapped, and
   `html { overflow-x: clip }` — there to stop a stray wide element scrolling the page sideways — *clipped*
   the overflow rather than offering a scrollbar. The control was gone, not just off-screen.
   `AccountLayout`'s bar already wrapped at that breakpoint. This one did not, and that was the whole bug.
2. **Auction lot cards ran 24px past a 320px viewport**, from `minmax(320px, 1fr)` — a track minimum wider
   than the content box cannot shrink. Now `minmax(min(320px, 100%), 1fr)`; the same guard was applied to
   the offers list at 330px.
3. **The affordability panel overflowed by 6px**, and the cause took measuring rather than reading: the
   narrow-width rule said `grid-template-columns: 1fr`, whose automatic minimum is `auto`, so the track
   could not shrink below the min-content width of a `white-space: nowrap` button — 302px. `minmax(0, 1fr)`
   releases it.

Every public route now reflows at 320px with nothing clipped and no horizontal scroll: marketplace,
affordability, auctions, services, sign-in, register.

### 7.5 Two findings that were not real

Recorded because an audit that only lists confirmed hits gives no sense of its own false-positive rate.

- **`CompliancePage` has no `h1`** — it delegates to two views by permission and both render a `PageHeader`,
  which is an `h1`. The other nine files the same check flagged are tabs and modals, which correctly have none.
- **`--brand` on the ink surfaces fails** — nothing renders brand-coloured text on ink. See §3.3.

### 7.6 Already correct before this audit

Worth stating, because it is most of the criteria and it did not happen by accident: `lang` on the document;
`document.title` per route; a global `:focus-visible` ring; `prefers-reduced-motion` honoured across every
animation and transition; `alt` on every `<img>`, with decorative ones correctly empty; landmark elements in
all four layouts; `Escape` closing dialogs; `aria-current` on pagination; `aria-label` on the theme toggle,
sign-out, burger and dialog close; and `role="combobox"` / `role="listbox"` / `aria-activedescendant`
already wired on the custom select.

### 7.7 What is left, and it needs a person

- A session with an actual screen reader — NVDA or VoiceOver — on the workflows that matter: approving a
  mortgage, registering for an auction, completing KYC. Everything above proves a name *exists*; only this
  shows whether the order it is read in is one somebody can work in.
- A keyboard-only run by somebody who does not already know where the controls are.
- ~~The authenticated screens at 320px~~ — **done, §7.10.**
- ~~Heading hierarchy~~ — **done, see §7.8.** I recorded this as a judgement call not worth guessing at
  across 71 pages, and that was wrong: the skips came from two shared components, not from 71 bespoke
  decisions.

### 7.8 Heading hierarchy — where the skip actually came from

Every page went `h1` → `h3` with no `h2`. I first recorded this as a per-screen judgement call and left
it; re-examining it, the cause was almost entirely two shared components:

| Component | Was | Now | Instances |
|---|---|---|---|
| `SectionCard` | `h3` | `h2` | 27, across 17 files |
| `EmptyState` | `h3` | `h2` | 27 |

Both render a section that sits directly under the page's `h1`, so `h2` is not a preference — it is what
the level means. The rest was 28 headings whose container decided the answer, and the container is
readable from the source rather than guessable: a heading at page level under the `h1` becomes `h2`; one
inside a dialog, whose own title is an `h2`, becomes `h3`; one inside a section that just became `h2` also
becomes `h3` (`ProfileView`'s name block). Five `h4`s inside modals were skipping a level for the same
reason in reverse.

**The level changed and the appearance did not**, which is the part that needed care: all 22 of these
files style their headings by element selector — `.fgroup h3`, `.body h3`, a bare `h4 {` — so every tag
change was paired with its selector. Verified by computing the rendered styles afterwards rather than by
eye: the marketplace filter headings are still 10.5px/700/uppercase in Sora at `--text-subtle`, and the
card titles still 14px/600 at `--text`.

Result: every public route now reports **one `h1`, levels `h1`–`h2` only, and zero skips** — marketplace,
affordability, auctions, services, sign-in, register. The authenticated pages are changed by the same two
shared components but are not visually confirmed, for the same reason the rest of the authenticated walk
is not.

One thing found and deliberately not fixed: `TenantListView` carries a bare `h4 { … }` rule with no `h4`
in its template. It is identical at `HEAD`, so it is pre-existing dead CSS rather than fallout from this
change, and tidying it is not what this change is for.

### 7.9 Input purpose — a criterion I had left out

1.3.5 Identify Input Purpose is level AA and it was missing from §1's table: fields collecting a person's
own details have to declare what they are, which in practice means an `autocomplete` token from the
WCAG-enumerated list.

A scan of all 264 fields against that list returned 34 candidates, 19 of them with no token. **Applying
all 19 would have been a mistake**, and this is the part worth recording, because the criterion is
narrower than it first reads: 1.3.5 is about fields collecting information about **the user**. Most of
those 19 collect information about somebody or something else, and there autofill is not merely
unnecessary — it is harmful:

| Field | Whose details | Verdict |
|---|---|---|
| `UserFormModal` first/last/email/phone | a colleague the admin is creating | `autocomplete="off"` is **already right** — filling the operator's own name into another person's record is a data-integrity bug wearing an accessibility badge |
| `ValuerPanelView` first/last/email/phone | another person being added to a panel | out of scope, leave as is |
| `AuctioneersView` contact name/phone/email | another organisation's contact | out of scope |
| `AuctionLotsView`, `PropertyEditView` county/town/address | a *property's* address | out of scope — not a person at all |
| `RoutingRulesView` county, `TenantListView` country | a rule's criterion, an organisation's country | out of scope |

Four were genuinely in scope and are fixed:

- `MyAgentProfileView` — `fullName` → `name`, `phone` → `tel`. "My registration" is the agent's own.
- `AuctionCatalogueView` — `contactPhone` → `tel`. "Register to bid" is the buyer registering themselves.
- `VendorApplyView` — `businessName` → `organization`. The applicant's own business.

Every in-scope field now carries the right token, with **one deliberate exception**: the "Choose your
username" dialog keeps `autocomplete="off"`. There is no `new-username` counterpart to `new-password`, so
the only available token would make the browser suggest the username the person already has — for a field
whose own hint says it cannot be changed again. Declaring the purpose correctly and suggesting the wrong
value is a worse outcome than the technicality, so the technicality loses and it is recorded here instead.

### 7.10 The authenticated walk, without a session

The 33 workspace routes were the last gap, and they needed a signed-in browser I could not have: entering
a password is not something I do, whoever asks and however low the stakes.

Driven instead with **Playwright and no credentials at all**. The app follows its own hydration path —
`POST /auth/refresh` on boot — so intercepting `/api/v1/**` and answering that one call with a session
payload is enough for the router guard to admit the walk. The permission list in the payload is the real
one, all 132 codes read out of the `permissions` table, so the sidebar renders exactly what a super admin
sees rather than what I guessed they see. Everything is external to the app: route interception and a
stubbed response. **No test hook, no dev dependency, nothing added to the source** — the walk script lived
in the gitignored `.playwright-mcp/` directory and was deleted afterwards.

Two findings, both in a shared component, both affecting most of the platform:

1. **The list search box had no accessible name — on 28 of the 33 routes.** `TableToolbar` wraps its input
   in a `<label>` whose only contents are the magnifier and the spinner, and both are `aria-hidden`, so the
   label named nothing. The placeholder carried the words and a placeholder is not a label: it disappears
   the moment anything is typed, which is exactly when somebody re-reading the field needs to know what it
   is. The component already computed the right string — `Search users`, `Search listings` — for the
   placeholder; it just never reached the accessibility tree. One `aria-label`, 28 routes.

   This was the most repeated control on the platform, which made it the most repeated omission. It is
   also the one thing in this audit that the static passes could not have found: the name is absent only
   once the icon is hidden and the label resolves to nothing, and that is a fact about the rendered tree.

2. **19 data tables had `th` without `scope`.** `th` alone already satisfies 1.3.1 for a simple
   one-header-row grid — the association is inferable — so this was a best-practice gap rather than a
   failure. `scope="col"` on `DataTable`'s header cell states it instead of leaving it to be inferred, at
   a cost of one attribute in one component.

Re-run after both fixes, all 33 routes: **zero unnamed controls, `scope` on every header cell (4/4 through
8/8), one `h1` each, no skipped heading levels, no positive `tabindex`, the skip link resolving, and
nothing clipped at 320px.** The workspace reflows cleanly — which had been the open question, since it is
where the wide tables live.

**The limit of this method, stated plainly:** list endpoints were answered with empty pages, so what was
audited is the chrome, the filter row, the table head and the empty state — not tables carrying twenty
rows of real data. A populated table could still reflow differently at 320px. That check needs a real
session, and it is the one thing on this page that a stub cannot stand in for.

### 7.11 Checkboxes

The 24 checkboxes on the platform were the operating system's, leaning on `accent-color` — which paints
the *checked* fill and nothing else, leaving the empty box as whatever hairline the OS picked. On a light
card that hairline was near-invisible, and 1.4.11 asks 3:1 of a control whose only affordance is its
outline.

They are themed now, in the design system rather than in a component, because **there was no markup to
change**: `appearance: none` takes the painting away from the OS and leaves everything else — the input is
still an input, so it is focusable, space still toggles it, `:checked` still drives the label, and a screen
reader still calls it a checkbox and reads its state. A control hand-built from divs and click handlers has
to re-earn all of that, usually gets half, and there were 24 of them.

Five states, in both themes: resting (surface fill, `--border-control` at 3:1), checked (`--brand` fill
with the tick in white at 4.78:1), indeterminate (a dash, same fill), disabled, and disabled-but-checked —
still legibly *on*, just not yours to change.

The tick is the right and bottom edges of a small box, rotated 45°. It started as a clipped polygon, which
was legible and blunt — hard corners, both arms the same weight, reading as a chunk rather than a stroke.
The sister project `axis-f` uses Naive UI's stock checkbox, whose mark is a tapered stroke, and rotating two
borders gives that shape in four lines: two arms of unequal length, even weight, an elbow `border-radius`
can soften. Naive's own path as a `mask` data URI would have matched to the pixel and put 400 unreadable
characters in the middle of the design system for a shape that is adjustable this way.

The indeterminate dash has to un-rotate explicitly, since it inherits the tick's 45° and a diagonal dash
reads as a broken tick rather than a deliberate state. Six per-file rules setting
`accent-color` and their own 15/16/17px sizes were removed; the platform had three checkbox sizes.

Two things the browser corrected:

1. **The dark theme's control border was too blue.** `--border-control` had been solved as the
   minimum-lightness value that preserved the hue, which cleared 3:1 by lifting a fully saturated blue —
   and put a distinctly blue outline around every control on a teal-branded platform. Desaturating at the
   same lightness gives `#617e9c`, still 3.06:1 on the least forgiving dark surface, and lets the control's
   fill be the thing with colour in it.
2. **The global focus ring was reshaping the box.** `:focus-visible` sets `border-radius: var(--r-sm)` so
   the outline it draws is rounded; on a 17px checkbox that rounded the box itself from 5px to 8px as you
   tabbed onto it — the control changing shape under the cursor. Pinned.

Verified by keyboard rather than by inspection: the box is reachable by Tab, `:focus-visible` resolves, the
ring is 2px of `--brand-text` at 2px offset, and the radius holds at 5px.

One correction worth recording, because it nearly became a bug report: the first measurement said the
checked fill and the tick were not changing at all. They were — `getComputedStyle` was being read in the
same tick as the click, and a property mid-transition reports its *starting* value. The styles were right
and the measurement was wrong, which is its own lesson about verifying transitions.
