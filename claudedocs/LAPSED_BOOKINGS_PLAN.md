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

A LAPSED booking comes back to life, as the same booking: same reference, pay code, buyer, price,
schedule and payments. A CANCELLED booking does not: a cancellation is a contract termination and is
final (decision 4); the buyer books again.

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
| `booking.refund.penalty.basis` — PERCENT_OF_PAID, PERCENT_OF_DEPOSIT or FIXED | PERCENT_OF_PAID | overridable |
| `booking.refund.penalty.rate` — the percent, or the fixed amount, as the basis says | 0 | overridable |
| `booking.refund.penalty.cap` — the penalty never exceeds this amount (blank: no cap) | blank | overridable |
| `booking.refund.penalty.bank.share.percent` — the bank's share of the penalty; the rest is the owner's | 0 | overridable |
| `booking.refund.within.days` — a refund may be raised this long after closing (0: always) | 0 | overridable |
| `booking.revive.within.days` — a lapsed booking may be revived this long after closing (0: always) | 30 | overridable |
| a policy note shown to the buyer, in their words ("Deposits are refundable less 5% within 90 days") | blank | overridable |

Everything configurable, at the platform and per development (decisions 1 and 2). The bank sets the
development's policy (it sets the money settings today); the owner reads it; the buyer reads it before
committing (§2.5).

**The refund row** (`booking_refunds`): booking, amount paid at the time, penalty percent and amount
applied, amount to refund, the payee (the buyer's name, bank and account — captured on the form, with
the same Co-op account check the settlement wizard uses), the reason, who proposed it, state, and the
disbursement it became. Penalty may be **waived** or **reduced** on the row with a reason, by whoever
approves it — policy is the default, not a cage.

```
paid            = payments standing on the booking
penalty         = min(basis(rate), cap)                — or the waived/reduced figure, with a reason
                  basis: paid × rate% | deposit due × rate% | the fixed amount, never above paid
refund          = paid − penalty
bank's share    = penalty × bank share%; the owner's share is the rest
```

**Paying it** follows where the money is:

- Under `collection_mode = BANK` the bank holds the money, so the refund is a disbursement through the
  existing engine (`settlement_kind = REFUND`, payee kind BUYER), maker-checker inside the bank as every
  payout is. When Co-op confirms it, the refund row flips PAID, `unit_bookings.refunded_at` is set, and
  the booking's Payments tab shows the money going out beside the money that came in.
- Under `collection_mode = OWNER` the owner holds the money: the refund is recorded by hand — proposed,
  approved by the owner's checker, marked paid with the bank reference — exactly as an invoiced
  commission is.

**The penalty** is money the buyer forfeits, split by `bank.share.percent`. Under BANK collection the
owner's share is settled to the owner as a `FORFEIT` leg of the settlement engine and the bank's share
is retained or moved to its fee account under the same `RETAIN | TRANSFER` rule its commission follows;
under OWNER collection the owner holds it all and owes the bank its share, invoiced as a commission is.

**What it does not do:** it never refunds more than stands on the booking; it never runs automatically
(the reconciliation plan's rule, kept: a refund is a person's decision); it never touches a COMPLETED
booking (that is a sale, and unwinding a sale is a different piece); and a booking with a refund PAID
cannot be revived — the money is gone, so a revival would be a new booking.

**The buyer may ask** (decision 3): from their portal, "Ask to revive" on a lapsed booking, and "Ask for
a refund" on a lapsed or cancelled one, with a reason and, for a refund, the account to pay. The request
is a row of its own (`booking_requests`: kind REVIVE | REFUND, reason, the account, state ASKED |
GRANTED | DECLINED, who decided and why). Staff see it on the booking and in the list's "Needs a
decision" view; granting it runs the revival or raises the refund proposal from the buyer's own figures;
declining it says why, and the buyer reads the answer on the booking. The buyer never moves money or
reopens a home themselves — they ask, and the answer and its reason are on the record.

### 2.3 What each person sees

- **The booking's page**, closed: a banner saying when and why it closed, what stands on it, and two
  actions where they apply — "Revive" and "Refund", each a modal. A refund in flight shows its state and
  the disbursement's reference; a paid one shows what went back and what was kept.
- **The bookings list**: a "Closed with money on it" view, so the sales office sees what needs a
  decision; refund state on the row.
- **The buyer's portal**: the booking says it lapsed or was cancelled and why, shows the terms it was
  made under (§2.5), offers "Ask to revive" and "Ask for a refund" where they apply, and shows the
  request's answer and the refund's progress — proposed, approved, sent, paid — with the figures.
- **The development's money page**: refunds under Money out, with the penalty kept.
- **A Refunds report** under Money: booking, buyer, paid, penalty, refunded, state, dates.
- **Notifications**: the buyer on revival, on a refund raised, and on a refund paid; a reminder to the
  buyer three days before a hold expires (`booking.expiry.reminder.days`), since most lapses are people
  who forgot.

### 2.5 The terms are agreed before the buyer commits

Everything above that can cost the buyer money — the hold and when it lapses, the deposit, the payment
plan, the penalty and the refund window, that a cancellation is final, what reviving a lapse needs — is
told to the buyer before they commit, and their answer is kept. Transparency is the requirement
(decision 3); an agreement nobody can produce later is not one.

**The terms** are one page, filled for any booking or listing. A **terms template** lives at the
platform (`booking_terms_templates`: version, the text with placeholders, active from), editable with a
new version each time; one page, `/app/terms/booking/{booking}` and `/terms/listing/{reference}` on the
public side, renders the active version with the live figures — price, deposit due, plan, hold days and
expiry, penalty basis/rate/cap, refund and revive windows, "cancellation is final", the seller's policy
note. Nothing is duplicated: the policy is read from the development's money settings, the figures from
the booking.

**What acceptance keeps** is small: `booking_terms` (booking, template version, the filled values as they
were that day, presented at, accepted at / declined at, by whom, through which channel — portal, or on
paper with the vault document). The booking keeps linking to the same page, which renders *that* version
with *those* values, so a later change to the rate or the wording does not rewrite what the buyer
agreed. The record is the version and the numbers; the page is the presentation.

**When they are presented:**

- A booking made **from the buyer's own offer** (`book` on an accepted offer): the terms are shown to the
  buyer in the portal the moment the booking exists, before the pay code is usable. The booking is
  RESERVED but flagged `terms_state = PRESENTED`; "Pay" is not offered until ACCEPTED.
- A booking made **by the sales office** for a walk-in buyer: the buyer is sent the terms (email and
  SMS link, to the booking in the portal — a buyer with no account is invited to make one, as an offer's
  buyer is today). Until accepted, the same rule: no portal payment, and a payment recorded by staff
  against an unaccepted booking is refused unless staff record acceptance **on paper** — the buyer
  signed the printed terms, and the signed form is uploaded to the vault against the booking; the upload
  is required, not a checkbox. The audit says which way it was accepted. When that buyer later has an
  account, the portal shows the terms as "accepted on paper on <date>" with the form, and offers a
  confirm — welcome, not required.
- **Declined**: the booking is cancelled with the reason "Buyer declined the terms", the home released,
  nothing owed either way — a declined hold has no penalty, because nothing was committed.
- **Not answered** within the hold: it lapses as any hold does; the reminder before expiry says the
  terms are waiting.

**Where else the same terms appear:** on the listing's public page and in the offer flow as "Booking
terms on this home" (the policy note and the figures), so a buyer reads them before they even offer;
on the receipt and the booking letter; on the booking's page for staff, with the accepted-at line.

**Changing terms on a live booking** — a rescheduled plan, a price change from a counter — re-presents
the terms as a new version; the buyer accepts again or the old version stands for what was already
agreed. Nothing that worsens the buyer's position takes effect until accepted.

### 2.4 Permissions and approvals

- `BOOKINGS_MANAGE` revives, proposes a refund, decides a buyer's request, and records an acceptance on
  paper.
- A refund is approved by the existing disbursement checker (`DISBURSEMENTS_APPROVE`) under BANK
  collection, and by a new `BOOKINGS_REFUND_APPROVE` under OWNER collection — the owner's own checker,
  never the proposer.
- Waiving a penalty is a change on the row that the approver makes, with a reason; the audit says both
  figures.

## 3. Build order

1. **Policy and terms** — the settings on the money settings card with platform defaults; the terms
   snapshot on every new booking; presented, accepted, declined, on paper; the portal's terms screen and
   the pay gate; the public "Booking terms on this home"; the reminder before expiry. First, because
   nothing after it may cost the buyer money they were not told about.
2. **Revive** — the service method, the availability and window rules, the audit, the buyer notice, the
   page's banner and modal; the buyer's "Ask to revive" and staff's decision.
3. **Refund** — the row, the arithmetic with waiver, the BANK path through the disbursement engine and
   the OWNER path by hand, the hooks that flip it PAID, the page, the list view, the report, the notices;
   the buyer's "Ask for a refund" and staff's decision.
4. **The penalty's settlement** — the FORFEIT leg and the bank's share, in the settlements queue.

## 4. Decisions taken (29 September 2026)

1. **The penalty** is totally configurable: its basis (percent of what was paid, percent of the deposit
   due, or a fixed amount), its rate, its cap, and the windows — at the platform, overridable per
   development.
2. **Who keeps it** is a setting too: the bank's share as a percent, the rest the owner's.
3. **The buyer may ask** for a revival or a refund from the portal; transparency is the requirement.
4. **A cancellation is final** — it is a contract termination. Revival is for lapses only; a refund may
   follow either.
5. Following from 3: everything that can affect the buyer is presented before they commit, and their
   acceptance or refusal is kept (§2.5).

6. **The terms are a page, not a copy** — a versioned platform template filled with live figures for any
   booking or listing; acceptance keeps the version and the figures, nothing more.
7. **The refund is paid to a provided account**, typed on the request or the proposal and checked with
   Co-op, whoever holds it.
8. **Revival** goes straight to AGREED when money stands on the booking, else to RESERVED with a fresh
   hold.
9. **Both kinds of acceptance count**: the buyer's own in the portal, and on paper for a buyer without an
   account — with the signed form in the vault required, and a confirm offered when they sign in.

Nothing left to confirm before phase 1.

## 6. Progress

### Phase 1 — policy and terms — done (29 September 2026)

- `V20260929110000__a_booking_is_made_under_terms_the_buyer_agreed_to.sql`: the refund policy columns on
  `developments` (basis, rate, cap, bank share, refund and revive windows, the note); `booking_terms_templates`
  seeded with version 1; `booking_terms` (booking, template version, the figures as filled, presented /
  accepted / declined, channel, the signed form's vault document, the buyer's later confirmation);
  `unit_bookings.terms_state` (NONE for everything made before, PRESENTED, ACCEPTED, DECLINED) and
  `expiry_reminder_sent_at`.
- **The policy** — `BookingPolicyService`: eight platform settings (`booking.refund.penalty.basis / rate /
  cap / bank.share.percent`, `booking.refund.within.days`, `booking.revive.within.days`,
  `booking.policy.note`, `booking.expiry.reminder.days`), each overridable per development through the
  money settings, with the arithmetic (`penaltyOn`: percent of paid, percent of deposit or fixed, capped,
  never above what was paid) and the sentence a buyer reads. The money settings card shows the policy
  in force, says when it is the platform's, and edits it beside the commission rates.
- **The terms** — `BookingTermsService`: one versioned template at the platform (Settings → Booking
  terms: read, write a new version with the placeholders listed and a preview; old versions kept), one
  page rendered for a booking from its own kept figures and for a listing from today's policy
  (`GET /public/properties/{ref}/terms`, folded under the finance panel on every listing). Presented in
  the transaction that creates a live booking, with the buyer told by email and SMS — through their
  account, or the contact on the booking when they have none. A completed sale recorded by hand is not
  presented.
- **The answer** — the buyer accepts or declines on their booking in the portal, where "Pay" is withheld
  until they accept and a banner says why; declining cancels the booking with their reason and releases
  the home, nothing owed. The sales office records a signed form for a buyer without an account — the
  upload is required, goes into the vault against the booking, and is the acceptance; a buyer who later
  signs in sees it and may confirm. `assertMayPay` refuses a portal prompt or a staff-recorded payment
  against PRESENTED or DECLINED terms with a sentence that says what to do; bookings made before terms
  existed are not gated. Money that arrives through the bank's statement still lands — it is money.
- **The reminder** — the hourly expiry sweeper now tells each buyer once, `booking.expiry.reminder.days`
  before a hold lapses, that the terms are waiting when they are.
- Tests: `BookingTermsIT` (5): presented and filled from the policy, refused until accepted, then paid and
  agreed; declined; signed on paper and confirmed; the arithmetic under each basis and the listing's page;
  template versions. Every fixture that pays against a fresh booking now accepts the terms first.

