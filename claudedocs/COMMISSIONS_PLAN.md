# Commissions: what the bank earns on a sale, what an agent earns for bringing the buyer, and how each is paid

Written 28 September 2026, after the money-in-and-out plan (`DEVELOPMENT_MONEY_IN_AND_OUT_PLAN.md`), which
named commissions as the next piece. Branch: `feature/commissions` in both repos, off
`feature/development-money-in-and-out`.

## 1. The requirement, in the client's terms

> The bank may be getting commissions from the sales, or even awarded to agents who bring in customers.
> This should be captured, since we are onboarding sales agents.

Two people can earn on one sale:

- **The bank**, for selling on the owner's behalf and collecting the money. It agrees a rate per project.
- **An agent**, for introducing the buyer. Agents are being onboarded now; today the app knows them only
  as people who list homes for private clients, not as people who bring buyers to a development.

Both must be *captured* — which sale, which rate, how much, to whom, and whether it has been paid — and
the bank, which holds the buyers' money under `collection_mode = BANK`, should be able to pay both out of
what it holds when the sale concludes, with the owner receiving the rest. That last part is the payout the
client believes exists ("moving funds to seller account when the sale is fully concluded") and which the
code does not yet tie to a sale (§2).

## 2. What the code does today

- **`commission_records`** (`V20260826160000__seller_operations.sql`, module `sellerops`): one row per
  completed **house** sale, raised by `BookingService.applyToProperty` → `CommissionService.raiseFor(Property)`.
  Rate from `ConfigKey.COMMISSION_RATE_PERCENT` (default 1.5, tenant-overridable) is copied onto the row —
  the right principle: a rate change never restates history. States `DUE | INVOICED | PAID | WAIVED`;
  platform staff settle by hand (`POST /commissions/{ref}/settle`, `COMMISSIONS_SETTLE`). Page
  `/app/commission`, report `COMMISSION`.
  - Gaps: no `booking_id` (keyed on `property_id, sold_at`); no payee — the platform is the only payee and
    the seller tenant the only payer; **development units raise nothing**
    (`DevelopmentInventoryService` defers "commission per unit sold"); `rateFor(tenantId)` ignores its
    argument and reads the *caller's* tenant override, so a seller's agreed rate is only honoured when the
    seller is the one completing the booking.
- **Agents** (`agent_profiles`): a one-person selling organisation with a licence and an agreement, approved
  by the bank (`AGENTS_DECIDE`). Linked to the *listings* they publish (`properties.agent_profile_id`) and to
  nothing else. No lead, offer, booking or buyer records an agent.
- **A sale concludes** when `BookingService.complete` finds `v_booking_balances.balance = 0` and writes
  `COMPLETED`; the property goes `SOLD`. Nothing follows: no settlement, no payout, no commission for units.
- **Disbursements** can pay a `SELLER_ORGANISATION` but carry no `booking_id`; a payout to the owner is
  typed by hand and reconciled by memory.
- **Collection** is per development: `BANK` (holds until every party is satisfied) or `OWNER`.

## 3. The design

### 3.1 A commission schedule, per development, set by the bank

A rate is a term of the bank's agreement with the owner, so it is set where the other terms of that
agreement are set: the development's "Who collects, who spends" card gains a third block, **Commission**.

```
developments
  bank_commission_percent   numeric(5,2)  null  -- null = the platform default (COMMISSION_RATE_PERCENT)
  agent_commission_percent  numeric(5,2)  null  -- null = AGENT_COMMISSION_RATE_PERCENT (new key, default 0)
  agent_commission_paid_by  text          null  -- SELLER | BANK; null = SELLER
```

- Set by platform staff with `DEVELOPMENT_FINANCE_SETTINGS` (the existing permission; it is the same card).
  Owners see it, never change it.
- Zero is a rate and raises nothing; null means "the default", read at raise time.
- `agent_commission_paid_by` says whose money the agent's fee comes out of when the bank settles a sale
  (§3.4): the seller's proceeds (the Kenyan norm — the seller pays the agent) or the bank's own commission
  (a bank that runs its own sales force). It changes only who bears it, never the amount.
- Houses (no development) keep the platform default and the seller's tenant override, resolved for the
  **seller's** tenant, which fixes the `rateFor` bug in passing.

### 3.2 Who brought the buyer: attribution on the booking

A booking gains `introduced_by_agent_id → agent_profiles`, nullable, settable while the booking is not
COMPLETED, by whoever may manage the booking (`BOOKINGS_MANAGE`) and by platform staff. Only an APPROVED
agent may be named.

It is also carried on the road to the booking, so nobody has to remember it at the end:
`purchase_requests.introduced_by_agent_id` (an offer) copies onto the booking an accepted offer becomes;
`enquiry_tickets.introduced_by_agent_id` copies onto an offer raised from the enquiry. Each is optional and
editable at its own stage.

How early an introducer may be named is the bank's to set: `ConfigKey.AGENT_ATTRIBUTION_FROM` =
`ENQUIRY | OFFER | BOOKING` (default `ENQUIRY`). A bank that only wants it on the booking sets `BOOKING`
and the field is not offered on enquiries or offers; the carry-forward simply has nothing to carry.

Attribution is a fact about the sale, so it is a **change-set field in the booking's audit**, and once the
sale is COMPLETED it is frozen: a commission has been raised against it. Changing it after that is a
correction that voids the agent line and raises a new one (§3.3), not an edit.

The agent's own screen (`AGENT_SELF_VIEW`) lists the bookings they are named on, in every state — an agent
should be able to see "I brought this buyer, the sale is at 60% paid" before any money is due.

### 3.3 Raising: one line per payee, on every completed sale

`commission_records` becomes a table of commission **lines** against a **booking**:

```
commission_records
  booking_id        bigint  → unit_bookings   -- new; NOT NULL for new rows, null on the legacy house rows
  development_id    bigint  → developments    -- new, denormalised for scoping and statements
  payee_kind        text    NOT NULL           -- PLATFORM | AGENT        (legacy rows: PLATFORM)
  agent_profile_id  bigint  → agent_profiles   -- when payee_kind = AGENT
  paid_by           text    NOT NULL           -- SELLER | BANK           (legacy rows: SELLER)
  basis_amount      numeric -- the sale price the rate was applied to (rename of sale_price, kept as-is)
  rate_percent, amount, currency, state, invoice_ref, invoiced_at, paid_at, waived_reason, note  -- as today
  disbursement_id   bigint  → disbursements    -- set when the bank pays it (§3.4)
  uk: (booking_id, payee_kind, coalesce(agent_profile_id, 0)) where status live
```

- `CommissionService.raiseFor(UnitBooking)` replaces `raiseFor(Property)`. Called from
  `BookingService.complete` and `recordSale`, for houses **and units**. Raises the PLATFORM line if the bank's
  rate is above zero, and the AGENT line if an agent is named and the agent rate is above zero. Idempotent
  per (booking, payee). Still never fails the sale.
- The rate and `paid_by` are copied onto the row at that moment; the schedule can change next quarter
  without restating this sale.
- Basis is the **price agreed** on the booking. Whether the bank wants it on price or on collected (the
  same at completion, different if a discount is later recorded) is a question for the bank (§6).
- A booking that is later cancelled after completion (reversal) voids its lines with a reason — the existing
  `WAIVED`+reason path, labelled "Sale reversed".
- `waived_reason` CHECK and `paid_at` CHECK stay.

### 3.4 Paying: settlement of a sale the bank collected

This is the piece that gives the client what they thought they had. When a booking on a development with
`collection_mode = BANK` reaches COMPLETED, the bank owes the owner the proceeds, less what is deducted.

A **sale settlement** is computed, never stored as a balance:

```
gross          = collected on the booking (v_booking_balances.paid)
bank fee       = PLATFORM line amount                       (deducted)
agent fee      = AGENT line amount, if paid_by = SELLER     (deducted)   |  if paid_by = BANK: from the bank's own fee
net to owner   = gross − deductions
```

It is shown on the booking (a "Settlement" panel, platform staff and the owner) and on a new
`/app/settlements` queue for the bank: every COMPLETED, bank-collected booking not yet settled.

**Settling** proposes disbursements through the existing engine, maker-checker inside the bank as today:

- one to the **owner** (`payee_kind = SELLER_ORGANISATION`, the owner's tenant/institution), amount = net,
  purpose "Sale proceeds — <unit>, <development>", from the bank's own send account;
- one to the **agent**, if there is an AGENT line, to one of the agent's **payout accounts** (§3.4a); the
  proposer picks which when the agent has more than one, the default preselected;
- optionally a third, the **bank's own fee** to its fee account, when the bank has said it wants its fee
  moved rather than retained (§3.4b).

Each disbursement carries `booking_id` (new nullable column) and `settlement_kind` (`PROCEEDS | AGENT_FEE`),
so a statement can say what a payout was for. When the bank confirms a disbursement `SUCCEEDED`:

- the agent line flips `PAID` with `paid_at` and `disbursement_id` — it writes itself, like a development's
  cost does;
- the PLATFORM line flips `PAID` when the **proceeds** disbursement succeeds under `RETAIN` — the bank has
  kept its fee by paying out the rest, and the row records that it was retained — or when the **fee**
  disbursement succeeds under `TRANSFER`.
- a booking is **settled** when its proceeds disbursement has succeeded (`unit_bookings.settled_at`, set by
  the same hook). The queue drops it; the panel shows the references.

Under `collection_mode = OWNER` the bank holds nothing, so nothing is deducted: the PLATFORM line is
**invoiced** to the owner exactly as today (`INVOICE → PAID` by hand), and the AGENT line is the owner's to
pay — recorded here, marked PAID by the owner's own checker with a reference, or paid through the
development's "Pay a beneficiary" flow if the agent is registered as that development's beneficiary, in
which case it writes itself the same way.

#### 3.4a An agent's payout accounts

On the agent's profile, not in the beneficiary register: an agent is paid as themselves, wherever they
happen to be introducing buyers, so the account belongs to the person.

```
agent_payout_accounts
  agent_profile_id → agent_profiles, bank_code, account_no, holder_name (as typed), confirmed_name,
  verification VERIFIED | UNVERIFIED, verified_at, is_default, status, audit columns
  uk (agent_profile_id, bank_code, account_no) where live
```

- Several per agent; exactly one default. Added by the agent (`AGENT_SELF_UPDATE`) or by the bank
  (`AGENTS_DECIDE`), on the profile page and at application time.
- Confirmed with the bank the same way a beneficiary is (`PayoutAccountCheck`, phase 2 of the money plan)
  and asked again when a settlement is proposed — the name the checker approves is the bank's second answer.
  Only a VERIFIED account can be paid.
- Where money goes is a fact worth a second pair of eyes, but a one-person organisation has no second
  person, so an account an agent adds is UNVERIFIED until the bank's check confirms the holder; the bank
  sees every account on the register page. That is the check; there is no approval queue for it.

#### 3.4b The bank's fee: retained or transferred

`ConfigKey.PLATFORM_COMMISSION_SETTLEMENT` = `RETAIN | TRANSFER` (default `RETAIN`).

- `RETAIN`: the fee stays in the collection account; the PLATFORM line is PAID when the proceeds go out.
- `TRANSFER`: a third disbursement moves the fee to the bank's fee account, named in
  `ConfigKey.PLATFORM_COMMISSION_FEE_ACCOUNT` — a **platform-owned payment account** chosen from the
  platform's live accounts under Payment types (a new purpose, `FEES`, set up like any other account and
  approved the same way). With `TRANSFER` set and no account named, settlement refuses with a message
  that says which setting to fix.

Both are read at proposal time, and the choice is copied onto the settlement (the fee disbursement exists
or it does not), so changing the setting later never restates a settled sale.

Why not deduct at every instalment? Because the sale is not concluded until it is fully paid and every
document has moved, which is the whole reason the bank holds the money. One settlement, at the end.

### 3.5 What each person sees

- **Bank — `/app/commission`** (existing page, rebuilt on the new shape): every line, filtered by payee kind,
  development, agent, state, period; totals by payee kind; actions as today (invoice, mark paid, write off)
  for what the bank settles by hand; a link to the settlement that paid the rest.
- **Bank — `/app/settlements`** (new): the queue described in §3.4, with the computed figures and a
  "Settle" action that proposes the disbursements; a settled tab with references.
- **Owner — the development finance tab**: a **Sales settlements** card: gross collected on completed sales,
  bank's fee, agents' fees, net paid to them, what is still with the bank. Money in stays the buyers' money;
  this card is the owner's answer to "what did I actually get".
- **Owner — a booking**: the Settlement panel (§3.4), read-only, and the "Introduced by" field.
- **Agent — `/app/my-commissions`** (new, `AGENT_SELF_VIEW`): bookings they are named on, each line's
  state, what was paid and when, and their payout accounts (§3.4a). Their own figures only, through their
  one-person tenant scope.
- **Statements**: report `COMMISSION` gains columns (payee kind, agent, development, booking reference,
  paid by, disbursement reference) and filters; a new **`SALE_SETTLEMENTS`** report (one row per completed
  bank-collected booking: gross, fees, net, state, references), owner-scoped like the development
  statements; an **`AGENT_SALES`** report (bookings introduced per agent, value, commission due/paid) for the
  bank.

### 3.6 Permissions and approvals

- Existing: `COMMISSIONS_VIEW`, `COMMISSIONS_SETTLE` (platform only) stay and cover the hand-settled paths.
- New: `SETTLEMENTS_VIEW` (platform and owners: owners see their own), `SETTLEMENTS_MAKE` (platform only:
  proposes the disbursements; the *decision* is the existing `DISBURSEMENTS_APPROVE`, so the checker is a
  different person by the existing rule).
- `AGENT_SELF_VIEW` already exists and gates the agent's page.
- No new approval handler: a settlement is two disbursements, and those already have one. The schedule on a
  development goes through the development's existing approval path only if that card does today (it does
  not — settings are direct with an audit line — so the rate is the same).

## 4. Build order

1. **Schema and raising** — migration (schedule columns; commission line columns and backfill; attribution
   columns; `disbursements.booking_id/settlement_kind`; `unit_bookings.settled_at`; `AGENT` beneficiary
   type); `CommissionService.raiseFor(UnitBooking)` for houses and units; the `rateFor` fix; attribution on
   the booking API; tests.
2. **Attribution and the agent's view** — "Introduced by" on booking, offer and enquiry, gated by
   `AGENT_ATTRIBUTION_FROM` (backend + frontend); `agent_payout_accounts` on the profile, at application and
   on the register; `/app/my-commissions`; tests.
3. **Settlement** — the computation, the queue, the proposal into the disbursement engine (proceeds, agent
   fee, and the bank's fee under `TRANSFER`), the `FEES` payment-account purpose and the two settings, the
   write-itself hooks on SUCCEEDED, the booking panel, owner's Sales settlements card; tests including the
   OWNER-collection and `TRANSFER` paths.
4. **The commission page and statements** — `/app/commission` on the new shape; reports `COMMISSION`
   (extended), `SALE_SETTLEMENTS`, `AGENT_SALES`; walk-through.

Each phase leaves the app working; phase 1 alone already makes units raise the bank's commission.

## 5. Not in this piece

- Tiered or per-unit-type rates (a flat percentage per development covers the agreements the bank has now).
- VAT on commission — the amount is the amount; whether it is VAT-inclusive is the bank's invoicing concern
  (§6). A `vat_percent` column can be added without restating anything.
- Commission on rentals, auctions or valuations.
- Paying agents by M-Pesa (the beneficiary model has room; PesaLink only, as for everybody).
- Splitting one agent commission between two agents.

## 6. To confirm with the bank

1. **Basis**: price agreed, or amount collected? (They are equal at completion unless a discount was recorded
   after booking.) Plan assumes price agreed.
2. **Who pays the agent**: the seller out of proceeds (default) or the bank out of its fee — per development.
3. **Timing**: one settlement when the sale is fully concluded (plan), never per instalment.
4. **VAT**: is the rate VAT-inclusive? Affects the invoice text only.
5. **Agent onboarding**: is every approved agent eligible to be named as introducer, or only agents on a
   list the bank keeps per development? Plan assumes any approved agent.

## 7. Progress

### Phase 1 — done (28 September 2026)

- `V20260928210000__a_sale_pays_the_bank_and_the_agent_who_brought_the_buyer.sql`: the schedule on
  `developments` (`bank_commission_percent`, `agent_commission_percent`, `agent_commission_paid_by`, with
  CHECKs); `unit_bookings.introduced_by_agent_id`; `commission_records` becomes lines against a booking
  (`booking_id`, `booking_ref`, `development_id/_name`, `payee_kind`, `agent_profile_id`, `agent_name`,
  `paid_by`, `disbursement_id`), legacy rows backfilled to their completed booking where there is exactly one,
  unique per (booking, payee), search text regenerated.
- `ConfigKey.AGENT_COMMISSION_RATE_PERCENT` (default 0, tenant-overridable).
- `Development`: the three fields, `agentFeeBorneBy()`. `DevelopmentMoneySettingsService`: rates saved and
  validated (0–100, three decimals) on the same card and endpoint as who collects and who spends; the
  response carries the platform defaults so the card can say what a blank means.
- `CommissionService.raiseFor(UnitBooking, Property, Development)` replaces `raiseFor(Property)`: a PLATFORM
  line and, where the booking names an approved agent, an AGENT line; units and houses alike; rate and
  bearer copied; idempotent per (booking, payee); never fails the sale. The platform default is resolved for
  the seller's tenant (`TenantContext.runAs`), which fixes `rateFor` ignoring its tenant. `forBooking(id)`.
  List filter `payeeKind`.
- Bookings: `CreateBookingRequest.introducedByAgentRef` (eleven-argument constructor kept),
  `POST /bookings/{id}/introducer` (`BOOKINGS_MANAGE`; live bookings only; approved agents only),
  `BookingResponse.introducedByAgentRef/Name`.
- Frontend: the "Who collects, who spends, who earns" card shows and (for the bank) edits the schedule;
  `/app/commission` gains an "Earned by" column and payee filter; types.
- Tests: `CommissionsIT` (8). Full suite 502, one pre-existing test (`BeneficiariesIT.ownershipAndMakerChecker`)
  corrected: it assumed a stranger's list is empty, which a beneficiary shared with every organisation
  rightly makes false.

### Phase 2 — done (28 September 2026)

- `V20260928230000__who_brought_the_buyer_is_known_from_the_first_enquiry_and_an_agent_says_where_to_be_paid.sql`:
  `introduced_by_agent_id` on `enquiry_tickets` and `purchase_requests`; `agent_payout_accounts` (several per
  agent, one default, VERIFIED/UNVERIFIED with the bank's confirmed name).
- `ConfigKey.AGENT_ATTRIBUTION_FROM` (ENQUIRY | OFFER | BOOKING, default ENQUIRY).
- `IntroducerService` (agents): the one rule for who may be named (approved agents), how early
  (`allowedAt(stage, from)`; later stages always open), and the picker's options. Bookings, enquiries and
  offers all resolve through it.
- Carrying: an offer inherits the introducer from the buyer's enquiry on the same home (offers are not
  raised *from* enquiries in this app, so the same buyer on the same home is the link); a booking made from
  an offer inherits the offer's. `POST /enquiries/{ref}/introducer` (`ENQUIRIES_ASSIGN`),
  `POST /offers/{ref}/introducer` (`PURCHASE_REQUESTS_DECIDE`, until it is a booking);
  `GET /agents/options` for the picker (anybody who may set one).
- `AgentPayoutAccountService`: add (asks the bank, first one is the default), ask again, make default,
  remove (the next one along becomes the default); the agent's own under `/me/agent/accounts…`
  (`AGENT_SELF_UPDATE`), the bank's under `/agents/{ref}/accounts…` (`AGENTS_DECIDE`); `payableDefault()`
  for phase 3 to propose from.
- `AgentEarningsService`: `/me/agent/introductions` (every booking the agent brought, from the day it is
  made, with the line once the sale completes), `/me/agent/commissions` (their own lines, by agent id rather
  than tenant), `/me/agent/totals`.
- Frontend: `IntroducerSelect` (booking forms: unit inventory, book-a-home; hides itself below the
  configured stage), `IntroducerLine` (booking detail, offer detail, enquiry thread; shows any carried name,
  edits from the configured stage on), the offer-to-booking modal says who is carried,
  `PayoutAccountsCard` (agent's profile once approved; the register's "Where they are paid" row action),
  `/app/my-commissions` under Mine.
- Deliberately left: naming a payout account on the public application form. It matters once the agent is
  approved, which is when their profile offers it.
- Tests: `AgentAttributionIT` (4). The stage rule is tested with the setting passed in, not written to the
  configuration table — the cache is shared with the dev server and a test that depended on evicting it has
  been flaky before.

### Phase 3 — done (28 September 2026)

- `V20260929010000__a_sale_the_bank_collected_is_settled.sql`: `disbursements.booking_id` and
  `settlement_kind` (PROCEEDS | AGENT_FEE | BANK_FEE, CHECKed together); `unit_bookings.settled_at`.
- `ConfigKey.PLATFORM_COMMISSION_SETTLEMENT` (RETAIN | TRANSFER, default RETAIN) and
  `PLATFORM_COMMISSION_FEE_ACCOUNT` ("0011/account", blank refuses TRANSFER). Read at proposal; the legs are
  the record.
- Permissions `SETTLEMENTS_VIEW` (module COMMISSIONS; granted to the seller groups that read development
  finance, and to the platform's read-only groups) and `SETTLEMENTS_MAKE` (platform only). The decision on
  each transfer stays `DISBURSEMENTS_APPROVE`, so the checker is a different person by the existing rule.
- `SettlementService` (new module `settlements`): `figures()` computed from the booking's money in and its
  live lines — gross, bank fee, agent fee, who bears it, what the bank keeps, net to owner; `forBooking`
  (owner reads, bank reads and is offered the owner's known accounts and the agent's confirmed ones),
  `queue(settled)` (bank sees all, owner their own), `forDevelopment` (the owner's card), `settle` (bank only;
  refuses unless AWAITING with no blockers; confirms every account with the bank on the way through; proposes
  the legs). Houses are not settled here — a house's seller is paid by its buyer.
- `DisbursementService.proposeSettlementLeg(SettlementLeg)`: the engine's part — row, checker's snapshot
  ("Settles: the proceeds of sale BK…"), approval scoped to the bank's own staff. `SettlementRecorder`,
  called beside the cost recorder when the bank confirms SUCCEEDED: an agent-fee leg pays the agent's line;
  the proceeds leg settles the booking and, unless a fee leg exists, pays the platform's line (retained); a
  fee leg pays the platform's line on its own arrival.
- Frontend: `/app/settlements` (queue with Awaiting / Settled tabs; "Settle" opens `SettleSaleModal`, a
  two- or three-step wizard: owner's account from the bank's known ones or typed, the agent's confirmed
  account, confirm); `SettlementPanel` on the booking page (figures, legs with links, blockers, the button
  for the bank); `SalesSettlementsCard` on the finance tab of a bank-collected development ("Paid to the
  owner … still with the bank"); nav under Money.
- Tests: `SettlementIT` (5): the figures both ways the agent's fee can be borne; owner-collected sales stay
  out; proposing writes two legs with the bank's names and maker-checker, refuses a second proposal, and the
  confirmations settle the sale and pay the lines in the right order; TRANSFER adds the fee leg (guarded
  against the shared config cache).

## 8. Decisions taken (28 September 2026)

- (a) Attribution is carried from enquiry → offer → booking, and how early it may be named is a setting
  (`AGENT_ATTRIBUTION_FROM`, §3.2) — the client may want it on the booking only.
- (b) An agent is paid to accounts on their own profile, several allowed, one default (§3.4a), not through
  the beneficiary register.
- (c) Whether the bank retains its fee or moves it to a fee account is a setting
  (`PLATFORM_COMMISSION_SETTLEMENT`, §3.4b), with the account named in a second one.

The standing rule from the client: where a choice can be a configuration, make it one.
