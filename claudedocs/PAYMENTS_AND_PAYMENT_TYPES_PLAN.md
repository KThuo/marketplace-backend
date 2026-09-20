# Payment types and payments

> **Superseded, 20 September 2026.** Written when payments went through the pesi gateway. The names,
> the shape and the sequence below no longer describe the code. The current plan and progress is
> [PAYMENTS_REBUILD_GAP_ANALYSIS_AND_PLAN.md](PAYMENTS_REBUILD_GAP_ANALYSIS_AND_PLAN.md). Kept for the
> reasoning that still holds.


**Scope:** the two things new-hodi calls "payment types" and "payments", brought across to this platform in
the shape this platform already has. A catalogue of channels money can arrive by, the accounts an
organisation collects into on those channels, and a first-class payments module — a list, a receipt, a
receive form and a void — over the money that already lands against bookings.
**Reference:** `../../new-hodi/hodi-b` (`com.hodi.payments`, `com.hodi.payments.types`) and
`../../new-hodi/hodi-f` (`pages/payments`, `pages/billing`, `components/forms/ReceivePaymentModal.vue`,
`components/forms/PaymentAccountModal.vue`).
**Touches:** one migration, a new `modules/payments` package, `infra/pesi`, `modules/bookings`, the enums and
seeder; on the client three pages, two modals, two services, the router, the nav and the booking drawer.

## 1. What is already here, and what is not

This platform sells homes; new-hodi lets them. The domain words do not match, but the shapes do:

| new-hodi | Here | Note |
|---|---|---|
| Estate (who collects) | Seller organisation *or* lending institution | A development is owned by one or the other, and the money follows the development. Platform-owned when neither. |
| Property (what an account is narrowed to) | Development | An account collects for every development an organisation owns, or for one of them. |
| Tenancy / occupation | Unit booking | The thing money is recorded against. |
| Invoice and allocations | — | No invoices. A booking has a schedule and a balance derived in `v_booking_balances`. Nothing to allocate to, so no allocations, no preview. |
| `rent_owed_before` / `rent_owed` | `balance_before` / `balance_after` | Snapshots on the receipt, for the same reason. |
| Bank, bank policy | — | There is no banks module. The catalogue carries a `provider_name` for display and nothing decides which channels an organisation may use. |
| STK push | Catalogue only | No outbound Pesi client exists here. STK channels arrive switched off and are not offered on the receive form. |
| `payment_types` (catalogue) | `pesi_super_types` | Already exists, half-built: Pesi codes only, no cash or cheque, no flags, no UI. Replaced. |
| `property_payment_types` (accounts) | `pesi_payment_methods` | Already exists, half-built: an owner and an account number, nothing else, no UI. Replaced. |
| `payments` | `booking_payments` | Already exists under the booking. Becomes the payments table. |

So this is less a port than a completion. The two Pesi tables were the first half of exactly this feature
and the migration folds them in rather than leaving two catalogues for one idea.

## 2. Decisions, and where they leave the reference

**Void, not reversal.** `booking_payments` corrects a payment with a second negative row. new-hodi voids the
row with a reason and keeps it. The reference's argument holds here too — a receipt records what a buyer
was told, and a void with a reason is a fresh fact beside it rather than an edit — and a payments *list*
reads far better without negative receipts in it. Existing reversal pairs are migrated: the original
becomes `VOIDED` carrying the reversal's reason, by and when; the reversal row is archived. The balance
view and the payments chart sum received rows only. Status is `1` received and `4` voided, which is this
platform's `STATUS_INACTIVE` and so every existing `status <> 5` guard still holds.

**One writer.** `PaymentService.receive` is the only place a payment row is created — the receive form,
the booking drawer and the Pesi notification all go through it — so the denormalised columns, the balance
snapshots, the channel stamp, the audit row and the receipt SMS happen once, whatever route the money took.
This is the reference's rule and the reason its receipt used to be sent for gateway credits and not for
cash.

**The account identifier stays the inbound match key.** new-hodi matches an inbound credit on a bank's
short code; this platform's Pesi contract sends `accountIdentifier`, resolved against the account number,
and `PesiIpnIT` pins that. So `account_no` stays unique across live rows and `short_code` is kept as an
optional, unique-when-present secondary reference the notification handler falls back to. The reference's
"required for inbound channels" rule is not carried.

**Paybill is optional.** The reference requires it on inbound channels because it prints on invoices.
There are no invoices here; a buyer quotes the unit's four-character code. Kept as a field, never
demanded.

**A method is a fact about the channel.** The receive form offers the coarse method (`CASH`, `CHEQUE`,
`BANK_TRANSFER`, `MOBILE_MONEY`, `CARD`, `OTHER` — the vocabulary already on the table) and, optionally,
the configured account the money came through. Choosing an account fixes the method from the catalogue
row's own `method` column, so "Lipa na KCB" cannot be recorded as a cheque. The reference derives this in
Java from the category; a column is the same rule with nothing to switch on.

**The one-time code reuses `OtpChallengeService`.** It is token-based rather than phone-keyed, so the
flow is: ask for a code (the server texts the *organisation's* contact number, not the caller's), hold
the challenge token, submit it with the code on save. A new purpose `PAYMENT_ACCOUNT` joins the CHECK
constraint. The platform's own accounts use a new `PLATFORM_SUPPORT_PHONE` configuration key. Withdrawing
an account takes no code, as in the reference: it sends money nowhere and is what somebody does the moment
an account looks tampered with.

**Scope follows the development, not the tenant column.** Payments and accounts cannot use `TenantScope`
for the reason developments cannot: a lending institution may own them outright. Payments are scoped by
the same four routes `DevelopmentVisibility.mine` uses, expressed over the payment's own owner columns and
a development subquery; accounts by owner alone, and platform-owned accounts only to platform staff.

**No bank policy, no events, no export enricher.** Nothing here rebuilds report figures on receipt (the
charts read views), so there is no `PaymentReceived` event. Exports are the reports module's, not this one's.

## 3. Schema — one migration

`V20260908090000__payment_types_and_payments.sql`

1. **`payment_types`** — the catalogue. `code` unique, `name`, `description`, `provider_name`,
   `pesi_provider_type` (null for cash and cheque), `category` (`CASH | CHEQUE | STK_PUSH | TRANSFER |
   VALIDATE`), `method` (the coarse method a payment through it records), `is_electronic`,
   `is_account_based`, `requires_short_code`, `sort_order`, status pair, generated `search_text`, audit
   columns. CHECKs: manual channels have no provider and gateway channels must; only STK and transfer may
   be electronic. Seeded with cash, cheque and the ten Pesi channels; everything with a gateway behind it
   arrives `INACTIVE` unless a migrated account already sits on it.
2. **`payment_accounts`** — replaces `pesi_payment_methods`, rows copied with their ids so
   `pesi_statements.payment_method_id` stays valid, then that column is renamed `payment_account_id` and
   re-pointed. Owner is `tenant_id` or `institution_id` or neither; `development_id` null means every
   development the owner has. `pay_bill_no`, `account_no`, `account_name`, `short_code`, copied
   `pesi_type` and `category`. Unique live `account_no`; unique live `short_code`; cash and cheque once
   per owner and development. CHECK: an account-based channel carries both halves of the account, a manual
   one neither.
3. **`payments`** — `booking_payments` renamed and widened: `development_id`, `unit_id`, cached
   `development_name`, `unit_label`, `buyer_name`, `buyer_phone`; `payment_type_id`, `payment_type_name`;
   `balance_before`, `balance_after`; `voided_at`, `voided_by`, `void_reason`; `statement_id`; generated
   `search_text`. Reversals folded into voids as §2 says; `reversal_of_id` and `reversal_reason` dropped;
   `amount > 0` enforced. Backfilled from the booking, unit and development.
4. **`unit_bookings.search_text`** — generated, so the receive form can find a booking by reference,
   buyer name or phone.
5. **Views** — `v_booking_balances` and `v_chart_payments_by_month` recreated over `payments` with
   `status = 1`.
6. **Tidy** — `pesi_super_types` and `pesi_payment_methods` dropped; the `BOOKINGS_PAYMENTS` permission
   row archived and its group links removed (the seeder creates its replacements at boot); the OTP purpose
   CHECK widened.

## 4. Backend

`modules/payments/`, flat like every other module here:

- `PesiChannel` — the enum from the reference, unchanged in spirit: the category decides behaviour, never
  a catalogue id.
- `PaymentType`, `PaymentTypeRepository`, `PaymentTypeService` — the catalogue. List, one, rename and
  reorder, switch on or off. Writes are platform-only; the permission is shared with account writes and
  cannot tell the two apart, so the service does.
- `PaymentAccount`, `PaymentAccountRepository`, `PaymentAccountService` — the accounts. List (scoped),
  one, assignable channels for an owner, offered accounts for a booking, duplicate check, request a code,
  assign, update, withdraw or restore. Which fields are required is read from the catalogue row.
- `Payment`, `PaymentRepository`, `PaymentService`, `PaymentQueryService` — money. Receive, void, list,
  detail, by receipt number, the balance panel for a booking, the booking picker, the methods on offer.
- `PaymentDtos`, `PaymentTypeDtos` — requests and responses, with `PagedDataRequest` subclasses for the
  lists.
- `PaymentController` at `/api/v1/payments`, `PaymentTypeController` at `/api/v1/payment-types`.

Elsewhere: `PesiIpnService` resolves the till from `payment_accounts` and records through
`PaymentService`; `BookingService` and `BookingController` lose their record and reverse paths and read
payments through the new query service; `ChartCatalogue` gates the payments chart on `PAYMENTS_VIEW`;
`OtpChallengeService` gains a purpose and an issue-to-phone method; `AppConstant` gains the payment
statuses and channel categories; `ConfigKey` gains the support phone.

### Module and permissions

`PAYMENTS` — core, admits the platform types, seller owners and managers, sales agents, agents and lender
staff (a bank financing a development records the money against it).

| Code | For | Platform-only |
|---|---|---|
| `PAYMENTS_VIEW` | the list, the receipt, the balance panel | |
| `PAYMENTS_RECEIVE` | record money | |
| `PAYMENTS_VOID` | reverse it, with a reason | |
| `PAYMENT_TYPES_VIEW` | the accounts and the catalogue | |
| `PAYMENT_TYPES_MANAGE` | set up, edit, withdraw an account | |
| `PAYMENT_CATALOGUE_MANAGE` | switch a channel on or off, rename it | yes |

`BOOKINGS_PAYMENTS` is retired: recording money is the payments module's act now, and one act should not
have two codes.

### Endpoints

```
GET  /payments                          list, paged, searched; developmentId, bookingId, paymentTypeId, method, status
GET  /payments/methods                  the coarse methods on offer
GET  /payments/bookings?search=         live bookings the caller may record against (the picker)
GET  /payments/balance/{bookingId}      what the booking owes, its schedule, what has been paid
GET  /payments/{id}                     the receipt
GET  /payments/receipt/{rrn}            the same, by receipt number
POST /payments                          receive
POST /payments/{id}/void                void, with a reason

GET  /payment-types                     accounts, paged, searched; ownerKind, developmentId, category, status
GET  /payment-types/{id}
GET  /payment-types/assignable          channels this owner could still be given
GET  /payment-types/offered/{bookingId} accounts money for this booking may be recorded through
GET  /payment-types/check-account       the duplicate check the form runs before a code is spent
POST /payment-types/otp                 texts the owner a code; returns the challenge token
POST /payment-types                     assign
POST /payment-types/update/{id}
POST /payment-types/{id}/status?active=
GET  /payment-types/catalogue
POST /payment-types/catalogue/update/{id}
POST /payment-types/catalogue/{id}/status?active=
```

## 5. Frontend

- `pages/payments/PaymentListView.vue` — Received and Voided tabs; search; development and payment-type
  filters; the reference's columns, with the voided tail on the voided tab; Open and Void row actions;
  Receive payment.
- `pages/payments/PaymentDetailView.vue` — the receipt: who paid, for which home in which development,
  how it arrived, the balance before and after, the references; the void banner; Void.
- `pages/payments/PaymentTypesView.vue` — Accounts and Methods tabs, as the reference lays them out.
- `components/payments/ReceivePaymentModal.vue` — booking picker (or the booking it was opened from), the
  balance panel, amount, came-through account, method, date, references, payer, note.
- `components/payments/PaymentAccountModal.vue` — three steps in `StepWizard`: Where, The account,
  Confirm (six-box `OtpInput`, code sent on arrival at the step).
- `BookingDrawer.vue` — the payments list in the new shape; Record a payment opens the shared modal; Void
  replaces Reverse.
- Types in `types/api.ts`, services `payments.ts` and `paymentTypes.ts`, three routes under `/app`, two
  nav entries.

## 6. Tests

- `PaymentServiceIT` — receiving stamps the balance before and after and the channel's name; a void puts
  the balance back and keeps the row; an account that does not reach the booking's development is refused;
  a cancelled booking is refused; a collaborator without unit rights is refused.
- `PaymentTypeServiceIT` — an inbound channel demands the account fields and a manual one refuses them;
  the same account twice on one channel is refused; a write without a code is refused; withdrawing needs
  none; a seller cannot switch a channel off.
- `BookingServiceIT`, `PesiIpnIT` — updated for the void model and the new till table.

## 7. Out of scope, and why

- **STK push initiation** — needs an outbound Pesi client this platform does not have. The channels are
  in the catalogue, switched off, so the day the client lands nothing here changes.
- **Bank policy** — needs a banks module.
- **Statements screen** — `pesi_statements` and its unmapped queue are unchanged and still have no UI; a
  separate piece of work.
- **Buyer self-service payment** — a buyer pays through Pesi against the unit's code; there is no
  in-portal pay screen to build until STK exists.

## 8. Delivered, and two things learned on the way

Everything in §3–§6 is implemented on `feature/payments-and-payment-types` in both repositories. The
migration ran cleanly against the development database; the backend suite passes (29 tests in the three
payment-related classes, the rest unchanged); the client type-checks and builds.

Two details settled during implementation rather than in the plan:

- **A gateway credit sends no receipt SMS.** The Pesi handler has thirty seconds and no outbound calls in
  it, so `recordFromGateway` writes the row, the audit entry and the snapshots but does not text the
  buyer. Hand-recorded payments do. This is the one deliberate exception to "one payment, one receipt".
- **An account on a switched-off channel is still offered on the receive form.** Switching a channel off
  stops new set-ups, not money already banking — which is what the catalogue's own status message promises,
  and what `PaymentAccountService.offeredFor` now does.
