# A development's money, in and out: who was paid, what for, and the statements that show it

**Date:** 25 September 2026 · **Repos:** `hodimp-b`, `hodimp-f` · **Status:** plan, for sign-off
**Next after this:** commissions — the bank's on a sale, and a sales agent's for bringing the buyer.

## 1. The requirement, in the client's terms

- A developer may fund the build with **their own money**. When they pay somebody, the platform records
  **the reason** and **the beneficiary**, and **what kind of beneficiary** they are: supplier, contractor and so on.
- The payment is **made through the app**. The bank debits the account, and a **maker and a checker inside
  the developer's own organisation** approve it.
- A developer, or an owner with the permission, **configures their own debit accounts**.
- **Only the bank collects.** Buyers pay into collection accounts that the bank configures for an owner,
  and owners cannot configure them. The bank sells on the owner's behalf, so it holds the money until every
  party is satisfied and the documents have transferred, and it can refund if there is a dispute.
- Whether the bank or the owner collects is **configured per development**. If the model changes later,
  the development is reconfigured, not rebuilt.
- When **the bank finances or manages** a development, it can manage that development's spending too: what
  is paid, and to whom.
- Each development gets **statements** of its money in and its money out. Slip validation receives customer
  money and **must never validate a debit**.

## 2. What the code does today

Checked in the code on 25 September 2026.

| Area | Today | Gap |
|---|---|---|
| Costs (`DevelopmentExpenditure`) | Category, optional phase, committed or spent, amount, date, free-text `payee`, a reference, notes, evidence, voiding with a reason | No beneficiary record, no payee type, and nothing links a cost to money that actually moved |
| Disbursements (`modules/disbursements`) | PesaLink out through Co-op. The bank confirms the destination name, a maker proposes, a checker approves, retries are safe, the status is enquired rather than resent | Payee is only `SELLER_ORGANISATION` or `OTHER`. It **always debits the platform's own account** (`sourceAccount()` → `findLiveForPlatform()`) and has no link to a development, phase or cost. Its own class comment says it "never touches a statement". |
| Transfer call (`CoopTransferService.send`) | Takes the account to debit as `fromAccount` | None. A developer's own account can be passed in; only the choice of source changes. |
| Accounts (`payment_accounts`) | `kind` `COLLECT`, `SEND` or `ENQUIRY`, with an owner and an optional `development_id` | Nothing stops an owner creating a `COLLECT` account when `payments.collection.scope` allows it, and that setting is **global**, not per development |
| Money in (`coop_statements`) | Bank notifications for credits only, matched to bookings, with attach and set-aside by hand. Debit notifications are acknowledged and discarded. | Nothing rolls it up per development as a statement |
| Slip validation | Looks up **unplaced credits** in `coop_statements` and applies one to a booking | None. It is credit-only by construction, and it must stay that way. |
| Bank involvement | A development is owned by a tenant (developer) **or** an institution (bank), and banks grant developers rights as collaborators. Financing is `facility_reference` and `facility_amount` as typed text. | No way to say "the bank manages this developer's spending" |

## 3. The design

### 3.1 Two settings on every development, set by the bank

| Setting | Values | Default | Who can change it |
|---|---|---|---|
| `collection_mode` | `BANK`: collected into bank-configured accounts. `OWNER`: the owner may configure the collection accounts. | `BANK` | Platform (bank) staff only |
| `spending_managed_by` | `OWNER`: the owning organisation's makers and checkers. `BANK`: the bank's. | `OWNER` everywhere. For an institution-owned development the owner already is the bank. | Platform (bank) staff only |

- The side that doesn't manage spending still **reads** everything: the statements, the beneficiaries, and
  every payment with its approvals. The bank lending on a developer-managed project sees every shilling,
  and the developer sees what a bank-managed project spent.
- `collection_mode` replaces the global `payments.collection.scope` for developments. The global setting
  still governs a plain house listing, and it becomes the default for a newly created development.
- Every change is audited with who made it, when, and the before and after values. Changing a setting never
  touches accounts or money that already exist. It only changes who may do what from then on.

### 3.2 Beneficiaries (new: `beneficiaries`)

The people and companies a development pays. They belong to the **owning organisation**, so they can be
reused across that owner's developments.

- **Type:** `SUPPLIER`, `CONTRACTOR`, `SUBCONTRACTOR`, `CONSULTANT` (architect, quantity surveyor, engineer),
  `PROFESSIONAL_FEES` (legal, valuation), `UTILITY`, `GOVERNMENT` (county approvals, NEMA, NCA), `LABOUR`,
  `LANDOWNER`, `OTHER`. The list lives in the database, like the cost categories, so the bank can add a type
  without a deploy.
- **Fields:** name, type, KRA PIN (optional, where the type expects one), contact person, phone and email,
  and the payout destination (bank code and account number).
- **Name confirmed at registration** using the same account-validation enquiry disbursements use. The
  bank-confirmed name is stored and shown beside the typed one. A beneficiary whose account can't be
  confirmed is saved as `UNVERIFIED` and **cannot be paid** until it is.
- **Maker-checker on a new beneficiary and on any change to where they are paid.** Changing a payee's
  account is how money gets diverted, so it needs a second person, as a payment does.
- Beneficiaries are deactivated, never deleted. A payment always keeps the details as they were when it
  was made.

### 3.3 Debit accounts (existing `payment_accounts`, `kind = SEND`)

- Configured by whoever manages spending: the owner's staff with the new `DEBIT_ACCOUNTS_MANAGE` permission,
  or the bank's staff. A second person approves, through the existing approval handler.
- Scoped to the owner, and optionally to one development. A development-scoped account is only offered on
  that development; an owner-level one is offered on all of theirs.
- **A `SEND` account can never collect**, and **an owner can never create a `COLLECT` account on a
  development whose `collection_mode` is `BANK`**. Both are enforced in `PaymentAccountService` and backed by
  a check constraint, not just hidden on the form.

### 3.4 Paying from a development (the disbursement engine, extended)

The existing disbursement engine is **extended, not duplicated**: its bank name-check, SENDING-state
protection and status enquiry are the parts that are hard to get right. New columns on `disbursements`:

`development_id`, `phase_id`, `cost_category_id`, `beneficiary_id`, `beneficiary_type` (a snapshot),
`owner_tenant_id` / `owner_institution_id`, `invoice_reference`, `managed_by` (`OWNER` | `BANK`), and a
`source_account_id` that may now be **an owner's `SEND` account** rather than always the platform's.

**The flow**

1. **The maker proposes:** development, beneficiary, amount, reason (required), phase (optional), cost
   category (required), invoice or order reference, and evidence (the invoice, certificate or LPO). The
   debit account is chosen from the ones that development may use.
2. **The destination is re-checked with the bank at the moment of proposal**, and the confirmed name is
   what the checker sees. If it no longer matches the beneficiary's confirmed name, the proposal is refused
   with both names shown.
3. **The checker approves or refuses.** They must be a different person in the organisation that manages
   spending on that development, with `DISBURSEMENTS_APPROVE`. Nobody can check their own proposal, which is
   already the rule and is enforced in the database.
4. **The bank debits the chosen account** (`CoopTransferService.send(…, fromAccount = the chosen SEND
   account, …)`), and the existing callback and status enquiry settle the payment.
5. **When it succeeds, the cost is recorded automatically:** a `SPENT` expenditure against the same
   development, phase and category, pointing back to the disbursement. The payment and the cost are one
   fact, entered once. A failed or refused disbursement creates no cost.
6. **Commitments:** a `COMMITTED` cost (an LPO or a contract sum) can name a beneficiary. Payments against it
   draw it down, and the development shows committed, paid against it, and outstanding.

**Paid outside the app.** A cost can still be recorded by hand, for cash or a cheque. It now names a
beneficiary (or a one-off payee) and a type, is marked **"Manual Entry"**, and carries its
evidence. The statements show these under their own heading, so a reader never mistakes a claim for a debit
the bank made.

**Who may do what:**

| Permission | Who holds it |
|---|---|
| `DISBURSEMENTS_MAKE` | The managing side's makers |
| `DISBURSEMENTS_APPROVE` | The managing side's checkers |
| `DEBIT_ACCOUNTS_MANAGE` | Whoever may configure debit accounts |
| `BENEFICIARIES_MANAGE` | Whoever may add or change payees |
| `DEVELOPMENT_FINANCE_SETTINGS` | Bank staff only |

The existing bank-to-seller payout (releasing sale proceeds) stays exactly as it is. It is the bank paying out
of *its* account and is marked `managed_by = BANK` with no development spending attached.

### 3.5 Statements

**Two statements per development, plus a summary.** Each is read from its own source, and they are only
combined on screen:

| | Source | What it lists |
|---|---|---|
| **Money in** | `coop_statements` credits matched to this development's bookings, plus refunds out of them | Date, bank reference, buyer (as the bank named them), unit and booking, amount, and how it was placed (automatic, slip, by hand) |
| **Money out** | Disbursements on this development, plus costs recorded by hand | Date, our reference and the bank's, beneficiary and **type**, reason, phase, category, invoice reference, the debit account (masked), maker and checker, state, and "Paid through Hodi" or "Manual Entry" |
| **Summary** | Both of the above | Totals in and out for the period, by category, by beneficiary type and by phase, and budget against committed against spent |

- **Filters:** period, phase, category, beneficiary, beneficiary type, and state. **Exports:** CSV now and
  PDF with the existing report machinery. Each export carries the development, the period, who ran it and
  when.
- **Visibility:** everyone who can read the development's finance sees its statements. A buyer sees none.

### 3.6 Keeping money in and money out apart

- **Separate at write time.** Debits never enter `coop_statements`. Money out lives only in `disbursements`
  and `development_expenditures`. Slip validation, the matcher and "attach to a booking" read only
  `coop_statements`, so a debit is *structurally* unreachable from them, not filtered out. A test asserts
  that slip validation cannot see a disbursement's references.
- **References cannot be confused.** Our disbursement references (`DB…`) and the bank's reference on a
  debit are stored as money out only. Slip validation refuses a reference that belongs to a disbursement,
  with "that is a payment out, not a payment received", instead of "not found".
- **Account kinds cannot be swapped.** A `SEND` account cannot collect and a `COLLECT` account cannot be
  debited from, enforced in the service and the schema.
- **Nothing is edited in place.** A mistake in either direction is voided with a reason and re-entered. The
  statements show voided rows struck through, never removed.

## 4. Build order

1. **The two settings on each development**, the rule that owners can't create `COLLECT` accounts under
   `BANK`, and the migration that sets existing developments from the current global setting.
2. **Beneficiaries:** table, types, bank name-check, maker-checker on create and on account change, and a
   seller-side screen.
3. **Debit accounts:** `SEND` accounts owned by an owner or development, with the permission and approval.
4. **Paying from a development:** the disbursement extension, the development payment form, the checker's
   queue, and the automatic `SPENT` cost. Hand-recorded costs gain a beneficiary and type.
5. **Statements:** money in, money out and the summary, with filters, CSV and PDF, on the development's
   finance tab for both sides.
6. **Tests:** integration tests for each rule in 3.1, 3.4 and 3.6, including a checker from the non-managing
   side refused, a checker approving their own proposal refused, a failed debit creating no cost, and slip
   validation blind to money out.

## 5. Not in this piece

- **Commissions** (the bank's on a sale, and agents' for bringing buyers) are the next piece. They are a
  money-out line of their own, so this piece names `managed_by` and a payee type they can reuse.
- M-Pesa (B2C) payouts, for example for casual labour. The beneficiary model has room for an M-Pesa
  destination, but only PesaLink to bank accounts is built now.
- Retention held back from contractor payments.
- Budget approvals and variation orders.

## 6. To confirm with the bank before step 4 goes live

- **The debit mandate.** The platform's Co-op credentials must be allowed to debit an **owner's** account,
  not only the platform's. The API call already supports it (`fromAccount`), but whether the bank permits
  it for a customer account is a banking arrangement, not code.
- **Approval limits.** Whether any amount needs a second checker, or a bank checker on a developer-managed
  development.

## 7. Progress

### Phase 1 — done (28 September 2026)

**Backend**
- Migration `V20260928090000`: `developments.collection_mode` and `spending_managed_by`, each with a check
  constraint. Existing developments take their collection mode from the platform-wide setting.
  `payment_accounts.configured_by_bank` is added, true for the platform's own accounts.
- `DevelopmentMoneySettingsService` with `GET`/`POST /developments/{id}/finance/settings`. Saving needs the
  new platform-only `DEVELOPMENT_FINANCE_SETTINGS` permission and is audited. A new development takes the
  platform-wide setting as its collection default.
- `PaymentAccountService`:
  - Under `BANK`, only bank-configured accounts collect for a development. An owner can't set one up for it,
    and can't edit or withdraw one the bank configured.
  - The bank's staff can set up collecting accounts for any owner and any development.
  - An owner can set one up for an `OWNER` development even while the platform collects everything else.
  - Money-out methods are unchanged until phase 3.
- `DevelopmentVisibility.assertMayManageSpending` gates recording, voiding and attaching evidence to costs.
  Platform staff are not let through automatically: the side that doesn't manage spending reads only.
  Drawdowns are unchanged.

**Tests**
- `DevelopmentMoneySettingsIT` has 8 tests covering the collection and spending rules.
- `PaymentTypeServiceIT.platformStaffCannotStepAroundIt` became `theBankConfiguresForOwners`. It asserted the
  old rule, that the bank could not set up an organisation's account while the platform collected.
- Full suite: 486 tests, 0 failures.

**Frontend**
- `MoneySettingsCard` on the finance tab: both sides read it and the bank's staff can change it.
- The cost controls follow `mayManageSpending`, with a note explaining why they are missing.
- `PaymentAccountModal` always asks the bank's staff "whose account". An owner can choose "every development"
  only when the platform-wide setting allows it, and is told when the bank collects for all of their
  developments.
- `vue-tsc` and `npm run build` pass.

**Not verified in a browser.** The screens need a signed-in bank user and a signed-in owner, and the dev
bootstrap login is stale.

**Behaviour changes to know about**
- A bank officer can no longer record costs on a developer-managed project unless it is switched to `BANK`.
- A developer who is a collaborator on a bank-owned project can no longer record its costs, because the owner
  (the bank) manages spending there.
- An owner can no longer withdraw an account the bank configured, or one collecting for a bank-collected
  development. Before, anyone could withdraw any account of theirs without a code.

### Phase 1 review (28 September 2026)

Read back against §3.1 after it was built. Confirmed as matching the plan: the two settings and their
defaults, bank-only changes, audit with before and after, the migration taking existing developments from the
platform-wide setting, the platform-wide setting still deciding for a house, and the non-managing side reading
everything. Found and fixed:

- **Bug.** On a bank-owned development under `OWNER` spending, a platform administrator with no institution
  on their profile was refused as "the wrong side". The owner of such a project *is* a bank, and a bank's
  staff are the platform's, so they are the owner's side there. Fixed in `DevelopmentVisibility`, with a test.
- **Removed a rule the plan never asked for.** A bank edit of an owner's account silently made it "the
  bank's", locking the owner out of their own account. An account is the bank's only if the bank set it up.
- **The list now says which accounts are the bank's** (`configuredByBank`, `mayChange` on every row) and
  offers an owner no Edit or Withdraw on them, instead of a 403 after the click.
- **The approvals checker is told who set an account up**, since that decides where it may collect.
- **An owner whose developments are all bank-collected** gets a message saying so, in place of one telling
  them to change a platform setting they cannot see.

Two things the plan leaves open, for the client:

- **Should changing the settings go through the approvals queue?** Switching a development to `OWNER` lets
  the owner's already-approved accounts start collecting there. Today it is one bank user, audited. A second
  bank user's approval would match how accounts themselves are handled.
- **Should a partner lender's staff be platform staff?** Today every institution's staff are, so "the bank"
  in these rules means any institution. If a partner lender should manage only its own projects' spending
  and never another's, that needs a distinction the actor model does not yet make.

Suite after the review: 489 tests, 0 failures. The screens are still unverified in a browser.

### Phase 2 — done (28 September 2026)

**Backend**
- Migration `V20260928120000`: `beneficiary_types` (ten seeded, a platform list like cost categories) and
  `beneficiaries`, owned by exactly one organisation, with a generated `search_text` and the `pg_trgm` guard.
  One owner may register one account once (`uk_beneficiary_payout`); another owner may register the same one.
- `modules/beneficiaries`: `BeneficiaryService` (register, edit, verify, deactivate, the payable picker),
  `BeneficiaryTypeService`, two controllers, and `BeneficiaryApprovalHandler`.
- **The bank's name.** `PayoutAccountCheck` asks Co-op who holds the account through the same
  account-validation channel a disbursement uses (`CoopPayoutAccountCheck`), at registration and again whenever
  the account changes. The confirmed name is stored; an account the bank cannot confirm is kept `UNVERIFIED`
  with the bank's reason, can be asked about again, and is never payable.
- **Maker/Checker.** A new beneficiary, and any change to its bank code or account number, is written at
  `STATUS_NEW` and goes to the approvals queue (`BENEFICIARY`), decided by somebody in the same organisation or
  the bank's staff holding `BENEFICIARIES_APPROVE`. Contact details, the type and the notes save directly.
  `payable` is live **and** verified: approved-but-unconfirmed is not payable.
- Permissions: `BENEFICIARIES_VIEW`, `BENEFICIARIES_MANAGE`, `BENEFICIARIES_APPROVE` (PAYMENTS module, held by
  owners' staff and the bank's), and platform-only `BENEFICIARY_TYPES_MANAGE`.
- Bank codes are stored padded to four digits, as disbursements store them. The first version compared the
  typed `11` against the bank's `0011`, missed a duplicate, and hit the unique index instead.
- `BeneficiariesIT`: 8 tests. Full suite: 497, 0 failures.

**Frontend**
- **Beneficiaries** (Money group): list with search, kind, and the bank's answer as filters; the bank's staff
  narrow by organisation. Each row shows the account, who the bank says holds it, whether it is confirmed,
  whether it is approved, and whether it can be paid. Edit, "ask the bank again", deactivate.
- `BeneficiaryModal`: name, kind, KRA PIN, contact, bank and account, "ask the bank who holds it", and a
  warning when an edit changes the account and so needs a checker.
- **Beneficiary types** (Platform group), mirroring cost categories. The approvals screen labels the new
  entity. `vue-tsc` and `npm run build` pass.

**Added after review (28 September 2026)**
- **Shared beneficiaries.** A supplier several developers buy from is registered once by the bank with no
  owner (`V20260928150000` relaxes the owner check). Every organisation sees it beside its own and may pay it;
  only the bank changes it, and only the bank's checker approves it. An owner registering an account the bank
  already shares is refused by name — no private copies of a shared supplier. `ownerKind` gains `SHARED`.
- The form is a three-step wizard (who, contact, paid into) and the phone field is `AppTelInput`.

**Not built, deliberately**
- The bank registering a beneficiary for an *institution* (a bank-owned development) has no form: the
  modal offers seller organisations only, because the auth store does not expose the caller's institution.
  The API accepts `institutionId`; the form is a phase 4 concern, when bank-managed payments need it.
- M-Pesa destinations, per §5.

**Not verified in a browser**, as with phase 1.

### Phase 3 — done (28 September 2026)

No migration: a debit account is a `payment_accounts` row on a `SEND` method (PesaLink), which the schema
already held. What changed is who may set one up and what a development may pay from.

**Backend**
- `DEBIT_ACCOUNTS_MANAGE` (PAYMENTS module, held by owners' staff and the bank's). Setting up a way of
  *collecting* still needs `PAYMENT_TYPES_MANAGE`; setting up an account to *pay from* needs this one. The
  account endpoints admit either, and `PaymentAccountService` decides per method which it needs.
- A paying-out account never answers to the collection setting: an organisation whose buyers pay the bank
  still pays its contractors from an account of its own. Scoped to a development, it is set up by whichever
  side manages that development's spending (`assertMayManageSpending`); the form's development list follows
  the method chosen. The platform's own `SEND` account stays the bank's.
- `debitAccountsFor(development)`: the owner's live `SEND` accounts that reach it, plus the platform's where
  the bank manages spending, and never a collecting account. Exposed as
  `GET /developments/{id}/finance/debit-accounts`; phase 4's payment form picks from it.
- Maker/Checker and the one-time code are unchanged: the existing account approval covers it.
- `DebitAccountsIT`: 4 tests. One bug found by them: `Map.of(...).get(null)` threw for an account with no
  development.

**Frontend**
- The account form asks "It pays for" or "It collects for" by method, reloads the developments on offer when
  the method changes, and says what approval means for a paying account. The accounts list says "pays out"
  or "collects" on each row; either permission opens it.
- The finance tab gains a **Pays from** card: the accounts this development may pay from, or why there are
  none and who can fix it.

**Left as it was, worth knowing**
- A debit-account manager needs `PAYMENT_TYPES_VIEW` to reach the Payment types screen at all; owners' system
  groups hold it already.

