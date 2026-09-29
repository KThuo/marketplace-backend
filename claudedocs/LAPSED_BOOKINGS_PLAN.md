# Lapsed and cancelled bookings: revive, or refund under policy

29 September 2026. Scoping only — nothing built yet.

## 1. What happens today

A booking starts RESERVED with a hold (`expires_at`, 14 days unless the form says otherwise). The first
payment received against it makes it AGREED and stops the clock. An hourly sweep (`BookingExpirySweeper`)
closes any RESERVED booking past its hold: state LAPSED, close reason "The reservation window passed.",
`expires_at` cleared, the home back to AVAILABLE. Staff holding `BOOKINGS_MANAGE` can close a live booking
as CANCELLED with a reason; the home is released the same way.

That is the end of the road. A LAPSED or CANCELLED booking:

- cannot be reopened — the buyer who turns up a day late books again from scratch, with a new pay code,
  a new schedule and no history;
- keeps whatever money landed on it — payments stay attached (the list shows lapsed bookings with
  1.5m and 1.8m paid), the balance view still counts them as received, and nothing on the platform can
  give any of it back or say that it was;
- says nothing to the buyer — the sweep writes an audit row and releases the home, and the buyer finds
  out when they try to pay.

Money on a closed booking is real: a payment can be recorded against a LAPSED booking, and a CANCELLED
booking was often AGREED, which means it had a payment. Refunding today means a bank transfer nobody on
the platform can see, against a booking whose figures then lie.

## 2. The design

### 2.1 Revive

A LAPSED or CANCELLED booking comes back to life, as the same booking: same reference, pay code,
buyer, price, schedule and payments.

- Allowed while the home is still available — no other live booking on it and its sale state AVAILABLE.
  If somebody else has since booked it, the answer is no, and it says who.
- Back to RESERVED with a fresh hold (days from the request, the platform default otherwise) when
  nothing has been paid; straight to AGREED when money stands on it, because money is the commitment.
- A reason, kept on the record as the close reason was; a REVIVED entry in the audit; the buyer is told,
  with the new expiry where there is one.
- `BOOKINGS_MANAGE`, on the booking's page. Maker-checker is not proposed: reviving moves no money, and
  the home's availability is the only thing at stake.
- A time limit after closing (`booking.revive.within.days`, default 30, 0 for no limit) — after that the
  booking stays closed and a new one is made, so an old lapse cannot quietly resurrect against a unit
  whose price has since changed.

### 2.2 Refund, under policy

A refund is a decision and a payout, both recorded.

**The policy** lives on the development's money settings, with platform defaults, the same shape as
commissions:

| Setting | Platform default | Per development |
|---|---|---|
| `booking.refund.penalty.percent` — retained from what was paid | 0 | overridable |
| `booking.refund.penalty.cap` — the penalty never exceeds this amount (blank: no cap) | blank | overridable |
| `booking.refund.within.days` — a refund may be raised this long after closing (0: always) | 0 | overridable |
| a policy note shown to the buyer on their booking ("Deposits are refundable less 5% within 90 days") | blank | overridable |

The bank sets the development's policy (it sets the money settings today); the owner reads it.

**The refund row** (`booking_refunds`): booking, amount paid at the time, penalty percent and amount
applied, amount to refund, the payee (the buyer's name, bank and account — captured on the form, with
the same Co-op account check the settlement wizard uses), the reason, who proposed it, state, and the
disbursement it became. Penalty may be **waived** or **reduced** on the row with a reason, by whoever
approves it — policy is the default, not a cage.

```
paid            = payments standing on the booking
penalty         = min(paid × percent, cap)             — or the waived/reduced figure, with a reason
refund          = paid − penalty
```

**Paying it** follows where the money is:

- Under `collection_mode = BANK` the bank holds the money, so the refund is a disbursement through the
  existing engine (`settlement_kind = REFUND`, payee kind BUYER), maker-checker inside the bank as every
  payout is. When Co-op confirms it, the refund row flips PAID, `unit_bookings.refunded_at` is set, and
  the booking's Payments tab shows the money going out beside the money that came in.
- Under `collection_mode = OWNER` the owner holds the money: the refund is recorded by hand — proposed,
  approved by the owner's checker, marked paid with the bank reference — exactly as an invoiced
  commission is.

**The penalty** is money the buyer forfeits. Under BANK collection it sits with the bank until the bank
settles it to the owner as a `FORFEIT` leg of the settlement engine (the bank's commission does not
apply — there was no sale); under OWNER collection it is already the owner's. Whether the bank takes a
share is a question below.

**What it does not do:** it never refunds more than stands on the booking; it never runs automatically
(the reconciliation plan's rule, kept: a refund is a person's decision); it never touches a COMPLETED
booking (that is a sale, and unwinding a sale is a different piece); and a booking with a refund PAID
cannot be revived — the money is gone, so a revival would be a new booking.

### 2.3 What each person sees

- **The booking's page**, closed: a banner saying when and why it closed, what stands on it, and two
  actions where they apply — "Revive" and "Refund", each a modal. A refund in flight shows its state and
  the disbursement's reference; a paid one shows what went back and what was kept.
- **The bookings list**: a "Closed with money on it" view, so the sales office sees what needs a
  decision; refund state on the row.
- **The buyer's portal**: the booking says it lapsed or was cancelled, shows the policy note, and shows
  the refund's progress once one is raised. Whether the buyer can *ask* for a refund or a revival from
  there is a question below.
- **The development's money page**: refunds under Money out, with the penalty kept.
- **A Refunds report** under Money: booking, buyer, paid, penalty, refunded, state, dates.
- **Notifications**: the buyer on revival, on a refund raised, and on a refund paid; a reminder to the
  buyer three days before a hold expires (`booking.expiry.reminder.days`), since most lapses are people
  who forgot.

### 2.4 Permissions and approvals

- `BOOKINGS_MANAGE` revives and proposes a refund.
- A refund is approved by the existing disbursement checker (`DISBURSEMENTS_APPROVE`) under BANK
  collection, and by a new `BOOKINGS_REFUND_APPROVE` under OWNER collection — the owner's own checker,
  never the proposer.
- Waiving a penalty is a change on the row that the approver makes, with a reason; the audit says both
  figures.

## 3. Build order

1. **Revive** — the service method, the availability and time-limit rules, the audit, the buyer notice,
   the page's banner and modal, the reminder before expiry. Ships alone; useful on its own.
2. **Policy** — the four settings on the money settings card with platform defaults; the note on the
   buyer's booking.
3. **Refund** — the row, the arithmetic with waiver, the BANK path through the disbursement engine and
   the OWNER path by hand, the hooks that flip it PAID, the page, the list view, the report, the notices.
4. **The penalty's settlement** — the FORFEIT leg to the owner, in the settlements queue.

## 4. To confirm before building

1. **The penalty's base.** A percentage of what was paid (the design above), or a fixed amount, or a
   percentage of the deposit due? And should the cap exist?
2. **Who keeps the penalty.** The owner in full, or does the bank take its commission rate off it as it
   does off a sale?
3. **Revival.** Straight to AGREED when money stands on it, or always back to RESERVED with a fresh hold?
   Is 30 days the right default for how long after closing a booking may be revived?
4. **The buyer's side.** May the buyer request a revival or a refund from the portal (raised for staff
   to decide), or is this staff-only?
5. **The refund's payee.** Always the buyer named on the booking, to an account typed on the form — or
   may it go to a third party (a parent who paid), with a reason?
6. **Cancelled bookings.** Treat CANCELLED like LAPSED for both revive and refund (the design above), or
   is a staff cancellation final?
