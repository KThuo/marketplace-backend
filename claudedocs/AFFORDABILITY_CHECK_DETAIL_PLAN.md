# An affordability check can be opened, and it shows its working

**Date:** 23 September 2026 · **Repos:** `hodimp-b`, `hodimp-f`, branch `feature/coop-bank`

## What was wrong

- **A row with no page behind it.** The workspace's Affordability list said "in the market for 9.2 million"
  and stopped. There was no way to see how the figure was arrived at.
- **"mock" in the Check column.** The list printed the assessor's stored code (`MOCK`) in lower case beside
  every reference. The code is the name of the platform's own rules engine, not a stub, and a display label
  ("Hodi indicative rules") already existed on the row but was not used.

## What "mock" actually is

`MockAffordabilityProvider` is the platform's own rules, written down and checkable by hand: net income
after obligations, times the DTI ceiling, read back through the annuity at the rate over the term, capped by
the deposit rule, plus the deposit. When a buyer names a mortgage product, that product's rate, term band,
LTV, DTI ceiling and minimum income are the terms used. When no product is named, the configured default rate
(13.5%) and ceiling (40%) apply. The name is a misnomer from the plan that anticipated a credit-decision
service from the bank replacing it as the *authority*; the arithmetic is real.

## What changed

1. **One check, in full, for the platform.** `GET /api/v1/affordability/{reference}` under
   `AFFORDABILITY_VIEW` returns the same response the buyer saw — outcome, figures, the derivation line by
   line, the terms table, product and listing — kept as it was on the day, not re-run. The response type has
   no user, name or contact field, and the query joins to none: the platform reads a calculation, not a
   household. A test asserts the working comes back and that no component of the response names a person.
2. **A detail page.** `/app/affordability/:reference`: the answer strip (could borrow, repayment, share of
   income against the ceiling, rate and term, what the home needs), the derivation, the terms table, what it
   was computed on (product, listing), the disclaimer, and a note that who ran it is not shown. Checks saved
   before the derivation was stored (the four existing rows) show the figures that went in and the
   assumptions instead, rather than a re-run working the buyer never saw.
3. **The list.** The reference links to the page. The sub-line shows the basis — the product's name, or
   "Hodi indicative rules" — instead of the provider code. The privacy note says where the working is.

## What true, bank-authoritative figures need

Not in this change; for the record:

- **A credit-decision API from the bank** (the plan's "OCP microservice"): request contract (income,
  obligations, deposit, term, product, applicant identifiers if any), response contract (decision, limits,
  reasons), authentication and an environment to test against. A second `AffordabilityProvider` implements
  it; the configuration row `affordability.provider` selects it; the rules engine stays as the fallback when
  the bank is unreachable.
- **Bureau and scoring inputs** the rules engine does not have: actual liabilities, credit history,
  employment verification. Those are the bank's, and they arrive with an application, not a calculator.
- **A rename**, if the word itself is the objection: provider code `MOCK` → `RULES` (constant, class,
  configuration default, and a migration over `affordability_checks.provider`). Cosmetic in effect; nothing
  in the arithmetic changes.

## Progress

| Step | State |
|---|---|
| Endpoint, service, repository, test | **Done, 23 September** |
| Detail page, route, API call | **Done, 23 September** |
| List links and basis label | **Done, 23 September** |
| Bank credit-decision provider | Blocked on the bank's API contract |
| Rename MOCK → RULES | Not started; awaiting the decision |
