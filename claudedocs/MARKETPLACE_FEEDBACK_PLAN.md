# Marketplace feedback — seventeen items

Scoped 2026-09-16 against `hodimp-b` (Spring Boot 4.1 / Java 23 / Postgres) and `hodimp-f`
(Vue 3 + TS). Branch `feature/coop-bank` in both repositories.

This is a list of defects and gaps rather than a feature, so the plan is organised by the thing
being changed rather than by the order the items were written in — several of the seventeen turn
out to be the same underlying fault seen from two screens.

---

## What the survey found, before any of it is fixed

Four findings reframe the list, and they are worth stating first because they decide the shape of
the work.

**Media is two tables, and a listing generated from a development is in neither.** `property_media`
holds a listing's photographs; `media_assets` holds everything else (development, phase, unit type,
unit, progress post) and carries the `media_kind` discriminator — `PHOTO`, `FLOOR_PLAN`,
`SITE_PLAN`, `BROCHURE`, `DRONE`. A typology card and a generated unit are `properties` rows and so
read `property_media`, which nothing ever writes for them. Meanwhile `PropertyService.photographCount`
counts the *unit type's* media when deciding whether the card may publish. So a typology card passes
the "at least one photograph" gate on pictures the marketplace will never show it. That single
mismatch is items 1 and 4 together.

**Amenities have a typology axis that nothing writes.** `unit_features` has both `unit_id` and
`unit_type_id`, an XOR constraint, and a documented inheritance rule ("a unit with its own rows has
exactly those, otherwise it inherits its typology's"). `PropertyService.applyAmenities` is the only
writer in the codebase and it always writes `unit_id`. The typology half, and everything built on
it, is inert. There is nothing at development level at all. That is items 6 and 8.

**The bank became the platform, and the auction guard did not follow.** `AuctionService.create`
refuses any caller with neither `tenantId` nor `institutionId`, with the message *"A lot is brought
by the bank or the seller selling it."* Since the Co-op migrations, bank staff are `ACTOR_PLATFORM`
with both ids null — so the one population the message invites is the one it rejects. That is item 13.

**Editing one unit takes the whole project off the marketplace.** `DevelopmentUnitService.update`
calls `DevelopmentPublication.requireReapproval`, which moves the *development* to `PENDING`, bulk-
updates **every** non-sold sibling unit to `DRAFT`, moves every typology card to `DRAFT`, and raises
one development-level approval. That is item 12, and it is a data-visibility bug rather than a
cosmetic one: changing a price on unit 4B withdraws units 1A through 12C from sale.

---

## Work packages

### A — Media: kinds, and sharing between a unit type and its listing
*Items 1, 4; item 9's upload rides on the same change.*

The rule adopted: **a listing generated from a development does not own media — it reads its unit
type's.** Copying at generation time would satisfy "don't upload twice" once and then drift the
moment either side is edited; sharing satisfies "edit on either updates both" permanently.

1. Add `media_kind` to `property_media` (`PHOTO|FLOOR_PLAN|SITE_PLAN|BROCHURE|DRONE|CERTIFICATE`,
   default `PHOTO`), and add `CERTIFICATE` to the `media_assets` kind CHECK.
2. `PropertyMediaService` gains a delegation: when the property is a `TYPOLOGY` (or a `UNIT` with no
   media of its own), `list`/`add`/`makePrimary`/`remove` operate on `media_assets` under
   `UNIT_TYPE`/`unitTypeId` instead of `property_media`. One store, two doors.
3. `PublicPropertyService.toDetail` and `.response` gain the same fallback the publish gate already
   has, so the card renders what the gate counted. `primary_image_key` is resolved through the same
   path rather than left null.
4. `MediaStrip` learns `kind`, so a development captures *Photographs* and *Site plans* separately
   and a unit type captures *Photographs* and *Floor plans* separately. `PropertyEditView`'s
   hand-rolled uploader is replaced by two strips of the same component.
5. `DevelopmentUnitType.floorPlanKey` is a dead column exposed as `floorPlanUrl`; it is now populated
   from the first `FLOOR_PLAN` asset rather than left permanently null.

### B — Location is one thing, and county is a list
*Items 2, 11.*

`LocationPicker` deliberately refused to write county/town/estate, on the reasoning that geocoded
names would churn the search facets. The feedback overrides that: the seller should place a pin and
have the address follow. The churn objection is answered instead by **constraining county to the
forty-seven**, which is what makes the facet stable — a fixed vocabulary tolerates a geocoder in a
way free text never could.

1. `src/data/counties.ts` — the forty-seven, with `canonicalCounty()` healing spelling variants and
   `countyOptions(current)` preserving an unrecognised legacy value rather than dropping it.
2. `LocationPicker` gains optional `v-model:county`, `v-model:town`, `v-model:estate`,
   `v-model:address`, filled from the resolved place and still editable by hand.
3. Every county input becomes an `AppSelect` over that list: property, development, auction lot,
   routing rules, vendor directory, marketplace search.
4. Auction: `venue_latitude` / `venue_longitude` on `auction_lots`, a `LocationPicker` beside the
   venue field, and the venue shown on a map in the public catalogue. (The native date/time inputs
   in the same form are package F.)

### C — Purpose and price, said out loud
*Item 3.*

`Property.listingType` (`SALE|RENT`) exists, is CHECK-legal, and has **no control anywhere** — it is
hardcoded `'SALE'` in the form object. `Development.purpose` has a control but appears in no public
DTO and on no card.

1. A Sale/Rent control on the listing form; `listingType` in the marketplace query, the facets and
   the search filter row.
2. A purpose badge on the listing card, the listing detail, the admin list and the development card;
   `purpose` added to `PublicDevelopmentResponse`.
3. Price rendered against the purpose: a sale price as now, a rent price suffixed "a month", a
   typology as "From X", a development as "X – Y". One helper in `utils/format.ts` so the four views
   cannot drift.

### D — Amenities where they belong
*Items 6, 8.*

1. `unit_features.development_id`, and the XOR constraint relaxed to "exactly one of three".
2. `amenityCodes` on the development save request and response; an Amenities step in
   `DevelopmentEditView` using the same grouped tile grid the listing form already has.
3. `amenityCodes` on the unit-type save request and response — the first writer of
   `unit_type_id` — so the inheritance machinery in `UnitSpec.of` finally has input.
4. The unit-type picker offers the development's own amenities first, under *"From the project"*,
   then the rest of the catalogue under *"Just this type"*. That is the global-vs-specific
   distinction the feedback asks for, expressed as provenance rather than as a new column.

### E — Approvals: scope, and a clearer form
*Items 7, 12.*

1. `DevelopmentUnitService.update` raises a **`PROPERTY`/`PUBLISH` approval on the edited unit** and
   moves only that unit to `PENDING`. Siblings, typology cards and the development are untouched.
   Development-level edits keep the development-level behaviour, which is correct for them.
2. `PropertyService.update` switches `approvals.submit` → `submitOrRestate`, removing the 409
   *"That is already waiting for a decision."* on a second edit of a pending listing.
3. `PropertyEditView`: the wizard's submit reads **Update** when the listing exists (**Create
   listing** when it does not), and *Send for approval* moves out of the Photographs step into the
   page header, where it reads as an action on the listing rather than on the photographs.

### F — Native controls
*Items 5, 11.* **Done.**

`AppDateTimePicker` composes the existing `AppDatePicker` with a typed, list-assisted time box and
speaks the same `YYYY-MM-DDTHH:mm` string `datetime-local` did, so no call site's payload changed.
Six files: cost and drawdown modals (native `date`), interest panel, viewings, diary and auction
lots (native `datetime-local`).

### G — Certification and energy rating
*Items 9, 10.*

1. Certification gains an evidence upload — `CERTIFICATE`-kind media on the listing, and on the
   development, which had no certification concept at all (`greenCertified` / `greenCertification`
   added to `Development`).
2. `EnergyRatingSlider` — an A–G band slider, green through amber to red, replacing the unvalidated
   8-character text box. Stored in the existing `energy_rating` column as the letter; a legacy value
   that is not a band is shown as-is with the slider unset rather than silently overwritten.

### H — Sidebar
*Item 14.* Declared group order instead of first-seen order (today, hiding one item by permission can
reorder the headings), and a regrouping: Overview · Marketplace · Leads · Money · Partners · Access ·
Platform. `seller-applications`, reachable only by URL today, gets an entry.

### I — Registration
*Item 15.* Three disjoint entry points today (`/register`, `/sell-with-us`, `/become-an-agent`) with
no relation between them. One screen with **Buyer** and **Seller** tabs; the seller tab asks what
kind — agent, owner, organisation — and that answer selects the KYB checklist (which already exists,
seeded per seller type in `kyc_requirement_configs`) rather than making the applicant discover it two
screens later. `sellerType` also gains the server-side validation it lacks, closing the hole where a
typo yields an empty checklist and therefore a submittable application with no evidence at all.

### J — Conversations
*Items 16, 17.*

1. **Admin inbox.** `EnquiryService.reply` sets `awaitingSeller = false`; the inbox immediately
   re-queries with `awaiting=true`; the row vanishes. Fixed by updating the replied row in place
   instead of re-querying, and by `close()` returning the thread (it returns `messages: null` today,
   which blanks the pane on close).
2. **Customer side.** The thread is fetched only on Open and shown nowhere else. The list gains the
   last message as a preview and an unread marker; the deep link gains the fallback fetch the admin
   view already has; `LeadNotifier`'s buyer link is corrected from `/account/enquiries` to the route
   that exists, `/account/conversations`.
3. **Viewings and offers keep no history at all** — `sellerNote`, `decisionNote` and `outcomeNote`
   are single columns each decision overwrites. A `lead_messages` thread is added, written on every
   decision, and rendered on both sides.

---

## Order of work

Schema first, in one migration, because six packages need columns. Then backend services, then the
shared frontend components (`MediaStrip` kinds, `LocationPicker` address, `EnergyRatingSlider`,
county select), then the screens, which are mostly assembly once those exist.

Verification at each stage is `mvn compile` for the backend and `npm run build` (which runs
`vue-tsc --noEmit` and a bundle assertion) for the frontend.

---

## What was built, and what it was verified against

All seventeen are done. `mvn compile` and `npm run build` both pass; the backend was booted against the
local database, both migrations applied, and the changed public endpoints were read back.

Two migrations rather than one: `V20260916090000` had already been applied when the saved-search column
turned out to be needed, and Flyway checksums what it has run — so editing the applied file would have
turned the next boot into a validation failure. `V20260916100000` carries it instead.

### Proven against the running application

- **Item 4.** Listing `PR260915CB7H` is a typology card. `GET /public/properties/PR260915CB7H` now returns
  a `primaryImageUrl` and three `imageUrls`, all resolved from its unit type's gallery. Before the change
  both were empty, while the publish gate counted those same files — which is precisely how a card went
  live blank.
- **Item 3.** `GET /public/properties/facets` now returns `listingTypes`; `GET /public/developments/DV260827DEMO`
  now returns `purpose`.
- **Item 1.** The same development returns a populated `floorPlanUrl` per typology.

### Item 13, verified by reading rather than by calling

The local bootstrap password has been rotated, so the fix was confirmed from the code: `BANK_ADMIN` and
`SUPER_ADMIN` are both `ACTOR_PLATFORM` (`UserTypeEnum`), `isPlatformStaff()` tests exactly that, the
create guard now admits it, and `isMine` gives platform staff the lots carrying neither owner id — so the
bank can create a lot and then still edit it.

### Three things found while doing this, fixed because they were in the way

- **`LoginView` never read `route.query.redirect`.** The router guard has always written it. Every deep
  link on the platform — a notification, a pasted URL, the seller applicant being sent on to finish —
  deposited people on their profile after signing in. Fixed, with a same-origin check on the value, and it
  is what lets the seller type reach the onboarding screen by query rather than through local storage.
- **A second edit of a pending listing was a 409.** `PropertyService.update` called `approvals.submit`
  where every other re-approval path calls `submitOrRestate`, so the author of a listing awaiting a
  decision could not save a correction, and the message talked about a queue rather than their listing.
- **The approvals queue printed `CATALOGUE_ITEM`.** Its label map said `CATALOGUE`; the server emits
  `CATALOGUE_ITEM`, so every vendor row fell through to the raw code.

### Where the first attempt fell short

**Item 16, customer side, was half-built and is now finished.** The preview on the conversation list read
from threads fetched earlier in the same session, so on first load every row was blank — which is the
complaint restated, not answered. `EnquiryResponse` now carries `lastMessage` on the list endpoints too,
filled by one batched query per page (`EnquiryMessageRepository.latestForTickets`), and the admin inbox —
which had no message text on its rows either — uses it as well.

### Deliberately not done

- **`LocationPicker` still does not overwrite an address field that already has something in it.** The
  feedback asks for the address and the map to be one thing, and they now are — a resolved place fills the
  four fields. Overwriting what a seller typed was not included: Google's name for an estate is regularly
  not the one on the gate, and a search to nudge a pin two streets would otherwise silently rewrite it.
- **Rent is stored with a period (`DAY`/`WEEK`/`MONTH`/`YEAR`) and refused without one.** Defaulting a
  letting to monthly would publish a term nobody agreed.
- **A legacy energy rating that is not an A–G band is shown as it stands**, with the slider unset, rather
  than snapped to the nearest letter or silently cleared.

### Partial, and worth a decision

- **Item 2.** The map now fills county, town, estate and the address line, but they remain four fields
  rather than one merged control, and a resolved place never overwrites a field a seller has already
  written in. If "the same thing" meant a single address control with the map as its only input, this is
  not that.
- **Item 15.** The front door is unified and the seller type is carried across, so nobody answers it
  twice — but a seller still registers, signs in, and then completes a separate four-step application.
  The steps were signposted, not removed. And the KYB checklist shown at registration is a hardcoded
  mirror of the `kyc_requirement_configs` seed, because the endpoint that reads it is scoped to a
  signed-in applicant; it will drift if the requirements change.
- **Item 17.** Viewings and offers keep a real history now, and the existing notes were backfilled into
  it — but `sellerNote`, `outcomeNote` and `decisionNote` remain as "latest word" columns beside the
  thread, because the notification lines, the calendar projection and the seller tables all read them.

### Not verified

No UI was exercised in a browser, and no tests were written. Item 13 was confirmed by reading the code
rather than by calling the endpoint, because the local bootstrap password has been rotated.

---

## Follow-up (17 September 2026): the sharing was half-built

Reported after using the screens: "images are not being shared from listing", and "edits made on the
listing are not reflected [on the development] and vice versa".

Both were real. A probe test — `TypologyAndListingAreOneIT` — was written first, stating the three
behaviours as assertions. One passed and two failed, which located the work exactly.

### What was already right

The gallery itself. `PropertyMediaService` does delegate a typology card's `list`/`add`/`makePrimary`/
`remove` to `media_assets` under `UNIT_TYPE`, and the public card resolves its pictures through the same
ladder. `GET /public/properties/PR260915CB7H` returns three images from unit type 195's gallery.

### What was wrong — the pictures, from inside the workspace

One store, two doors, and **only one door knew about the card's cover cache**:

- `PropertyService.toResponse` — the DTO behind the listings list and the listing editor — read
  `storage.urlFor(p.getPrimaryImageKey())` with no fallback, and counted `property_media`, which is empty
  for every generated listing by design. So a typology card rendered coverless and "0 photographs" beside
  a development screen showing four. The column was in fact null on every typology card in the database.
- `DevelopmentMediaService.refreshCover` wrote the new cover onto `development_unit_types` and stopped,
  while `PropertyMediaService.refreshSharedCover` also repointed the listings. An upload through the
  development screen therefore left the card's cached key stale or null; the same upload through the
  listing form did not.

Fixed both ways round: the workspace DTO resolves the cover and the count through the shared gallery
(the same ladder the public card uses, so the two answer identically), and the development door repoints
every listing of that typology exactly as the listing door does. Existing rows need no backfill — the
read resolves them.

### What was wrong — the facts

`cardFor` built the card from the typology once, at listing time, and nothing updated it afterwards.
`mirrorToListing` carried availability and the "from" price and nothing else. Nothing at all travelled the
other way. So a two-bed corrected to a three-bed in the project screens went on advertising two bedrooms,
and a price fixed on the card was not the project's price.

`TypologyListingMirror` holds the invariant in both directions:

- **What travels**: property type, bedrooms, bathrooms, parking, floor area, service charge, description,
  price and amenities.
- **What does not**: the place (the development's, and moving the project is what moves its cards);
  availability and the "from" price (`DevelopmentInventoryService` stays their single writer); and a card
  title somebody wrote themselves — the generated form "Two bedroom at Highrise Apartments" is recognised
  and regenerated on a rename, anything else is left alone.
- **Re-approval travels with it.** An edit arriving through the listing form moves the same figures the
  bank approved on the project, so it calls `requireReapproval` exactly as the typology's own editor does.
  Otherwise the listing screen would have been a way round the project's gate.
- **No loop**: both directions write through repositories, not through the two services that call them.

### Verified

`TypologyAndListingAreOneIT` — six tests: the shared gallery, the resolved cover and count, the typology's
edit reaching the card, a hand-written title surviving a rename, the card's edit reaching the typology, and
an ordinary house being untouched by all of it. Whole suite: 285 tests, green.

### Also, on the same report: the blue focus ring on the map

`LocationPicker`'s search box is Google's `gmp-place-autocomplete`, whose input lives in a shadow tree.
`outline` is not inherited and a scoped stylesheet cannot cross that boundary, so the platform's focus ring
never reached the one control that needed it and Chrome drew its own blue. The rule is now adopted into the
shadow root itself, and the map canvas — which is ordinary light DOM — has its ring replaced rather than
removed, because it is a real tab stop. Both are defensive: a closed shadow root leaves the field working
and merely blue.

**Unverified in the browser.** The picker only appears on three signed-in screens and the local bootstrap
password has been rotated, so this one needs the reporter's eyes.
