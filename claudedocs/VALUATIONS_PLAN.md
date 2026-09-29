# Valuations: what is there, what is not, and what to finish

Written 29 September 2026 from a review of both repos. The module was built in the Quantumnex plan (§3.5, §15)
and has not been touched since; this is what a second look finds.

## 1. What the flow does today

A seller or the bank raises a job on a live listing (`VALUATIONS_REQUEST`); the platform assigns it
(`VALUATIONS_ASSIGN`) by round robin or by name, refusing a valuer whose indemnity cover is below the price,
lapsed, or off the panel; the valuer accepts, hands back or reports (`VALUATIONS_WORK`); the report — market
value, forced-sale value, method, inspection date — is written once and completes the job. Visibility: the
platform sees all, a seller its own, the bank its institution's, a valuer only what is assigned to them.
Panel: onboard, suspend, restore. One report (`VALUATIONS`, under Compliance).

## 2. What is wrong or missing

### Bugs
- **A valuer's completed count never rises.** `submitReport` releases the valuer before it sets
  `completedAt`, and the release counts a completion only when `completedAt` is set
  (`ValuationService.java:367-369`, `459-467`). The panel's "N done" is always 0.
- **Super Admin is offered the valuer's and the requester's actions and then refused.** `VALUATION_WORK`
  admits SUPER_ADMIN and SUPPORT_ADMIN (`AppModuleEnum.java:243-246`), and the seeder grants an admin every
  permission its modules admit, so the screen offers "Take it on", "Submit a report", "Request a valuation"
  and the service says no. The screen decides actions from permission plus state; the server sends no
  per-row flags.
- **Two assigns can race** — `assign` checks `isUnassigned()` with no lock; two staff assigning at once both
  pass and two valuers' counts go up.
- **A hand-back's reason outlives the hand-back**: `declinedReason` is not cleared on reassign, so "Handed
  back: …" shows on assigned and completed jobs; each hand-back overwrites the last, so there is no history.
- Counties are stored as typed on onboarding and upper-cased on update; indemnity cover is required on the
  form but not on the server.

### States nobody can reach
`DECLINED` and `SUBMITTED` are allowed by the CHECK and named in the constants and the frontend labels, but
no code writes them: a hand-back goes straight back to `REQUESTED`, a report goes straight to `COMPLETED`.

### Not built
- **Nothing consumes the figure.** No link from a valuation to a booking, an offer, a mortgage product or an
  affordability check; affordability caps the loan against the *price*, never the forced-sale value the
  plan says "a lender actually lends against".
- **No notifications at all**: not the valuer on assignment or cancellation, not the requester on accept,
  report or cancel, not the platform on a hand-back, nobody on cover or registration expiring or a due
  date passing. The onboarding password is shown on screen and never sent.
- **No audit** on raise, accept, hand back, cancel, valuer update, suspend or restore (only assign, report
  and onboard are recorded). **No maker-checker anywhere** — the figure the bank would lend against is final
  the moment one valuer submits it; cover amounts and manual assignments need no second person.
- **No signed report**: `document_reference` is a free string, never validated against the vault, no upload,
  never shown.
- **No way to renew a valuer**: `POST /valuers/{ref}/update` has no screen, so once cover or registration
  lapses the valuer cannot be assigned until somebody edits the database. `GET /valuers/me`,
  `GET /valuations/{ref}` and the unassigned count are likewise unreached.
- **No tests** for any of it.
- Calendar: `VALUATION` is a permitted diary source and nothing writes an inspection appointment (deferred
  in the Quantumnex plan).

### Rough on screen
- Hand back uses `window.prompt`; cancel fires with no confirmation and no reason; `cancelledReason`,
  `insuranceValue` and `assumptions` are never shown; no insurance-value input on the report form.
- The request form takes a typed listing reference — no picker, no way in from a listing's own page, no
  fee currency.
- The assign modal loads only the first hundred valuers, lets an unavailable one be picked, and is empty for
  bank staff (the panel fetch needs `VALUER_PANEL_VIEW`, which they do not hold).
- Filters: only state, and the list opens on "Everything" even for the platform's queue owner. The server
  supports purpose and listing filters that are never sent; the panel's county filter is sent and ignored.
- Cards with "load more", where every sibling list is a table with a pager; no detail view; the title links
  to the buyer-facing marketplace page.
- Panel: no edit, no detail, suspend without a note, registration expiry not shown, "lapsing soon" a
  client-side 60 days, a valuer sees the admin table with the admin's wording.
- The report omits purpose, requester, fee, due date, both values and the variance against asking; it is not
  owner-scoped and has no `institution_id`.

## 3. The design for finishing it

### 3.1 The figure reaches the bank
A valuation is *for* something. `valuation_requests` gains an optional `booking_id` and `offer_id` (the sale
it was raised against) and the request form is reached from a booking or an offer as well as from the list.
Affordability and the mortgage LTV take `min(price, forced_sale_value)` where a completed valuation exists
for the property, and say which they used. The booking and offer pages show the latest valuation's figures.

### 3.2 A report is checked before it counts
Submitting a report writes `SUBMITTED`; the platform's `VALUATIONS_ASSIGN` holder (or a new
`VALUATIONS_REVIEW`) approves it to `COMPLETED` or sends it back to `IN_PROGRESS` with a reason — through the
existing approval engine (`APPROVAL_ENTITY_VALUATION`), so the checker sees the figures and the valuer's
assumptions in the change set. Only a `COMPLETED` report feeds §3.1. Manual assignment and cover edits stay
single-person (they move no money), but every one is audited.

### 3.3 Hand-backs are history
`DECLINED` is written on hand-back and kept as a row in a small `valuation_events` table (state, who, when,
reason) so a job that was handed back twice says so; reassignment clears the current reason. The events feed
the notifications and the detail view's timeline.

### 3.4 Everybody is told
A `ValuationNotifier` on the leads pattern: the valuer on assignment, cancellation and send-back; the
platform on raise and hand-back; the requester on accept, report approved and cancel; the valuer and the
platform thirty days before cover or registration lapses, and when a due date passes. Onboarding emails the
temporary password. The platform's nav badge shows the unassigned count.

### 3.5 The signed report
The report form uploads the PDF through the vault (`DocumentService`), stores its reference, and the detail
view offers it; the requester and the bank may read it, a valuer only their own.

### 3.6 The screens
A table with a pager and filters (state, purpose, requester, valuer, due), opening on the queue for the
platform; a detail page with the timeline, the figures, the assumptions and the report; reason modals for
hand-back and cancel; a listing picker and a "Request a valuation" way in from a booking or offer. The panel
gains edit/renew, a detail view, a suspend note, registration expiry, an "available now" filter, and a
valuer's own standing page (`/app/my-valuer-profile`, from `valuers/me`). Per-row action flags from the
server, as beneficiaries do, so the screen and the service agree about who may do what.

### 3.7 Housekeeping
Fix the completed count; lock the row on assign (`@Version`); remove SUPER_ADMIN and SUPPORT_ADMIN from
`VALUATION_WORK` (they assign and review, they do not value); enforce cover on the server; normalise
counties once; widen `v_report_valuations` (purpose, requester, fee, due, both values, variance,
`institution_id`, owner-scoped) and add a panel report (cover expiries, load, availability).

## 4. Build order

1. **Housekeeping and tests** — §3.7 fixes; per-row action flags; `ValuationFlowIT` covering raise, both
   assignment paths and the three refusals, accept, hand back, report, cancel, scope, onboarding, suspend
   and restore. Ships alone and makes everything after it safe to change.
2. **Screens** — §3.6, on the fixed model, with the panel's edit/renew first (it unblocks assignment today).
3. **Review and history** — §3.2 and §3.3, with the approval handler and the events table.
4. **Notifications and the signed report** — §3.4 and §3.5.
5. **The figure reaches the bank** — §3.1, with the affordability change and its tests.

## 5. To confirm before phase 3

- Whether a submitted report is checked by a second person before it counts (§3.2), and by whom — the
  platform's assigner, or a reviewer role of its own.
- Whether affordability should lend against the forced-sale value or the market value when a valuation
  exists (§3.1). The plan text says forced-sale; the bank may say otherwise.
- Whether the bank's staff (now platform actors) raise valuations as the bank, as a seller does, or whether
  raising becomes the platform's alone under the Co-op-as-platform plan's stage 2.

## 6. Progress

(none yet)
