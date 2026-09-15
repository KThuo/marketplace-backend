# One approval, given by the bank

Branch: `feature/coop-bank`, both repos.

---

## 1. What is wrong today

Publishing one development with three typologies takes **four separate submit/approve round trips** —
one `DEVELOPMENT/PUBLISH` and one `PROPERTY/PUBLISH` per typology — each with its own readiness gate
and its own maker/checker pair. Plus two setup gates before any of that: a selling organisation, and a
manual "Put on the marketplace" per unit type.

Palm Heights is stuck on the first of those: `selling_tenant_id` is null, which blocks *both* submitting
the project and listing a typology, with two different error messages.

The approve→publish coupling itself is already clean — `ApprovalService.applyDecision` →
`handler.onApproved` → `service.applyPublication`, one transaction. Nothing there needs inventing. The
friction is entirely in how many times it has to happen.

## 2. The model being asked for

> Create the development, generate units → the bank approves → it is published.
> The seller sells through the bank, so the bank approves any financial or property change.
> Any user created has to be approved by bank staff.

One checker for everything material, and that checker is the bank.

## 3. Publishing collapses to one approval

1. **The selling organisation stops being a question with one answer.** A tenant-owned project defaults
   `sellingTenantId` to the owning tenant on create. A bank-owned project genuinely has to say, because
   the answer is somebody else, so the gate stays for that case only.
2. **Typology cards are created on submit, not by hand.** Every priced unit type without a card gets one.
   The manual "Put on the marketplace" button stays for the odd case of listing one early, but nobody has
   to find it.
3. **Approving the development publishes its cards.** `applyPublication` already cascades to `UNIT` rows
   through `syncUnitRows`; it will cascade to `TYPOLOGY` rows too. Refusal cascades the same way.
4. **The readiness gate merges.** A typology card's own check wants a photograph, a description and a
   town. Checked once, at development submit, against the project and each card — so the bank is told
   everything missing at once rather than one round trip at a time.

Net: **one submit, one approval, everything live.**

## 4. The bank is the checker

`DevelopmentApprovalHandler.assertMayDecide` currently lets the owning tenant or the selling tenant
decide. That is a seller approving their own project, which is the thing the client does not want.
Deciding narrows to platform staff — which is the bank, since its people are platform staff.

**The one rule that does not bend**: `ck_approval_maker_checker` is a database CHECK, and the migration
that added it says a rule that can be switched off is not segregation of duties. So a bank user who
drafts a project still cannot approve it — another bank user must. That is not negotiable in code, and
it should not be.

## 5. A user cannot sign in until the bank says so

`UserService.create` returns the temporary password **synchronously in the response body**. That is the
one genuinely awkward part of gating it, and the awkwardness is worth naming: under a checker gate the
credential either has to be generated at approval time and shown to the checker, or stored on a pending
row — and a plaintext credential at rest is not something to add.

**Neither.** The account is created exactly as it is today, password and all, but `enabled = false`
until a bank user approves. The maker still gets the temporary password to hand over; the account simply
cannot sign in yet. No credential is stored readable, nothing about the existing flow changes, and the
gate is real because `enabled` is what the authentication path checks.

A new `USER` entity type and handler; approving sets `enabled = true`, refusing archives the account.

## 6. Financial changes — what I am not deciding alone

"Any financial change" could reasonably mean voiding a payment, recording a drawdown, changing where
money lands, or repricing a unit. They differ a lot in frequency: voiding a payment happens rarely and
is a good fit for a second pair of eyes; recording a drawdown happens weekly and a gate there is a queue
somebody will come to resent.

Payment **accounts** already have a control the others do not: an OTP to the *organisation's* registered
phone, which the service itself calls the highest-consequence configuration change in the product.

So §6 is deliberately left open in this document — see §8.

## 7. Out of scope

- Making maker/checker configurable. It is a database CHECK, on purpose.
- Approving KYC decisions, vendor onboarding, or partnership records (the last no longer exists).

## 8. Status

§3 and §4 (publishing collapses, bank is the checker) — **being implemented now**, because they are what
Palm Heights is stuck behind.

§5 (users) — designed above, not yet built.

§6 (financial changes) — **needs a decision from the client first**: which operations, given the
frequency trade-off. Gating a weekly drawdown behind a second person is a different product from gating
a rare void.
