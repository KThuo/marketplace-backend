# Affordability against a real mortgage, and a mortgage somebody can create

**The ask (17 September 2026):** be deliberate. A buyer should pick one of the bank's mortgages, fill in
the rest, and get an answer computed against *that* product's terms — and "how it was calculated" should
show the actual working rather than something that reads like a mock. First, though, a mortgage product
has to be creatable at all.

---

## 1. What is there already, and what is actually missing

More exists than the symptoms suggest. Worth stating plainly so the work is the gap and not a rewrite.

| Piece | State |
|---|---|
| `MortgageProduct` entity | **Complete.** Rate and rate type, term bounds, amount bounds, max LTV, minimum deposit, processing fee, insurance, other-fees note, minimum income, max DTI, eligibility notes, required documents, publish state. |
| Product API and screens | **Complete.** List, find, create, update, publish, withdraw, deactivate; `MortgageProductListView` and a thorough `MortgageProductEditView` covering every field above. |
| Public catalogue | **Complete.** `/public/mortgage-products/search`, published-and-live only, cheapest first. |
| Affordability engine | **Real arithmetic, not a stub.** `MockAffordabilityProvider` does net income → DTI ceiling → annuity read backwards → maximum price, with three outcomes and a marginal band. |
| Affordability against a *product* | **Missing entirely.** |

### 1.1 Why a product cannot be created

`MortgageProductService.create` refuses any caller with no `institutionId`, and its own comment says why:
platform staff have none, and `SaveProductRequest` has nowhere to name the bank. So the only people who
could create a product are a bank's own staff — and on this platform the bank *is* the operator, signed in
as platform staff. The same shape as the listing-edit lockout fixed earlier today.

`loadOwn`, which guards update, publish, withdraw and deactivate, refuses platform staff for the same
reason. So even a product created another way could not be published.

### 1.2 Why the calculation ignores the product

`AffordabilityService` line 126:

```java
BigDecimal rate = mock.defaultRate();
```

Every check, for every buyer, at the configured default rate. `AffordabilityRequest` carries no product,
`AffordabilityProvider.Request` carries a bare `annualRate`, and nothing in the chain knows a product
exists. The response does carry `options` — what the money would buy at each bank — but those are computed
*after* the answer, from a figure the products had no part in.

So the product's own terms are all unused: its rate, its term bounds, its maximum loan-to-value, its
minimum deposit, its maximum DTI, its minimum income, its fees.

### 1.3 Why the working reads like a mock

Two separate things, both fair.

- **The panel.** `working` is a flat `Map<String, Object>` rendered by de-camelising the keys and printing
  the raw value: "max loan amount / 4875000.00". That is a debug dump, not an explanation.
- **The word.** `provider` is the string `MOCK` (`AppConstant.PROVIDER_MOCK`), and it is shown on the
  affordability list. A client reading "MOCK" against their own numbers concludes the platform is not real.

---

## 2. What we are building

### A — A product the bank can actually create

1. `SaveProductRequest` gains `institutionReference`. Bank staff may omit it (theirs is implied); supplying
   a *different* one is refused, because a bank filing a product under another bank's name is not an
   oversight feature.
2. `create` resolves the institution: the caller's own, or the named one for platform staff. Neither
   available → a 400 that says to name the bank, not a 403 that says they are the wrong person.
3. `loadOwn` becomes `loadManageable`: own institution, or platform staff — the same oversight rule the
   listing screens now use.
4. `GET /mortgage-products/institutions` — id and name of the live banks, for the picker. There is no banks
   controller today; this is three lines rather than a module.
5. `MortgageProductEditView` gains a bank select, shown only to a caller with no institution of their own,
   preselected when there is exactly one.

### B — The calculation, against the chosen product

1. `AffordabilityRequest` gains `productReference` (optional — the calculator still answers without one,
   because somebody who has not chosen a bank yet still deserves a number).
2. `AffordabilityProvider.Request` gains a `ProductTerms` record: rate, rate type, term bounds, max LTV,
   minimum deposit percent, max DTI, minimum monthly income, processing fee percent, insurance percent, and
   the names of the product and its bank. Null when no product was chosen, and the configured defaults then
   stand in — which is exactly today's behaviour, preserved.
3. The provider applies them, in this order, and every one of them is a line in the working:
   - **Minimum income.** Below it, the answer is NOT_ELIGIBLE and says so by name.
   - **Term.** Clamped into the product's band, and the clamp is reported rather than silent.
   - **Rate.** The product's, not the configured default.
   - **DTI ceiling.** The product's `maxDtiPercent` where it has one, else the configured default.
   - **Maximum loan.** The annuity read backwards, then capped by **max LTV** against the price where a
     property is in play.
   - **Deposit.** Below the product's minimum percent, the shortfall is named.
   - **Fees.** Processing and insurance as cash, so "what do I need on the day" is answerable.
4. The response carries the product it was computed against, so the screen can say so and a stored check
   still makes sense a month later.

### C — Working that reads like an explanation

`Decision` gains `List<Step>`: `{ label, detail, formula, value, kind }`. The stored `providerPayload` map
stays exactly as it is — its documented job is to keep the assessor's own response verbatim — and the steps
are what the screen renders.

The panel becomes a numbered derivation: what was done, the arithmetic in the buyer's own figures, and the
running total; then the checks (LTV, deposit, income) as pass/fail lines; then the fees.

### D — The word "mock" leaves the interface

The stored code stays `MOCK` — it is in every existing row and in a configuration value, and renaming it
would be a migration for a caption. What changes is what anybody sees: the provider gains a display name
("Hodi indicative rules", or the bank's own name once OCP answers), used in the calculator and in the
affordability list.

---

## 3. Decisions worth recording

- **A product is optional, not required.** Forcing a choice before any number appears would make the
  calculator useless to somebody who has not shopped yet. No product = today's behaviour, labelled as the
  platform's own indicative rules.
- **The product's terms bind, the household's inputs do not stretch.** Where the product is stricter than
  the platform default (a lower DTI, a higher deposit), the product wins. Where it is looser, it also wins —
  it is the bank's own product and the bank's own risk.
- **LTV caps the loan, it does not change the price.** A household that can service more than 90% of the
  price is told the loan is capped and the deposit has to make up the difference, rather than being shown a
  smaller house.
- **Fees are shown, never netted off.** Adding them into the loan would quietly change the answer; naming
  them as cash needed on completion is what a buyer actually has to plan for.
- **Nothing here becomes a credit decision.** Every figure stays indicative and every screen goes on saying
  so.

## 4. Files

Backend: `FinanceDtos`, `MortgageProductService`, `MortgageProductController`, `AffordabilityProvider`,
`MockAffordabilityProvider`, `AffordabilityService`, `AffordabilityCheck` (product columns), one migration.
Frontend: `finance.ts`, `types/api.ts`, `MortgageProductEditView.vue`, `AffordabilityView.vue`, plus a
`DerivationPanel` for the working.

## 5. Checks

- A product can be created, published and edited by platform staff, and a bank's staff still cannot file
  one under another bank.
- A check run with a product uses its rate, its ceiling and its term band, and says so.
- A check run without one is unchanged from today.
- The derivation's steps reconcile to the headline figures — asserted in a test, not by eye.
- `mvn package` green; `npm run build` green.

---

## What was built, and what it was checked against

All of A–D, with one change of course: **there is no institution picker.** Co-operative Bank is the only
institution on this platform — it operates the marketplace rather than competing on it — so a product files
itself under the one that exists and no screen asks. If a second is ever onboarded, creation stops guessing
and says so rather than filing under whichever row sorted first.

### Proven against the running application

`POST /public/affordability/estimate` with 250,000 income, 30,000 of obligations, 2,000,000 down over 240
months, against `MPABABF6CE4X` (Home Owner Mortgage, 13.25%, 45% ceiling, 10% deposit, 1.5% arrangement):

| | |
|---|---|
| Rate used | **13.25%** — the product's, not the configured 13.5% default |
| Ceiling | **45%** — the product's `maxDtiPercent` |
| Repayment allowed | 220,000 × 45% = **99,000** |
| Loan that repayment buys | **8,323,309** at 13.25% over 240 months |
| Maximum price | 8,323,309 + 2,000,000 = **10,323,309** |
| Arrangement fee | 8,323,309 × 1.5% = **124,850**, named as cash rather than borrowed |
| Steps rendered | **10**, each with its formula |

### The one thing the screens made obvious afterwards

Every option under the answer read "Above what your income carries" while the answer above it said the
opposite. `FinanceMatchService.cost` was putting the deposit back to the *product's minimum* — so a
household told they could reach 10.3M with 2M down was then shown the same mortgage, at the same price,
borrowing 9.3M and failing. The buyer's own deposit is now the floor's floor: the chosen product's row is
now exactly the headline (2,000,000 down, 8,323,309 borrowed, 99,000 a month, within income), and Plot
Purchase Plan correctly does not fit because it wants 30% down on that price.

Verified in the browser on the public calculator, and by 9 tests in `AffordabilityAgainstAProductIT`
including the LTV cap and the term clamp. Whole suite 304 green.
