# An offer is a negotiation

**Date:** 23 September 2026 · **Branch:** `feature/coop-bank` (backend and frontend)

## What is wrong

An offer's thread is a list of lines with a rule down the left: the buyer's message, the seller's decision
note, a withdrawal. It cannot say what the money did — what was first offered, what the seller came back
with, what the buyer then offered, what was finally agreed — because a message carries only words. The two
sides cannot counter each other at all: the seller's only answers are accept, decline and "considering".

## What changes

- **A message carries a kind and an amount.** `lead_messages.kind` (MESSAGE, OFFER, COUNTER,
  ACCEPTED_COUNTER, REVIEW, ACCEPTED, DECLINED, WITHDRAWN) and `lead_messages.amount`. Words stay words;
  the moves in the negotiation are events with a figure, drawn differently.
- **The offer remembers the money.** `purchase_requests.original_amount` (never changes),
  `offer_amount` (the buyer's current figure), `counter_amount` and `counter_by` (the seller's outstanding
  counter, cleared when answered), `agreed_amount` (set on acceptance). Booking from an accepted offer uses
  the agreed figure, as it already used `offer_amount`.
- **Counters, both ways.** The seller counters with a figure and a word (`POST /offers/{ref}/counter`),
  which also marks the offer as being considered. The buyer accepts the counter
  (`POST /me/offers/{ref}/accept-counter`, which makes it their offer) or counters back
  (`POST /me/offers/{ref}/counter`). The seller's final Accept records the agreed figure.
- **The thread is a conversation.** `OfferThread.vue` draws the buyer on one side and the seller on the
  other — mine on the right, whichever side is reading — words and moves alike; the platform speaks from the
  seller's side. The moves are event cards carrying the figure. Above it a strip reads: original offer · current offer · counter outstanding · agreed.
- **Both sides can act from the thread.** The seller's offer page: message, or counter with a figure. The
  buyer's Offers tab: message, accept the counter, or counter back.
- The rule from before stands: the conversation is open only while the offer is live.

## Not in scope
- Counters on enquiries or viewings; those threads keep the plain history.
- A time limit on a counter.

## Progress

| Step | State | Notes |
|---|---|---|
| 1 | **Done, 23 September** | `V20260923090000`: `lead_messages.kind` (default MESSAGE) and `amount`; `purchase_requests.original_amount` (NOT NULL, backfilled), `counter_amount`, `counter_by`, `agreed_amount`; existing offer messages given kinds from the state they moved to. `LeadThreadService.record`/`recordAsBuyer` take kind and amount; `MessageResponse` carries both. `PurchaseRequestService`: submit records OFFER; decide records REVIEW / ACCEPTED (with `agreedAmount = offerAmount`, counter cleared) / DECLINED; withdraw records WITHDRAWN; `counter` (seller, `POST /offers/{ref}/counter`, marks under review), `acceptCounter` and `buyerCounter` (buyer, `/me/offers/{ref}/accept-counter`, `/me/offers/{ref}/counter`); `PurchaseRequest` defaults the first figure on persist; the seeder's insert names it. `OfferThread.vue`: bubbles mine-right/theirs-left, moves as cards with the figure on the side of whoever made them. Seller page: money strip (first offered · current · counter standing · agreed), the thread, message or counter-with-a-figure. Buyer tab: money strip, the thread, take the counter or offer a new figure. `OfferBookingIT` covers counter → acceptance. Suite 418 green. |
| 2 | **Done, 23 September** | Every conversation reads left incoming, right outgoing, nothing centred: `OfferThread` (moves take the author's side; the platform speaks from the seller's side), the seller's enquiry inbox and the buyer's enquiry thread (platform bubbles no longer in the middle), and `LeadHistory` on viewings and the buyer's history, which was a rule down the left and is now two-sided. |
