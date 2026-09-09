# Units as properties: one source for selling and for money

**The ask (9 September 2026):** a development that is tracked, and even sold before completion, should have
every generated unit created in `properties`, so that selling a home and tracking its payments happen from one
source rather than two.

## 1. What we have — and why it is two sources today

| | Ordinary house | Development unit |
|---|---|---|
| The record | one `properties` row | one `development_units` row; the *typology* has a `properties` row, the unit does not |
| Marketplace | its own listing | one listing per kind of home ("the 2-beds at Highrise, 60 of 70 left"); the unit is a drill-down page read from the development, not from `properties` |
| Enquiry, viewing, offer | attach to the property | attach to the **typology's** property — an offer says "a 2-bed", never "B-14" |
| Reserve, agree, complete | none: `markSold` flips the listing | `unit_bookings` on the unit, with a schedule and a balance |
| Payments | **none** — a house sale has nowhere to record money | against the booking |
| Direct sale without terms | `markSold` | `DevelopmentUnitService.reserve/sell` beside the booking path |

So the split runs both ways. A unit has money but no listing; a house has a listing but no money. Ten tables
already reference `properties(id)` (leads, valuations, media, promotions, commissions, saved searches…), which
is exactly why the typology got a row in the first place — and why the unit should have one too.

The one-listing-per-typology decision (commit `632997b`) was about **what search shows**: an estate of 125
bungalows is four results, not 125. That stays true and is not in tension with this ask. A row can exist in
`properties` without appearing in the result list; the typology rows were hidden that way for a week before
they were shown.

## 2. What "one source" means concretely

Every generated unit is a `properties` row, and a sale — of a unit or of a house — is a booking on a property,
with its payments against that booking.

- `properties` gains `unit_id` and a `listing_kind` of `HOUSE`, `TYPOLOGY` or `UNIT`. `UNIT` rows carry the
  unit's label, block, floor, own price (else the typology's), construction status and sale state as cached
  columns, written by the one writer, `DevelopmentInventoryService`.
- Marketplace search shows `HOUSE` and `TYPOLOGY`; `UNIT` rows are the drill-down under a typology, as the
  unit pages are today — but now read from `properties`, so an enquiry, viewing or offer can name **B-14**.
- `unit_bookings` becomes a booking on a property: gains `property_id` (backfilled, then NOT NULL); `unit_id`
  stays for unit rows and is null for a house. `markSold` on a house becomes "complete a booking", and a house
  gets a schedule and payments like a unit does. Payments already hang off the booking, so nothing moves there.
- The unit's `sale_state` and the property's state are one fact with two mirrors. `properties.listing_state`
  keeps its five values; the unit row additionally caches `sale_state` (AVAILABLE, HELD, RESERVED, SOLD,
  RETAINED). The public unit page and the inventory screen both read it from `properties`.
- `DevelopmentUnitService.reserve/sell` stop being a second way to sell. "Record a sale made off the platform"
  stays as a button, but it creates a completed booking with the price and the buyer, so the money has
  somewhere to land later.

## 3. Options

**A. One property per unit, bookings on properties (recommended).** The shape above. One source, one sale
path, leads on a specific home, houses get payments. Cost: two migrations with backfills, the inventory writer
mirrors units as well as typologies, the booking service and its four screens learn about properties, the
marketplace unit pages switch their read, and about fourteen backend classes that key on `unitTypeId` are
touched. A week of work, most of it careful rather than hard.

**B. Bookings on properties only.** Houses get bookings and payments, but units still have no row in
`properties`. Halves the ask; leads still cannot name a unit. Not recommended: it is the smaller half.

**C. Everything becomes a unit.** Make every house a one-unit development. Reverses the marketplace model
and every listing screen for the sake of symmetry. Not recommended.

## 4. Sequence for A

1. **Schema.** `properties.unit_id`, `listing_kind`, the unit caches; `uk_property_unit` (one live row per
   unit) beside `uk_property_unit_type`; `unit_bookings.property_id`. Backfill: a `UNIT` row for every existing
   unit (from its typology's listing: type, tenure, location, description), `listing_kind` on every existing row,
   `property_id` on every booking. Flyway, in one transaction.
2. **One writer.** `DevelopmentInventoryService.mirrorUnit(unit)` creates or updates the unit row on generate,
   create, update, reserve, sell, release, build-status and archive; `mirrorToListing` keeps the typology row.
   Nothing else writes a `UNIT` row; `PropertyService.update` refuses one except for photographs and blurb.
3. **Bookings on properties.** `BookingService.create(propertyId, …)`: a unit row resolves its unit and holds the
   existing partial-unique guard; a house row has no unit and guards on `property_id` instead. `markSold` on a
   house creates and completes a booking. The booking drawer opens from a listing as well as from the unit
   inventory. Payments unchanged.
4. **Leads name the home.** Enquiry, viewing and offer forms on the public unit page post the unit's property.
   The seller's lead screens show the unit label where there is one.
5. **Marketplace.** The typology page lists its units from `properties`; the unit page reads a property. Search
   predicate: `listing_kind <> 'UNIT'`. Facets unchanged.
6. **Retire the second path.** `reserve` becomes "create a booking"; `sell` becomes "record an off-platform
   sale" that completes a booking. The unit inventory screen loses its two ad-hoc buttons.
7. **Analytics.** `contracted`, `collected` and receivables now cover houses too; the developments comparison
   is unchanged. One test each way: a house sale with two payments appears in the dashboard's month.

## 5. Decisions that are yours

1. **Search.** Keep unit rows out of the result list and reach them through the typology (recommended, keeps
   `632997b`), or show them?
2. **Houses.** Give ordinary houses the same booking-and-payments path (recommended — it is the point of
   "one source"), or leave `markSold` as it is?
3. **Off-platform sales.** Keep a button for a sale done outside the platform, recording it as a completed
   booking (recommended), or insist every sale goes through reserve → agree → complete?
4. **Price on a unit row.** The unit's own price, else its typology's — as the unit page already does. Confirm.
5. **Existing rows.** The two developments in the dump get their unit rows created by the backfill, with the
   typology's photographs. Confirm nothing about them should be different.

## 6. Out of scope

- Splitting a typology listing into per-unit marketing copy. A unit row's blurb is its typology's unless
  somebody edits it.
- Reworking the payment types module. Accounts are per organisation or per development, and a house's
  payments land in the organisation's accounts as a unit's do.

## 7. Decided (9 September 2026)

1–3 as recommended; 4 confirmed. On 5 the answer changed the shape: **the property row is the unit.** Not a
mirror kept in step with `development_units` — a unit sold is one row updated, a unit completed is one row
updated, and the development reads its inventory from `properties`. A development is a *project* grouping of
property rows; its typologies are the *categories* within it.

What that means for §2 and §4:

- `development_units` is retired. Its columns move onto `properties` (label, block, floor, door, phase, pay
  reference, sale state, completion and handover dates, buyer, hold, sold price, own bedrooms/bathrooms/areas/
  aspect, notes). `listing_kind` says which of `HOUSE`, `TYPOLOGY`, `UNIT` a row is. `tenant_id` and `price`
  become nullable for a `UNIT` only: a bank's project may have no selling organisation yet, and a unit's price
  is its typology's until it has one of its own (`institution_id` is added so the owner is still on the row).
- Every foreign key that pointed at a unit now points at a property: `unit_bookings.property_id`,
  `payments.property_id`, `unit_features.property_id`, `listing_progress_updates.property_id`, and the media
  owner ids. The backfill creates one `UNIT` row per existing unit and rewrites those keys in the same
  transaction, then drops the old table.
- `DevelopmentUnitService`, the generator, bookings, payments, the IPN matcher, progress posts and unit media
  keep their endpoints and DTOs; underneath they read and write `Property` rows of kind `UNIT` through a
  repository scoped to that kind. The frontend is unchanged by this step.
- A unit row's `listing_state` follows its development (LIVE when the project is live, SOLD when the unit
  sells, WITHDRAWN when the project is taken down), written by the inventory service when either changes —
  so `markSold`, search and the reports read one column for a house and a unit alike.
- Marketplace search and the seller's listing list exclude `UNIT` rows; the typology row remains the card and
  the units its drill-down. `PropertyService` refuses to edit, submit, withdraw or sell a `UNIT` row directly:
  those go through the development and the booking.

Sequence: (1) this migration and the entity merge, everything compiling and the suite green; (2) bookings on a
property, so a house is booked and paid like a unit; (3) leads on a unit; (4) marketplace unit pages read
`properties`; (5) retire the direct reserve/sell buttons in favour of "record an off-platform sale".

## 8. Stage 1 delivered (9 September 2026)

`V20260909090000__units_are_properties.sql`: `listing_kind` on `properties`; the unit columns; `tenant_id`
and `price` nullable for a UNIT with `institution_id` added; one live row per unit label and per pay
reference; the typology uniqueness narrowed to TYPOLOGY rows; a UNIT row backfilled per unit (34 in the
development database); `unit_bookings.property_id` and `payments.property_id` replacing `unit_id`;
`unit_features.unit_id`, `listing_progress_updates.unit_id` and media owner ids rewritten to property ids;
`v_booking_balances`, `v_development_finance` and `v_chart_dev_funding` recreated over `property_id`;
`development_units` dropped.

`DevelopmentUnit` is gone; `Property` carries its fields and its questions (`isAvailable`, `isSoldUnit`,
`isOnHold`, `effectivePrice`). `DevelopmentUnitRepository` is a second repository over `Property`, every
query narrowed to kind UNIT. `UnitBooking.propertyId` (development optional), `Payment.propertyId`. The unit
service stamps a generated row with what a listing carries — title, type, owner, place, the project's listing
state. `DevelopmentInventoryService.syncUnitRows` copies a project's state, name and place onto its unit rows
after every development save; `applyListingState` makes a sold unit read SOLD and every other unit read what
its project reads, in the same transaction as the sale. Search, facets and the seller's listing list exclude
UNIT rows; `PropertyService` refuses to edit, submit, withdraw, sell or archive one. Endpoints and DTOs are
unchanged, so the frontend is untouched by this stage. Suite: 203 tests green.

## 9. Stage 2 delivered (9 September 2026): a house is booked and paid for like a unit

`V20260909110000__house_bookings.sql`: `payments.development_id` nullable; a HOUSE row may carry a sale
state. `BookingAccess` says who may see and who may sell a home — a unit through its development's rule, a
house through its seller's tenant scope. `BookingService` books a property of either kind: bookings are
addressed by their own id (`/api/v1/bookings/{id}/…`) and created against a home (`/api/v1/properties/{id}/
bookings`); the development-scoped routes remain for the inventory screen. A house under a live booking stays
on the marketplace with its sale state HELD or RESERVED; completing the booking writes SOLD in both columns,
copies the buyer and price onto the row, and raises the commission — which `PropertyService.markSold` no
longer does itself: marking a house sold now writes a completed booking (or completes the live one, balance
checked), with a buyer, a price and a place for late money to land. `PaymentService` receives and voids money
on a house booking; the account must belong to the seller and reach the whole organisation. `BookingResponse`
carries `propertyId`, `propertyTitle` and `listingKind`. Tests: `HouseBookingIT` (book, pay, complete; mark
sold is a booking; cancel frees; other sellers not found).

Frontend: `bookingApi` addresses bookings by id and books a property; `BookingDrawer` no longer needs a
development; `BookHomeModal` and `RecordSaleModal`; the listing page gains a Bookings panel and its "Mark sold"
action becomes "Record a sale" or "Complete the sale".

Still to do from §7: leads naming a unit (stage 3), the marketplace unit pages reading `properties` (stage 4),
and retiring the inventory's direct reserve/sell buttons in favour of "record an off-platform sale" (stage 5).

## 10. Stage 5 delivered (9 September 2026): one path to SOLD

The inventory's "Mark sold" is now "Record a sale", and `DevelopmentUnitService.sell` no longer flips the
row: it calls `BookingService.recordSale`, which writes a completed booking (or completes the live one, balance
checked). A unit's hold without money stays as it was — a name taken over the phone is not a sale. So every
home, house or unit, becomes SOLD by exactly one route, and every sale is a booking the dashboard can add up.

Remaining from §7: leads naming a unit (stage 3) and the marketplace unit pages reading `properties` (stage 4).
