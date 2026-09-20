# An accepted offer becomes a booking

**Date:** 20 September 2026 · **Branch:** `feature/coop-bank` (backend and frontend)

## What is wrong

A buyer offers, the seller accepts, and the offer is finished: `PurchaseRequestService.decide` sets
`ACCEPTED`, tells the buyer "they will be in touch", and nothing else happens. The offer never references a
booking, and a booking can only be made from the inventory screen or a property's own page — where the
person has to retype the buyer's name and phone and the agreed figure that the offer already holds.

## What changes

### Backend
- `purchase_requests.booking_id` (nullable, FK, unique): the booking an accepted offer became.
- `POST /api/v1/offers/{reference}/book` (`BOOKINGS_MANAGE`), body: price agreed, deposit due, payment plan,
  hold days, notes, instalments — everything optional; the offer supplies the buyer, the home and the
  defaults (price = offer amount, deposit = what the buyer said they had ready).
- `PurchaseRequestService.book` refuses an offer that is not `ACCEPTED` or already has a booking, creates the
  booking through `BookingService.createForProperty` (so every bookable rule, visibility rule and the
  Maker-side checks stay where they are), links the buyer's account to the booking (`buyer_user_id`) so the
  booking appears under their own "My bookings" and they can pay it, writes the booking on the offer, records
  it on the offer's thread and tells the buyer.
- `OfferResponse` carries `bookingId` and `bookingReference`.

### Frontend
- Offers page: an accepted offer without a booking offers **Convert to booking**; one with a booking shows
  "Booked · BK…" linking to the booking. The accept dialog's warning says what the next step is.
- `BookFromOfferModal`: the buyer read-only from the offer, price agreed and deposit prefilled, plan, hold
  days, notes. Submit calls the new endpoint and lands on the booking, where payments are received.

## Not in scope
- Marking the listing sold — still the booking's completion.
- Re-opening a declined or withdrawn offer.
