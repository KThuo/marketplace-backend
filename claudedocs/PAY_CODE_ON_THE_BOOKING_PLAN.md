# The pay code belongs to the booking

**Date:** 21 September 2026 · **Branch:** `feature/coop-bank` (backend and frontend)

## What is wrong

The four-character pay code (`properties.pay_reference`, alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`,
32⁴ ≈ 1,048,576 codes) was designed when money was received against a **unit**. It is allocated once when a
unit is created (`DevelopmentUnitService` via `PayCodeAllocator`), never changes, and is unique across every
unit ever. Money is now received against a **booking**: every payment, prompt, schedule and balance hangs off
`unit_bookings`, and a unit can be booked, cancelled and booked again by somebody else.

That leaves the code pointing at the wrong thing:

- The code names the unit, so the matcher has to take a second step (`PayeeResolver.onHome` →
  `findLiveForUnit`) to find the booking, and a code quoted after a cancellation and a re-booking lands on the
  **new** buyer's booking with no way to tell.
- A payer is told the unit's code, but what they are paying is a booking with its own buyer, schedule and
  amount due. The biller validation answers with the booking's buyer name and amount due for a code that does
  not belong to that booking.
- STK push does not use the code at all: the customer sees the intent reference (`IN…`), a twelve-character
  string, on their phone and statement.
- Houses (`listing_kind = 'HOUSE'`) never get a code, so a booking on a house has nothing short to quote.

## What changes

One code per booking, allocated when the booking is made, unique across every booking ever. It is **the**
payment reference: what the buyer quotes at the bank (biller), what the matcher reads off an incoming credit
(IPN, biller advice, CSV upload), and what the STK prompt carries as its customer-facing reference.

### Why unique across all bookings, not only live ones

"One unique code per active booking" is guaranteed either way, since a property has at most one live booking
(`uk_booking_live_property`). Uniqueness across **every** booking ever is chosen because a payment can arrive
weeks after a booking is cancelled. If the code were recycled, that late payment would be credited to the
next buyer. With the code kept, the resolver finds the cancelled booking and answers "Booking BK… is
cancelled, so there is nothing to credit", and the credit goes to the queue for a person to decide. The
space is 1,048,576 codes against a booking count that will stay in the thousands; `PayCodeAllocator` already
retries 25 times against the database and the unique index is the last word.

### Backend

**Step 0 — the column moves.** Migration `V20260921120000__the_pay_code_belongs_to_the_booking.sql`:
- `unit_bookings.pay_reference VARCHAR(8)`; `CREATE UNIQUE INDEX uk_booking_pay_reference ON unit_bookings
  (pay_reference)`.
- Backfill: every **live** booking (`RESERVED`, `AGREED`, `status <> 5`) inherits its unit's existing code, so
  a buyer who has already been told a code keeps it. Unit codes are unique and a unit has one live booking,
  so inherited codes cannot collide. Every other booking (completed, cancelled, lapsed) gets a fresh code
  from the same alphabet, generated in SQL and checked against the codes already taken. Then `NOT NULL`.
- `properties.pay_reference` and `uk_property_pay_reference` are dropped. One source of truth; the unit
  inventory shows the live booking's code instead (below).
- `UnitBooking.payReference`; `UnitBookingRepository.existsByPayReference`, `findByPayReference`.
- `PayCodeAllocator` moves to `modules/bookings` and checks bookings, not units. `DevelopmentUnitService`
  stops allocating; `Property.payReference`, `existsByPayReference`/`findByPayReference` on the unit
  repository and the unit search clause go.
- `BookingService.createForProperty` (the one path every booking takes, including offer → booking) allocates
  the code inside the same transaction as the insert, so a booking never exists without one.

**Step 1 — the readers.**
- `PayeeResolver`: booking reference → **booking pay code** → listing reference. The unit code path goes.
  The listing-reference attempt stays ahead of the code only in the sense that a twelve-character string is
  tried as a reference before its last four characters are tried as a code (exactly as today, to avoid a
  wrong-thing tail match). `Resolution.needsCorroboration()` stays true for a bare code.
- `CoopIpnService.placeAutomatically`: unchanged in shape. A bare code still needs the amount or the phone to
  agree before auto-crediting a free-text bank transfer, because the code carries no checksum.
- `CoopBillerService`: validation resolves the code to the booking directly and answers with that booking's
  buyer and amount due. The **advice** that follows a successful validation is placed on the code alone: the
  bank confirmed the code with the payer moments earlier and showed them the buyer's name, which is stronger
  corroboration than an amount match. Implemented as a `trusted` flag on the placing call, set only from the
  biller advice path.
- `CoopStkService.push`: the narration carries the booking's pay code (`"Payment <code> for BK…"`), so the
  customer's phone and the bank statement show the same four characters the buyer knows, and a second
  `OtherDetails` entry (`PayCode`) carries it for a statement read by a person. `OtherDetails.Reference`
  **stays the intent reference**: it is what places a lost-callback notification on the intent with no
  corroboration, and swapping it for a code would send that money to the queue whenever the amount differed
  from the deposit. `MessageReference` stays the intent reference, the correlation key for the callback.
- Responses: `BookingResponse.payReference` and `BookingBalance.payReference` read the booking's own field.
  `UnitResponse.payReference` becomes the live booking's code, or null when the unit is not booked.
- `DemoActivitySeeder`: statements and payments quote the booking's code.

**Step 2 — frontend.**
- Booking detail, My bookings, TakePaymentModal prefill, payment forms: same field names, now the booking's
  own code; no change in markup beyond copy ("this booking's pay code").
- Unit inventory row: the code column shows the live booking's code with the buyer's name beside it, and is
  empty on an unbooked unit. The "record an outside sale" hint reads "Payments are tracked against the
  booking's pay code, shown once it is created."
- Types: `UnitRow.payReference` becomes nullable.

**Step 3 — tests.**
- `BookingServiceIT`: a new booking has a four-character code from the alphabet; a booking made after a
  cancellation on the same unit gets a different code; the unique index refuses a duplicate.
- `PayeeResolverIT` (new): a code resolves to its booking; a closed booking's code resolves to no booking
  with the cancelled reason; a listing reference still resolves.
- `CoopIpnIT`: fixtures put the code on the booking; corroboration cases unchanged in meaning.
- `CoopBillerIT`: validation by the booking's code; an advice quoting the code is placed without an amount
  match; an unknown code is 404.
- `CoopStkIT`: the push body carries the booking's code as the customer reference.
- `DevelopmentUnitServiceIT`: `generatesABlock` no longer asserts codes; `searchesByPayCode` moves to
  searching bookings.
- Every fixture that set `payReference` on a unit sets it on the booking instead.

## Not in scope
- A checksum character (the header of `V20260827090200` leaves the option open; the alphabet and length stay).
- Changing how slips are validated (`SlipValidationService` matches bank statements, not codes).
- Removing the booking reference (`BK…`) or listing reference as things a payer may quote.

## Progress

| Step | State | Notes |
|---|---|---|
| 0 | **Done, 21 September** | `V20260921120000`: `unit_bookings.pay_reference` NOT NULL with `uk_booking_pay_reference`; live bookings inherited their unit's code, every other booking drew a fresh one in SQL; the code joined the booking's `search_text`; `properties.pay_reference` dropped. `PayCodeAllocator` moved to `modules/bookings` and checks bookings; `BookingService.book` allocates in the insert transaction and names a pay-code clash in its conflict message. `PayeeResolver` resolves a code to its booking (`onBooking`: live → found, closed → "is cancelled, nothing to credit"). Unit responses carry the live booking's code or null; the unit search finds a unit through its live booking's code. Seeder draws a code per seeded booking. Fixtures moved the code to the booking; 397 tests green. |
| 1 | **Done, 21 September** | `CoopIpnService.placeAutomatically(statement, account, codeConfirmed)`: a bare code still needs the amount or the buyer's phone to agree, except when the caller vouches for the code; the biller advice does (`CoopBillerService.advise` passes `true`), because the bank validated the code with the payer moments earlier. Queue reason names the booking. STK narration `Payment <code> for BK…` plus an `OtherDetails.PayCode` entry; `Reference` stays the intent's so a lost callback still credits without corroboration. Responses were moved in step 0. Co-op test classes green (34). |
| 2 | **Done, 21 September** | Types already nullable; docs say whose code it is. Inventory row shows the live booking's code (titled so) and nothing on an unbooked unit; the outside-sale hint reads "tracked against the booking's pay code, shown once it is created". Booking detail and My bookings say the code is the booking's own, quoted wherever the buyer pays. TakePaymentModal prefill unchanged: the balance panel now carries the booking's code. Type-check and build green; not screenshotted, the admin session did not survive the backend restarts. `dist` re-zipped. |
| 3 | **Done, 21 September** | `PayeeResolverIT` (5, new): the code resolves however the payer wrote it and asks for corroboration; booking and listing references resolve without; a cancelled booking's code is found and refused with "cancelled" and the reference; after a re-booking the old code names the old buyer and the new code the new one; an unknown code says so. `BookingServiceIT` +2: a re-booked unit draws a different code and the old booking keeps its own; the unique index refuses a duplicate code (and the live-index test's raw insert now supplies a code, so it fails on the index it means to prove). `CoopBillerIT` +1: an advice quoting the bare code with an amount that matches nothing is placed. `CoopStkIT`: the push body carries `Payment <code> for BK…` and a `PayCode` detail while `Reference` stays the intent's. Plan complete. |
