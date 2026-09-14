# Co-op Bank as the platform — removing the institution axis

Branch: `feature/coop-bank` (both repos), cut from `feature/payments-and-payment-types`, which stays
as the parked copy of the multi-institution build.

---

## 1. What changed in the requirement

The platform was built on the assumption that **a lending institution is a third party**. Sellers
list property, several banks partner with several sellers, and a bank's staff read the portfolios of
the sellers it has partnered with. That is what `lending_institutions`,
`tenant_lender_partnerships` and the `institution_id` column on fifteen tables exist to express.

The client is Co-op Bank, and they already lend on mortgages themselves. There is no marketplace of
competing lenders to model, because there is only one lender and **they own the product**.

So the change is not "delete lending". It is:

| Before | After |
|---|---|
| Many lending institutions, each a separate organisation | One lender: Co-op Bank |
| A bank's staff are `institution_id`-scoped | Co-op's staff **are** platform staff — `SUPER_ADMIN` and below |
| A bank sees sellers it has an approved partnership with | The platform already sees everything, by definition |
| Mortgage products belong to an institution | Mortgage products belong to the platform |
| Lending is one participant's feature | Lending is the product |

**Everything lending-related stays**: mortgage products, affordability and the OCP provider,
valuations, the valuer panel, their modules, permissions, config keys, tables and screens. They are
not being deleted — they are changing owner, from "whichever institution" to "the platform, which is
Co-op".

What goes is the **institution as a separate organisation**: the second ownership axis.

---

## 2. Why this is not a small change

`institution_id` is not a lending column. It is one half of a two-owner model, and the schema says
so — `developments`, `auction_lots` and `valuation_requests` each carry

```sql
CHECK ((institution_id IS NOT NULL)::int + (tenant_id IS NOT NULL)::int = 1)
```

— exactly one owner, never both, never neither. Nine more tables carry the looser
`CHECK (tenant_id IS NULL OR institution_id IS NULL)`.

It reaches into security:

| Where | What it does | File |
|---|---|---|
| Visible-tenant set | A lender's entire row visibility is the partnership table | `security/principal/PrincipalFactory.java:81-82` |
| Report/chart SQL | Second owner column in every owner-scoped query | `security/OwnerScopeSql.java:54-59` |
| Project visibility | Five separate institution branches in the Criteria API | `modules/developments/DevelopmentVisibility.java` |
| Payments | Owner resolution and scope | `modules/payments/PaymentScope.java`, `PaymentAccountService.java` |
| Principal | First-class field, and in the login payload | `UserPrincipal.java:55,102`, `AuthDtos.java:96` |
| Charts | `OWNER = List.of("tenant_id", "institution_id")` | `modules/analytics/ChartCatalogue.java:82` |

Roughly 200 call sites across 15 entities, the principal, the audit row, the approval workflow and
every report's `ownerScoped` flag.

**The risk is specific and worth naming**: `TenantScope` is the one choke point that decides which
rows a caller may read, and every organisation shares one schema. A mistake here does not throw — it
returns other people's data. That is why the staging below exists.

---

## 3. Approach — three stages, each shippable

The end state is the same either way. The staging is about being able to stop, test and deploy
between steps rather than landing 200 edits as one commit.

### Stage 1 — Co-op's people become platform staff (no schema change)

The behavioural change, with the columns left alone.

1. `UserTypeEnum`: `LENDER_ADMIN`, `MORTGAGE_OFFICER`, `CREDIT_ANALYST` move from
   `ACTOR_LENDER` to `ACTOR_PLATFORM`. This alone gives them unrestricted visibility, because
   `UserProfile.isPlatformActor()` is what `PrincipalFactory` reads — no partnership needed.
2. Rename them to what they now are: `BANK_ADMIN`, `MORTGAGE_OFFICER`, `CREDIT_ANALYST` keep
   their work; the *administrator* of an institution becomes an administrator of the platform.
3. `PrincipalFactory`: the partnership branch becomes unreachable for these types. Keep it for one
   release rather than deleting it, so a rollback is a one-line revert.
4. Stop offering institution ownership on new records: `DevelopmentService` no longer stamps
   `institutionId` from the caller.
5. Seed one `lending_institutions` row for Co-op so existing foreign keys stay satisfied, and
   re-point every existing institution-owned row at it.

**Testable**: a mortgage officer signs in and sees every seller's portfolio without a partnership.
**Revertible**: three enum values and one branch.

### Stage 2 — the product surfaces move to the platform

6. `mortgage_products.institution_id` is `NOT NULL REFERENCES lending_institutions`. Make it
   nullable, with null meaning "the platform's" — the same shape a platform-owned development has.
7. `MORTGAGE_PRODUCTS`, `VALUATIONS`, `VALUER_PANEL`, `AFFORDABILITY` module audiences: platform
   types only, since that is now who configures them.
8. Partnerships: the module and its screens go. `PARTNERSHIPS_*` permissions are removed from the
   templates and the module is archived rather than dropped, so existing rows stay readable while
   nothing new is written.
9. `DashboardService.lenderCards` becomes the platform's lending view rather than one institution's.

### Stage 3 — drop the column

Only once stages 1 and 2 have run in an environment and the queries have been watched.

10. Relax the XOR constraints to "tenant_id present, or platform-owned".
11. Drop `institution_id` from the fifteen entities and their tables, `OwnerScopeSql`'s second
    column, `ChartCatalogue.OWNER`, `PaymentScope`, `DevelopmentVisibility`, the principal, the
    login payload and the audit row.
12. Drop `tenant_lender_partnerships` and `lending_institutions`.

---

## 4. Re-brand (config and visible strings)

Code identifiers stay — `com.hodi`, the cookie names and the Redis prefix do not move, because
renaming them invalidates every live session and cache for no user-visible gain.

| What | Where |
|---|---|
| `COMPANY_NAME`, `COMPANY_EMAIL` defaults | `enums/ConfigKey.java:145-150` |
| SMS sender id, email domain | `ConfigKey.java:178,183`, `infra/notify/EmailSender.java:40` |
| Theme colours → Co-op green, logo/favicon URLs | `ConfigKey.java:110-141` (data, set from Settings) |
| Email/SMS template bodies naming "Hodi Market Place" | `ConfigKey.java:329,348-353` |
| The sign-in panel's three-party pitch | `hodimp-f/src/layouts/AuthLayout.vue` — "Sellers / Lenders / Buyers" is now "Sellers / Co-op / Buyers" |
| `pom.xml` name and description | Cosmetic, safe |

---

## 5. Decisions that are still open

1. **Existing institution-owned rows.** Re-point at the single Co-op row (stage 1), then at
   platform-owned (stage 3)? Or straight to a tenant? Needs a look at live data.
2. **`LENDER_ADMIN`'s name.** `BANK_ADMIN` reads better but every existing profile row carries the
   old code, so it is a data migration rather than a rename.
3. **Whether stage 3 is worth doing at all.** After stages 1 and 2 the column is inert: nothing
   writes it and nothing scopes on it. Dropping it is tidiness bought with the riskiest change in
   the plan. It can be left for a quiet week, or skipped.

---

## 6. Status

**Stage 1 done.** `LENDER_ADMIN` (now "Bank Administrator"), `MORTGAGE_OFFICER` and `CREDIT_ANALYST`
are `ACTOR_PLATFORM`; `V20260914120000__coop_bank_is_the_platform.sql` moves `user_types.actor_class`
and `user_profiles.profile_type` for rows that already exist, guarded against the one unique-index
collision it can cause. `PrincipalFactory` is untouched — its partnership branch is simply no longer
reached by these types, which is what makes the change a two-line revert. 226 tests pass.

**Re-brand done** (§4). Seeded defaults in `ConfigKey`, `EmailSender`'s local part, the `pom`, the
sign-in panel's copy, the design system's brand tokens and the remaining "Hodi" strings in the
frontend. `V20260914130000__coop_bank_branding.sql` carries it to databases that already exist, each
UPDATE conditional on the value still being the Hodi default so nothing anybody has customised is
overwritten.

One thing worth knowing about the palette: the bank's marketing green `#009A44` is **not** the button
fill. White text on it is 3.68:1 at rest and 4.41:1 on hover, both under AA, so every primary action
on the platform would have failed contrast. The accent is `#00883C` — the nearest green in the same
family that clears it — giving 4.58 / 5.47 / 6.08, the same monotonic ramp the teal it replaces had.
The brighter green still appears through `--brand-light` and the accent-light token, where nothing is
written on top of it. All of it is editable from Settings.

**Stages 2 and 3 not started.** Both change the schema, and §5 lists the decisions they need first.

Already landed on `feature/payments-and-payment-types` (and inherited here) — unrelated to lending:
role templates that carry their own roles' verbs, `AUDIT` made core, the `VENDOR` module-audience
divergence, progress-update photographs, the Pesi provider filter, and the forced password change
moving onto the sign-in card.
