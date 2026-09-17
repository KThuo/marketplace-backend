# Payments, completed: Co-op through Pesi, tied to a listing and to the person who paid

**The ask (17 September 2026):** complete the Co-op integration. Billers, IPN and STK push are what the
client needs first. Tie payments to listings and to the customers who make them. Stop offering payment
types nobody has configured. And when a listing has been paid for — even partly — say so, or take it off
the market, as a choice made when the booking is created.

Two repositories are the reference: `../../pesi` (the gateway itself) and `../../new-hodi/hodi-b` (a
mature collection module — statement mapping, IPN termination, an outbound client).

---

## 1. What is already here

Worth stating, because the gap is narrower than the ask suggests and the work should be the gap.

| Piece | State |
|---|---|
| `PesiIpnService` | **Good.** Stores the statement first, acknowledges, then places best-effort. Refuses to place on a bare reference match — a four-character code from a 32-letter alphabet is one mistyped letter away from another live one — so it wants the amount or the phone to agree as well. Unplaceable money is stored and queued, never refused. |
| `PesiStatement` | Stored, with the raw payload kept. |
| `Payment` | Links booking, development, property, buyer name and phone; carries source, method, external reference, balances before and after, void trail. |
| `PaymentType` / `PaymentAccount` | A catalogue of channels, and per-organisation accounts against them. |
| `PesiChannel` | Categories — CASH, CHEQUE, STK_PUSH, TRANSFER, VALIDATE — with the rule that **the category decides behaviour, never a catalogue id**. |
| Receive-payment form | Exists, picks a booking, records against it. |

## 2. What is missing, precisely

### 2.1 There is no outbound client — so STK push cannot happen
`PesiChannel` says it plainly: *"Not wired here yet: there is no outbound client, so these channels stay
switched off in the catalogue."* Pesi exposes `POST /api/ext/v1/transactions/coopbank/stk-push` (and a
generic `/pay`), authenticated with an `X-API-Key` header. Nothing in this codebase calls it.

`hodi-b`'s `PesiClient` is the shape to follow, and two of its decisions are load-bearing:

- **Two clients, two timeouts.** A quick one for asking about money; a patient one (three minutes) for
  moving it, because the gateway holds the connection until the payer has decided. Anything shorter times
  out on a payment that then succeeds — money taken, no record of it.
- **Never throws.** A gateway that is down returns a named failure, not an exception at the call site.
- **`status == "0"` decides, not the HTTP code.**

### 2.2 A payment is not tied to the person who made it
`Payment` carries `buyer_name` and `buyer_phone` — denormalised text — and no user id. A signed-in buyer
cannot reliably be shown their own payments, because the match is a name someone typed. `UnitBooking`
already has `buyerUserId`; the payment does not copy it.

### 2.3 A payment is not tied to a listing it can be found by
`property_id` exists and is nullable, and `booking_id` is NOT NULL — so every payment must belong to a
booking, and a payment against a plain listing (a house, not a development unit) has nowhere to go. The
client asks for payments tied to listings; today they are tied to bookings of development units.

### 2.4 The receive form offers channels nobody configured
`PaymentQueryService.methods()` returns `PaymentMethods.ALL` — a static list of six. So "Card" is offered
by an organisation with no card channel, and a payment can be recorded through a method the platform
cannot actually collect. The offered list must come from what this organisation has configured and
switched on, plus the ones that need no configuration at all (cash, cheque over a counter).

### 2.5 A paid-for listing keeps advertising itself
Nothing takes a listing down, or marks it, when money arrives against it. The client wants that as a
decision made per booking: *hold it*, *mark it*, or *leave it up*.

---

## 3. What we are building

### A — The outbound client, and STK push
1. `PesiClient` in `infra/pesi`: `X-API-Key` from configuration, quick and patient timeouts, `Outcome<T>`
   that never throws, `status == "0"` as the success test.
2. `POST /payments/stk-push` — amount, phone, booking or listing, payment account. Returns the gateway's
   own answer. Records a payment **only** on a confirmed success, through `PaymentService`, so a gateway
   credit is stamped exactly as a hand-keyed one is.
3. The catalogue's STK_PUSH channels become offerable once an account exists for them.

### B — Billers and IPN, completed
1. The IPN receiver keeps its shape. What it gains is the **unmatched queue as a screen**: what arrived,
   what it could not be placed against, and a person's decision to place it.
2. Placing an unmatched statement is an ordinary payment write with the statement's id on it, so the money
   is never counted twice.

### C — Payments tied to a listing and to a person
1. `payments.buyer_user_id`, copied from the booking when there is one and from the signed-in payer when
   the payment is theirs. A buyer's own payments then resolve by identity, not by a typed name.
2. `payments.property_id` filled for every payment, not only unit ones, and `booking_id` made nullable so a
   listing can be paid for without a development behind it.
3. A buyer-facing "my payments" read, scoped by identity.

### D — Only configured channels are offered
`methods()` becomes: the distinct methods of this organisation's **active** payment accounts, plus cash and
cheque where the organisation is allowed to take them. Nothing else. The receive form then cannot offer
what the platform cannot collect.

### E — A paid listing says so
1. `unit_bookings.on_payment` — `HOLD`, `MARK`, or `NONE`, chosen when the booking is created.
2. On the first payment that clears, the listing is withdrawn (`HOLD`), or flagged as *reserved — deposit
   paid* on its card (`MARK`), or left alone (`NONE`).
3. The marketplace shows the flag; search keeps or drops the row by the same rule.

---

## 4. Decisions worth recording

- **The gateway's answer is the record, not our reading of it.** The raw response is stored on the payment
  exactly as the IPN payload is stored on the statement.
- **Synchronous STK, not fire-and-forget.** Pesi holds the connection until the payer decides, so the caller
  gets a real outcome. A payment is written on success only, and never inside an open transaction across
  the call.
- **Idempotency is by external reference.** A gateway may deliver the same credit twice; the unique index on
  the external reference is what makes the second one a no-op rather than a double posting.
- **Cash and cheque need no configuration.** Everything else does. A channel with no account behind it is a
  method that cannot collect, and offering it is how money gets recorded against nothing.
- **Hiding a listing is a choice, not a rule.** A seller taking a holding deposit on a show house may well
  want it still advertised; one selling a single plot does not. The booking says which.

## 5. Sequence

1. D — only configured channels (small, self-contained, fixes a live complaint).
2. C — payments tied to listing and person (migration; everything else builds on it).
3. A — the outbound client and STK push.
4. B — the unmatched queue as a screen.
5. E — a paid listing says so.

Each lands with its own tests and its own commit.

## 6. Checks

- A method not configured for this organisation cannot be selected, and cannot be posted.
- An STK push that the payer declines writes no payment; one they approve writes exactly one, and a
  repeat delivery of the same reference writes none.
- A buyer sees their own payments and nobody else's.
- A booking marked HOLD takes its listing off the marketplace on first cleared payment; MARK leaves it up
  and flags it; NONE changes nothing.
- `mvn package` green, `npm run build` green.
