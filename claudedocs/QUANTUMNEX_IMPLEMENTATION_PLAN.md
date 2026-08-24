# Quantumnex Property Portal — implementation plan for Hodi Market Place

**Source of requirements:** `Quantumnex_Property_Portal_Dev_Plan.md` (BRD v1.2, 13 Aug 2026), modules M1–M15.
**Applies to:** `hodimp-b` (Spring Boot 4.1.0 / Java 23 / Postgres / Redis, port 8085) and `hodimp-f` (Vue 3 + TS + Naive UI, port 3020).
**Companion document:** `HODI_ACCESS_MANAGEMENT_PLAN.md` — what is already built, and why it is built that way. This
document does not restate it; it audits it against the BRD and then sequences the rest.

**Status:** **Phases 0a and 0b are built and verified** (§7, §8). **Phase 1 is under way**: M2's listings and
marketplace (§9), M2's buyer-side rows plus §3.8's consent store (§10), and M3 — mortgage products and
affordability behind a provider interface (§11). Sections 3.3–3.6 and 3.9 remain planning; M4 is next.

---

## 1. The headline

Phase 0 of the source plan asks whether the existing access management is "a two-day tidy-up or a redesign".

**It is a redesign of the identity model, and a tidy-up of everything else.**

Two findings drive that, and they are the only two that do:

1. **FR073 — one natural person as both Buyer and Seller.** Today one row in `users` *is* one actor: `email` is
   `UNIQUE`, `actor_class` is a column on the user, and `tenant_id`/`institution_id` are columns on the user with a
   `CHECK` that at most one is set. A person cannot be a buyer on Monday and a seller's owner on Tuesday without a
   second email address. Fixing this moves the actor-defining fields off `users` and onto a profile row, which
   touches the JWT, the principal, both tenant filters, the permission resolver and every list that scopes by
   organisation.
2. **Three actor kinds the BRD treats as first-class do not exist**: Agent (FR160–FR164), Valuer (FR035–FR048) and
   Vendor (FR170–FR174). Agent is *nearly* there — there is a `SALES_AGENT` user type under a seller — but the BRD's
   agent is an independent party who signs terms and may list client-owned property, not a seller's employee. Valuer
   needs a visibility rule this codebase does not have: scope by *assignment*, not by organisation.

Everything else in the audit is additive: new tables, new modules, new permissions, using machinery that already
works. The three-axis access model (module admits user type → permission → `TenantScope` visibility), the sliding
session, the config layering, the audit sink and the seeder all survive Phase 0 unchanged in shape.

---

## 2. Access-management audit (source plan §2.2, answered)

Verdicts are against the code on `main` as of this document, not against intent.

| Requirement | Verdict | Gap detail | Fix |
|---|---|---|---|
| Dual Buyer+Seller profile (FR073) | **Missing** → now **Covered** (§7) | `users.email` is `UNIQUE`; `actor_class`, `tenant_id`, `institution_id` and `user_type_id` are all columns on `users`, with `CHECK (tenant_id IS NULL OR institution_id IS NULL)`. One login = one actor, permanently. | `user_profiles` table + active-profile claim in the JWT. See §3.1. |
| Maker/Checker segregation (FR070, FR122) | **Partial** → now **Covered** (§8) | The only segregation that exists is `PartnershipService.assertMayApprove`, and it separates *sides* (the seller side cannot approve its own proposal), not *users* — a second user of the same seller can approve their colleague's proposal. There is no generic mechanism, so every future approval (listing, KYC, auction, promotion, vendor product) would re-implement it. | Generic `approval_workflow` with a `submitted_by <> checked_by` constraint. See §3.2. |
| Seller entity-type KYC fields (FR066) | **Partial** | `tenants.seller_type` exists and is captured at onboarding, but nothing consumes it: there is no per-type required-document list, no document upload for KYC, and no validation that differs by type. | `kyc_requirement_config` + `kyc_submission` + `kyc_document`. See §3.3. |
| Agent role + e-signature (FR160–FR161) | **Missing** | `SALES_AGENT` is a *seller staff* user type — an employee inside a seller organisation. The BRD's agent registers independently, accepts T&Cs with a captured signature artifact, and flags each listing self-owned vs client-owned. No signature storage exists. | New actor class `AGENT` + `agent_profile` + `signature_artifact` + `listing_agreement`. See §3.4. |
| Valuer scoped access (FR043) | **Missing** | No valuer anything. More importantly, `TenantScope` has exactly two visibility modes — own organisation, or the set of partnered sellers — and a valuer needs a third: only the rows of the tickets assigned to them. | New actor class `VALUER` + assignment-based visibility mode in `TenantScope`. See §3.5. |
| Vendor role (FR170) | **Missing** | No vendor, no category taxonomy. Structurally the easiest of the three: a vendor is a small tenant-like organisation whose children are catalogue items rather than properties. | New actor class `VENDOR` + `vendor_profile` + `vendor_category`. See §3.6. |
| Staff business-unit scoping (FR049, FR100) | **Partial** | The mechanism exists and is the right one — platform user types (`SUPER_ADMIN`, `SUPPORT_ADMIN`, `PLATFORM_AUDITOR`) crossed with `app_modules.allowed_user_types` — but the business units the BRD names do not: Property Operations, Recoveries Unit, Valuation Team. Recoveries staff seeing only auction listings is a user type plus a module matrix row, not new machinery. | Seed the four platform user types and the module matrix rows in the phase that introduces each module. No schema change. |
| KYC-gated listing permission (FR075) | **Missing** | There is no KYC and no listing. Note what *is* already right: `onboarding_status` on `tenants` gates activation, and `EffectivePermissionResolver` is the single choke point every permission passes through — so the gate belongs there, not in a controller. | Resolver drops listing-write permissions unless the profile's `kyc_status = APPROVED`. See §3.3. |
| Access audit log (FR006, FR123, FR156) | **Partial** → now **Covered** (§7) | `audit_logs` exists with actor identity, before/after payloads, IP and user agent, and every CUD path writes to it. Three gaps: (a) `AUDIT_LOGIN`/`AUDIT_LOGOUT` are declared in `AppConstant` and never used, so authentication events are not recorded at all; (b) failed logins are not recorded; (c) the table is a normal table — nothing stops an `UPDATE`. | Record auth events; add an append-only guard. See §3.7. |
| Consent/preference store (FR004–FR005) | **Missing** | No consent anything. Notification preferences do not exist either. | `consent_preference` with an append-only history. See §3.8. |
| KYC document encryption (FR006, NFR Security) | **Partial** | `StorageService` is sound as far as it goes: tenant-prefixed keys, content-type allowlist on sniffed type, 10 MB cap, S3-or-filesystem by runtime config. But every object is equally reachable by anyone who can call the module, there is no per-document ACL, no server-side encryption declaration, and no separation between "media" and "documents" — which the BRD's own architecture (§4.1) separates. | Document vault with per-document ACL + SSE. See §3.9. |
| Recovery by email **and** phone (FR007) | **Partial** → now **Covered** (§7) | `PasswordResetService.request` took an email and only an email. *Corrected after checking:* an SMS sender did already exist — `NotifyClient.sendSensitiveSms`, credentials in `configurations`, body redacted in logs — so the gap was only the reset path, not the channel. | Accept either identifier and deliver by the channel it came in on. See §3.10. |

Also worth recording, because the source plan's §2.3 guesses at these and guesses low:

- **Not "a single flat role per user".** Permissions are already arbitrary bundles (`user_groups` →
  `user_group_permissions`), gated by a module-admits-user-type matrix. The fix in §2.3 item 1 is needed for the
  *profile* problem, not for a role-model problem.
- **Parameterised queries throughout** — JPA/`@Query` with bound parameters, no string-concatenated SQL. The one
  place taking a raw identifier (module code matching) uses exact-token matching over a CSV column rather than SQL
  `LIKE`, deliberately.
- **Session handling already exceeds the BRD.** Sliding idle window, refresh rotation with reuse detection that
  revokes every session, a `sessions_valid_from` cutoff that invalidates access tokens issued before a password
  change, and separate cookies per session class.

---

## 3. Phase 0 — the work the audit implies

Ordered by what blocks what. 3.1 and 3.2 block most of section 4; the rest can land alongside the module that first
needs them, and are marked as such.

### 3.1 Profiles: one login, many actors *(blocks M1, M8, M9, M10)* — **BUILT**

**Migration.** New table, one row per existing user, then `users` loses the actor-defining columns:

```
user_profiles
  id, user_id, profile_type       -- BUYER | SELLER | LENDER | AGENT | VALUER | VENDOR | PLATFORM
  tenant_id, institution_id       -- at most one, per profile rather than per user
  user_type_id, user_group_id
  kyc_status                      -- NOT_REQUIRED | PENDING | SUBMITTED | APPROVED | REJECTED
  is_default
  status, status_flag, ... audit columns
  UNIQUE (user_id, profile_type, tenant_id, institution_id)
```

`users` keeps exactly what identifies and authenticates a person: name, email, phone, password, 2FA secret, lockout
state, `sessions_valid_from`. `email` stays `UNIQUE` — one person, one login, several profiles, which is what FR073
actually asks for and is cheaper than reconciling duplicate identities later.

**Token and principal.** The JWT gains `pid` (active profile) alongside the existing claims. `UserPrincipal` resolves
`actorClass`, `tenantId`, `institutionId`, `userTypeCode` and the permission set **from the active profile**, so
`TenantScope`, `TenantBindingFilter`, `EffectivePermissionResolver` and every scoped repository query keep their
current shape and read a different source. A new `POST /api/v1/auth/switch-profile` issues a new token pair for
another profile of the same user; switching is re-issuance, not mutation, so a leaked buyer token can never be
widened into a seller token.

**Frontend.** A profile switcher in the topbar (only rendered when a user has more than one profile), and the router's
landing decision moves from "is this a buyer" to "what is the active profile".

**Honest cost.** This is the largest single change in the whole programme relative to its visible output: nothing new
appears on screen except a switcher, and roughly a dozen files that currently read `user.actorClass` change to read
`profile.actorClass`. It is also the change that gets 30× more expensive after M8 exists.

### 3.2 Generic approvals *(blocks M6, M8, M10, M13)* — **BUILT**

```
approval_workflow
  id, entity_type, entity_id, action        -- e.g. LISTING/PUBLISH, SELLER_KYC/APPROVE
  tenant_id
  submitted_by_user_id, submitted_at, submission_note
  checked_by_user_id, checked_at, decision  -- APPROVED | REJECTED | SENT_BACK
  decision_reason
  state                                     -- PENDING | APPROVED | REJECTED | SENT_BACK
  CHECK (checked_by_user_id IS NULL OR checked_by_user_id <> submitted_by_user_id)
```

The `CHECK` is the point: user-level segregation of duties enforced by the database, not by whichever service
remembers. A service-layer guard sits in front of it for the readable error message, and
`PartnershipService.assertMayApprove` folds into this rather than staying a second implementation.

Two permissions per approvable module, `*_SUBMIT` and `*_APPROVE`, so Maker and Checker are group bundles rather than
hard-coded roles — which is what lets one organisation put both in one group and another split them.

**Decided, and built that way:** self-approval is impossible platform-wide, enforced by the database CHECK. It
is not configurable per entity type — a segregation of duties that can be switched off is not one, and every
regulator's version of this question expects a single answer. Where an organisation is genuinely one person,
the answer is that the platform decides, since it already sits outside every organisation. If Compliance later
wants exceptions, that is a new table and a new conversation, not a flag on this one.

### 3.3 KYC *(with M8)*

```
kyc_requirement_config   -- profile_type + entity_type → required document codes, versioned
kyc_submission           -- profile_id, entity_type, state, submitted/decided, ties to approval_workflow
kyc_document             -- submission_id, document_code, storage key, expiry, verified_at
```

Requirements are data, not code: Compliance changes the CR12 requirement for a SACCO without a deploy, and existing
submissions keep the version they were judged against.

The gate goes in `EffectivePermissionResolver`: if the active profile's `kyc_status` is not `APPROVED`, every
`LISTINGS_CREATE`/`LISTINGS_SUBMIT`-class permission is dropped from the effective set. This is deliberately the same
choke point the module matrix uses, so a UI that forgets to hide a button still cannot reach the endpoint.

### 3.4 Agent *(with M9)*

New actor class `AGENT`; `agent_profile` (licence number, agency name, self-employed flag); `signature_artifact`
(storage key, captured-at, IP, user agent, hash of the accepted terms version — the hash is what makes the signature
evidence rather than decoration); `listing_agreement` generated on approval.

Agent visibility is a third `TenantScope` mode: their own listings, plus client-owned listings where they are the
acting agent.

### 3.5 Valuer *(with M5)*

New actor class `VALUER`; `valuer_profile` (panel membership, PI cover sum assured and expiry, active flag).

The assignment-scoped visibility mode is the new mechanism: `TenantScope.visibleIds()` gains an assignment source, so
a valuer's every list is filtered to the entities of tickets assigned to them. Round-robin assignment lives in a
service with the PI rule from FR041 (`PI sum assured ≥ property price`, active and eligible valuers only).

### 3.6 Vendor *(with M10)*

New actor class `VENDOR`; `vendor_profile`, `vendor_category` (taxonomy, seeded and admin-editable),
`catalogue_item`. Vendors are organisation-scoped exactly like sellers, so they reuse `TenantScope` unchanged.

### 3.7 Auth events and an append-only audit *(small; do it in Phase 0)* — **BUILT**

- Write `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `TOKEN_REUSE_DETECTED`, `PASSWORD_RESET` and `PROFILE_SWITCH` to
  `audit_logs`; `AUDIT_LOGIN` and `AUDIT_LOGOUT` already exist as constants and are simply unused.
- Failed logins record the attempted identifier, never the attempted password.
- Append-only: a `BEFORE UPDATE OR DELETE` trigger on `audit_logs` that raises, plus a role that lacks
  `UPDATE`/`DELETE` on it. Retention becomes a partition drop rather than a `DELETE`.

### 3.8 Consent *(with M1)* — **BUILT** (§10)

```
consent_preference          -- user_id, channel (EMAIL|SMS|PUSH), purpose (PROMOTIONAL|TRANSACTIONAL|...), granted
consent_preference_history  -- append-only: who, when, from where, previous value
```

Separate from the profile because "prove they opted in on this date" is the requirement, and a mutable boolean on a
profile row cannot answer it. Transactional notifications are not opt-out-able and are marked as such in the purpose
list rather than by convention.

### 3.11 Affordability and mortgage products *(M3)* — **BUILT** (§11)

The BRD's headline is a listing and the finance against it, side by side. Three parts, and only one of them
is blocked on anybody else.

```
mortgage_product      -- institution_id, amount and term bounds, rate, LTV, deposit, fees, DTI ceiling, published
affordability_check   -- user_id, the inputs, the decision, the provider's own response kept verbatim
```

**Products are a lender's own rows**, scoped by `institution_id` through the existing principal — no new
visibility machinery. They are published rather than approved: §3.2's Maker/Checker is listed against M6, M8,
M10 and M13, and adding a queue here would be inventing a control the BRD does not ask for. What a product
does need is a *publish* permission distinct from *update*, because putting a rate in front of the public is a
different act from drafting one.

**Which products a buyer sees against a listing is decided by the partnership table**, which already answers
"which lenders may work this seller's portfolio" (`findActiveInstitutionIdsForTenant`). That is the whole
join: no new column, and the arrangement that governs the portfolio governs the finance shown against it.

**Affordability goes through an interface with two implementations.** `AffordabilityProvider` is the contract;
`MockAffordabilityProvider` is a documented, tunable rule set (net income × a configured DTI ceiling → the
repayment a household can carry → the annuity inversion → a loan, plus deposit → a price). The provider is
chosen by a configuration key, so the day the OCP contract exists it is a new class and a changed row, not a
change to anything that calls it. The dependency blocks *correct numbers*, not the funnel around them, and
every screen says the figures are indicative.

**Two response shapes again, for the same reason as the listing.** A check holds somebody's income. The
person who entered it sees everything back; the platform's own list sees the outcome, the derived figures and
which listing it was run against, and never the raw inputs. Lender staff see neither — a lender learns a
buyer's finances when the buyer applies to them, which is M4, and not before.

**Offers are computed on read, not stored.** They are indicative and derived from products that change; a
frozen copy would be a promise the platform did not make. The check itself is stored, because "what did I
work out in March" is a fair question and the inputs are the answer.

### 3.9 Document vault *(with M8)*

Split what the BRD splits: `StorageService` keeps public media (listing photographs, avatars);
a new `DocumentService` handles KYC and legal documents with a `document_acl` row per document, server-side
encryption declared on write, no public URL form (access is always a short-lived signed fetch through an endpoint
that checks the ACL and writes an audit row), and its own bucket or prefix.

### 3.10 Recovery by phone *(small; do it in Phase 0)* — **BUILT**

`PasswordResetService.request` takes an identifier and resolves it as email *or* phone; delivery follows the channel
of the identifier. The indistinguishable-response behaviour for unknown identifiers stays exactly as it is.

**Correction to the audit:** §2's table said an SMS sender did not exist. It does — `NotifyClient.sendSensitiveSms`,
with the gateway credentials already in `configurations` as encrypted values and the message body redacted in every
log line. Only the reset path was email-only, which is a much smaller gap than the table implied.

---

## 4. Modules M1–M15 mapped onto this codebase

Conventions every module follows, so they are not restated per module: `status`/`status_flag` soft lifecycle (5 =
archived, lists hide it); `search_text` generated column + pg_trgm GIN index on anything listable; server-side
paging via `PagedDataRequest`; HashIds on every exposed id; Flyway `VYYYYMMDDHHmmSS__description.sql`; one
`app_modules` row per module with its `allowed_user_types`, and `*_VIEW/_CREATE/_UPDATE/_DEACTIVATE/_ACTIVATE/_DELETE`
permissions plus module-specific verbs; denormalised `*_name` columns rather than joins for display; every list
endpoint paged and indexed; a matching frontend page in the same turn as the endpoint.

| Module | New app module(s) | Core tables | Notable dependency |
|---|---|---|---|
| **M1** Buyer onboarding & profile | extends existing `BUYERS` | `consent_preference`, `consent_preference_history` | §3.1 profiles, §3.8 consent |
| **M2** Discovery & search | `PROPERTIES` (public read), `SAVED_LISTINGS`, `SEARCH_ALERTS` | `property`, `property_media`, `property_document`, `saved_listing`, `search_alert` | Redis cache regions (`hodimp:` prefixed) designed in, not retrofitted |
| **M3** Affordability | `AFFORDABILITY`, `MORTGAGE_PRODUCTS` | `affordability_check`, `mortgage_product` | Built against a mock behind `AffordabilityProvider`; only *authoritative numbers* wait on OCP |
| **M4** Leads & buyer dashboard | `ENQUIRIES`, `SITE_VISITS`, `PURCHASE_REQUESTS` | `enquiry_ticket`, `site_visit`, `purchase_request` | CRM connector (M14) for the mortgage path |
| **M5** Valuation | `VALUERS`, `VALUATIONS` | `valuer_profile`, `valuation_request`, `valuation_report` | §3.5 assignment-scoped visibility |
| **M6** Auction | `AUCTIONS` | `auction_listing`, `auctioneer` | §3.2 approvals; isolation from M2 search results is a hard rule (UC006) |
| **M7** Post-transaction & feedback | `RATINGS`, `MODERATION` | `property_rating`, `service_rating`, `moderation_queue` | — |
| **M8** Seller onboarding & listing governance | `LISTINGS`, `KYC` | `listing`, `listing_document`, `listing_progress_update`, `green_certification`, KYC tables from §3.3 | §3.1, §3.2, §3.3, §3.9. Largest module: five sub-slices, each its own turn |
| **M9** Agent management | `AGENTS` | `agent_profile`, `signature_artifact`, `listing_agreement` | §3.4 |
| **M10** Vendor marketplace | `VENDORS`, `CATALOGUE` | `vendor_profile`, `vendor_category`, `catalogue_item`, `product_rating` | §3.6, §3.2 |
| **M11** AI assistant | `ASSISTANT` | `assistant_conversation`, `assistant_message` | Thin orchestration over M2/M3/M4; hand-off writes an M4 ticket with the transcript attached |
| **M12** Internal management (buyer side) | `TICKETS`, `CALENDAR` | `ticket_assignment_rule`, `event_calendar_entry` | Property Operations user type |
| **M13** Internal management (seller side) | `PROPERTY_CONFIG`, `PROMOTIONS`, `COMMISSIONS` | `property_type_config`, `promotion_package`, `commission_record` | §3.2 for the KYC and listing queues |
| **M14** Integration layer | not a UI module | `integration_log`, `outbound_message` | OCP client, CRM connector, SMS/email gateway. The SMS half is needed early by §3.10 |
| **M15** MIS & reporting | `REPORTS` | reporting views only | Read replica or warehouse, not the transactional DB |

### Sequencing for this repository

The source plan's phases hold; what changes is the granularity, because each phase here is several turns and each
turn lands backend **and** frontend together.

| Phase | Contents | Turns (estimate) |
|---|---|---|
| **0a** | §3.1 profiles + switcher; §3.7 auth audit; §3.10 phone recovery | **done** |
| **0b** | §3.2 approvals, folding partnerships into it | **done** |
| **1** | M2 and M3 **done — listings, marketplace, shortlist, saved searches, consent store, mortgage products, affordability** → M4 next | 6–8 |
| **2** | M8 in five slices: KYC schema/§3.3 → registration by seller type → listing CRUD + media → approval queue → progress updates. §3.9 vault lands with the first slice | 6–8 |
| **3** | M5, M6 | 4–5 |
| **4** | M9, M10, M7 | 4–5 |
| **5** | M12, M13, M15 | 5–6 |
| **6** | M11 | 2–3 |
| **7** | NFR hardening (§5 below) | 2–3 |

M3 is deliberately built against a mocked OCP client behind the real interface, so the dependency blocks *correct
numbers*, not the funnel around them.

---

## 5. Non-functional checklist — current standing

| NFR | Standing |
|---|---|
| Search/affordability < 2s | Not yet measurable. The pattern is in place (paged endpoints, pg_trgm GIN, Redis regions) |
| 50k concurrent external / 1k staff | Untested. Stateless auth and per-request tenant binding scale horizontally; the buyer-facing read path needs the cache designed in at M2 |
| Horizontal scaling | Sessions are DB-backed refresh tokens with stateless access tokens, so no sticky sessions |
| TLS + encryption at rest | TLS is deployment. Config secrets are encrypted at rest today (`EncryptionUtil`); documents are not — §3.9 |
| Least privilege on both portals | **Done and verified** — three-axis model, verified end to end from an empty database |
| Input validation / OWASP | Parameterised queries throughout, no string-concatenated SQL. Bean Validation on nine of the write controllers — the configuration-admin endpoints validate in the service instead, which should be made consistent |
| VAPT | Not started. Phase 7 |
| Full immutable audit trail | Partial — see §3.7 |
| Logs exclude PII/credentials | Secrets are masked in list, log and at-rest with an audited reveal path. Needs a sweep once buyer PII exists |
| APM + alerting | Not started |
| DR, RPO < 15 min / RTO < 4 h | Infrastructure, outside this codebase; flag to whoever owns the Postgres |
| WCAG 2.1 | Partial by construction — semantic markup, labelled fields, focus-visible rings, reduced-motion honoured. Needs a real audit |
| English / KES / DD-MM-YYYY | Currency and timezone are per-organisation columns with KES/Africa-Nairobi defaults; date formatting needs one shared formatter rather than per-page `toLocaleDateString` |

---

## 6. Decisions needed before Phase 0b

These change the shape of the work, so they are worth answering before code rather than after:

1. **Are Agent, Valuer and Vendor tenants?** My recommendation: **no** for Valuer (an individual on a panel, scoped by
   assignment), **no** for Agent unless agencies with staff are in scope (if they are, an agency is a tenant and an
   agent is its staff), **yes-shaped** for Vendor (an organisation with catalogue items). Getting this wrong is a
   migration, not a refactor.
2. **Is self-approval blocked platform-wide, or configurable per entity type?** §3.2 assumes blocked, enforced by a
   database `CHECK`.
3. **Does one person's Buyer and Seller profile share one login (my recommendation) or two separate credentials?**
   The BRD says "same email allowed", which reads as one login.
4. **OCP affordability contract** — until it exists, M3 ships against a mock.
5. **KYC document list per seller type** — Compliance sign-off. §3.3 makes it data, so the answer can arrive late,
   but the *schema* of the answer (document codes, expiry, per-type versioning) needs confirming early.
6. **Auction isolation** — confirm that auction listings are excluded from buyer search entirely (UC006), rather than
   filtered by a facet. Exclusion is a different query path, not a flag.


---

## 7. Phase 0a as built

Landed on `main` in both repositories, verified end to end against a database that already held the previous
schema (so the migration was exercised as a real upgrade, not a fresh install).

### Schema

`V20260824090000__user_profiles.sql` — creates `user_profiles`, backfills one row per existing user, then drops
`user_type_*`, `user_group_*`, `actor_class`, `tenant_*` and `institution_*` from `users`. `users.search_text` is
rebuilt person-only and the labels get their own index on the profile. Two partial unique indexes carry the rules
that matter: one profile of a kind per organisation, and exactly one default per person. `refresh_tokens` and
`audit_logs` each gain a profile column.

`V20260824090100__audit_append_only.sql` — statement-level triggers that refuse `UPDATE` and `DELETE` on
`audit_logs`. Verified by trying both as superuser: both raise, and the row is unchanged. Retention is therefore a
partition drop, which is noted on the table's own comment.

### What the profile claim is, and why it is safe

The access token carries `pid`. It is the only authorisation-adjacent claim in the token, and it is a *selection*
among profiles the subject already holds, never an entitlement: `JwtAuthenticationFilter` loads the profile by
(id, userId) and refuses anything that does not belong to the subject. Verified by hand-signing three tokens with
the deployment secret — one naming another user's profile, one naming a profile that does not exist, one naming its
own — and getting 401, 401, 200.

### Defects found by building it

Six, all fixed:

1. **HashIds in the login response were salted "system".** The salt is per user from the security context, and on
   the login path there is no context yet — so `profiles[].id` in a login response did not decode on the
   authenticated request that sent it back, and switching profile straight after signing in failed with a 500.
   `me()` now encodes against the user's own name explicitly. This was latent before profiles: every id in a login
   response had it, and nothing had yet round-tripped one.
2. **A staff member who added a buyer profile was trapped on it.** `BuyerVerificationRequiredFilter` refused
   everything outside its allowlist, and `/auth/switch-profile` was not on it — so the only way off an unverified
   buyer profile was to sign out. Added to the allowlist.
3. **Verification was asked of people it was never asked of.** Staff accounts do not go through buyer
   verification, so a staff member's buyer profile was permanently "unverified" and pending a code nobody would
   ever send. `PrincipalFactory.isVerified` now exempts anybody holding a live staff profile — the person was
   vouched for by whoever created their account.
4. **The client re-derived that rule and got a different answer.** `needsVerification` was
   `isBuyer && !emailVerified`, which sent the same person to the "confirm your email" screen the server was
   letting through. `MeResponse` now carries `verificationRequired` and the client reads it.
5. **The buyer dashboard card contradicted the details table** — "Confirmed" against "Not yet", because one asked
   whether anything was outstanding and the other whether the address was confirmed. Both now say what they mean.
6. **`recordAuth` wrote a bare string into a JSONB column**, so every login, failed login and logout was silently
   dropped with an error in the log. Found by looking at the table rather than at the code.

### Behaviour verified

Login lands on the profile matching the requested session class (a person holding both gets their buyer profile at
the marketplace and their staff profile at the workspace, with the right idle window each time); switching issues a
new session, revokes the old refresh row and blacklists the old access token; a refresh keeps the profile it was
issued for; the users list is now a list of profiles and searches across both the person's and the profile's
generated columns ("wanjiru acacia" finds one row); a buyer-only unverified account is still gated with a 403 while
`/auth/me` stays reachable; and password recovery by "0733 444 555" finds an account stored as "254733444555" while
an unmatched identifier creates nothing and says the same thing either way.

### Deliberately not done in 0a

- **Removing somebody from an organisation** without touching their credential. The row actions on the users list
  act on the account, as they did before; per-profile retirement belongs with the phase that gives organisations
  their own membership screens.
- **Adding a buyer profile during registration.** An unauthenticated request naming somebody else's address must
  never write a profile onto their account, and applying the submitted password would be a password reset with no
  proof of anything. It is a self-service action on the profile page instead, and registration still answers
  identically for every existing address.
- **AGENT, VALUER and VENDOR profile types.** The CHECK constraint lists the four that exist; each new one arrives
  with its module, its permissions and its screens, because a profile type with none of those is a value nothing
  can hold.


---

## 8. Phase 0b as built

### The mechanism

`V20260824180000__approval_workflows.sql` creates `approval_workflows`: `(entity_type, entity_id, action)` plus
the scope whose queue it belongs in, the submitter, the decision, and four CHECK constraints. Three are worth
naming:

- `ck_approval_maker_checker` — `checked_by_user_id <> submitted_by_user_id`. **The rule.** Verified by raw
  SQL as superuser: the UPDATE is refused.
- `ck_approval_decided` — a decided row has a decider and a pending one does not, so "approved by nobody" is
  not representable. That is the shape an audit cannot ask questions about.
- A partial unique index on `(entity_type, entity_id, action) WHERE state = 'PENDING'` — two people submitting
  the same thing cannot produce two queue rows, either of which could be decided while the other stayed open.

`ApprovalHandler` is what a module supplies to make its things approvable: the entity type, **the domain's own
decide permission**, any extra domain rule, and what "approved" actually does. There is deliberately no
`APPROVALS_DECIDE` permission — a single approve-anything code would be a way around every module's gate,
granted from one screen. `APPROVALS_VIEW` grants sight of the queue and nothing else.

### Partnerships folded in

Proposing raises the queue entry in the same transaction, in the queue of the side that did *not* propose. The
partnership screen's Approve button now goes through the same workflow as the queue, so there is one path to
cross-organisation access rather than two that can drift. The old `assertMayApprove` became the handler's
`assertMayDecide` — the organisation rule, which is domain knowledge — while the user rule and the permission
are the workflow's.

Verified end to end: seller proposes with a covering note → the request appears in the lender's queue and not
in the seller's → the seller (the submitter) is refused with "You submitted this — somebody else has to decide
it" → the lender approves → the partnership activates and the lender's visible-seller set gains it on the next
request → reject with no reason is refused → send back with a reason clears the proposal so either side may
propose again, and the lender loses visibility immediately.

### Two gaps this phase exposed, both fixed

Neither is about approvals; both are about what happens when a module ships *after* an organisation exists.

1. **New permissions never reached existing organisations.** An owner group is built at onboarding from the
   permissions that existed that day, and nothing revisited it. `APPROVALS_VIEW` was therefore invisible to
   every organisation already on the platform — and would have been, once per module, for the fifteen still to
   come. The seeder now tops up each organisation's *system* group on every boot, filtered through the module
   matrix and `platformOnly` exactly as onboarding is. Groups an organisation built for itself are left alone:
   adding permissions to those would be the platform widening a role somebody deliberately narrowed.
2. **New core modules never reached existing organisations either.** `enableCoreModules` was written to be
   idempotent "so it can be re-run", and nothing re-ran it. The seeder now calls it for every live tenant.
   Non-core modules are deliberately left alone — those are an organisation's choice, and switching them on
   would override it on every deploy.

`APPROVALS` was made **core** in the process. A queue for a control the BRD requires everywhere should not be
something an organisation can be switched off from while remaining subject to the rule; what can be withheld
is the permission, and that is per user group where it belongs.

### One client-side defect worth recording

Approving a partnership left the shell still saying "no sellers in view" directly above the partnership that
had just been approved. The server rebuilds the principal per request, so the *next* call was correctly scoped
— it was the client's copy of the identity, taken at sign-in, that was stale. Both the approvals queue and the
partnership screen now re-read `/auth/me` alongside the list after any decision.


---

## 9. Phase 1 / M2, first slice

The first table in this schema that is not about access, and the first screen a stranger came for.

### What landed

`properties` and `property_media`, with the lifecycle DRAFT → PENDING → LIVE → SOLD/WITHDRAWN. Publication is
a Maker/Checker decision through the queue built in 0b: submitting raises a `PROPERTY`/`PUBLISH` request in the
seller's own queue, and `PropertyApprovalHandler` is fifty lines because everything about who may decide, when,
and what is recorded already existed. Editing a live listing takes it back through the queue — a public offer
somebody may be acting on is not a thing to change quietly.

Seller-side: a four-step listing wizard (the property, where, green, photographs), photograph management with a
cover image, and the listing list scoped by `TenantScope` — so a partnered lender reads a seller's portfolio
through the same endpoint without any code here knowing about them.

Public: `/api/v1/public/properties` with search, facets counted off live listings, and detail by *reference*.
Two response records rather than one with fields blanked — a stranger gets the town and the estate, never the
address line, and the two being separate types is what stops a field leaking by being forgotten.

The KYC gate from §3.3 is now wired: `PROPERTIES_CREATE/UPDATE/SUBMIT/MEDIA` are in `KYC_GATED`, inert until M8
starts writing a status other than `NOT_REQUIRED`. Reading is deliberately not gated — a seller waiting on KYC
can still see what their colleagues drafted.

### Deliberately deferred

Title deeds and approved plans (they are §3.9's document vault, and building them into general media storage
now is building the thing the plan says to build properly later), saved listings and search alerts (a buyer's
own rows, which belong beside M1's consent store), and enquiries (M4 — a button that opened nothing would be
worse than its absence).

### Three defects found by building it

1. **Every locally-stored file resolved to `/media/media/…`.** `storage.local.base.url` was seeded as a path
   (`/media`) while `StorageService.urlFor` appends `/media/<key>` to it. The key configures the *origin* in
   front of a path the application owns; it now defaults to empty, meaning same origin, and a migration
   corrects the stored value.
2. **The migration fixed the row and the cache kept serving the old value** — configuration lives in Redis,
   which outlives a restart, so the broken URL survived two deploys. The seeder now empties the configuration
   cache before it reconciles anything.
3. **Every page taller than the viewport was clipped at one screenful.** `html, body, #app { height: 100% }`
   with an opt-in `page-scrolls` class that nothing ever added: the property detail page ended mid-air and the
   settings screens could not reach their last card. The default is now `min-height`, so a page grows with its
   content — the failure mode decides the default, and forgetting to opt into scrolling loses content silently
   while forgetting to opt into a fixed frame merely gives you a scrollbar.

   Fixing that exposed a second one underneath: `html, body { overflow-x: hidden }` makes the document a
   scroll container, which stops `position: sticky` working for everything inside it. The workspace sidebar
   had been scrolling away with the page all along, with nothing wrong in its own CSS. It is `overflow-x:
   clip` now, which prevents sideways scrolling without creating the container.


---

## 10. Phase 1 / M2, second slice — a buyer's own rows, and the consent store

The three things held back from the first slice, built together because the third is the gate on the second.

### What landed

**The consent store (§3.8, FR004–FR005).** `consent_preferences` holds one current position per (person,
channel, purpose); `consent_preference_history` holds every movement of it, written **by trigger** and
refusing UPDATE and DELETE. The trigger rather than the service is the point: "the code always remembers to
record the change" is the assumption the table exists to remove, so a repair script or a future bulk import
still leaves a trail. Transactional messages are not opt-out-able and the database says so with a CHECK —
verified by trying it as superuser, which is refused.

Keyed on `user_id`, not the profile, which is the one place since Phase 0a that deliberately ignores the
actor split: consent is a fact about a natural person and the inbox they answer on, and a per-profile store
would let the same person be simultaneously opted in and opted out on the same address.

**The shortlist (FR020–FR021).** `saved_listings`, also keyed on the person — saving a house is something a
human does, not something an actor does, and hiding a shortlist when somebody switches to their seller
profile would be a bug rather than isolation. It is the only table in the schema without the soft lifecycle:
un-saving deletes the row, because a bookmark somebody removed should stop existing rather than linger as an
archived row the unique index still collides with. A snapshot of title, price, town and cover image is taken
at save time and used **only** when the listing is no longer public, so a sold or withdrawn listing reads as
news rather than as a stale price.

**Saved searches (FR022–FR024).** `search_alerts` stores the criteria as columns, one for one with
`PublicSearchRequest`, and `PublicPropertyService.newMatches` re-runs them through the *same* specification
builder the marketplace endpoint uses — one implementation of the search, so a standing search cannot come to
mean something different from the same filters typed today. `SearchAlertDispatcher` polls what is due and
hands each row to `SearchAlertRunner`, a separate bean so `REQUIRES_NEW` actually goes through the proxy.

There is deliberately **no channel column** on `search_alerts`. Which way an alert arrives is resolved from
the consent store at send time, every time, so somebody who opts out this morning stops hearing from every
search they have ever saved this morning without anything going back to edit those rows. An empty channel set
means silence — never a fallback to email, which would make the recorded refusal decorative.

**Front end.** A heart on every marketplace card and on the detail page, backed by one Pinia store so the
same listing cannot be filled in one place and empty in another; "Save this search" turning the current
filters into an alert, showing the criteria back as a sentence first; and three pages in the buyer's own area
— the shortlist, the saved searches with what each one has actually done, and the consent grid with the
person's own history under it. Registration gained one unticked box: the property-alerts consent, captured in
the same transaction as the account.

### Behaviour verified

Registration with the box ticked writes six consent rows with IP and user agent, alerts granted and marketing
refused; the transactional CHECK refuses a false as superuser; the history table refuses an UPDATE as
superuser; hearts survive a reload from `/me/saved-listings/references`; a withdrawn listing falls back to
its snapshot with the reason and the date; consent switched off makes the alert report itself *Silent* on its
own screen with the fix one click away; the dispatcher run with alerts off records `NO_CONSENT` and sends
nothing; with consent restored it composed and delivered both messages against a stub gateway; a second run
over the same window found nothing, and a newly published matching listing produced exactly one more alert.

### Two things worth recording

1. **Listings are addressed by `reference` in this module, not by id.** The client is holding ids encoded
   under `PublicMarketplace`'s fixed salt; `/api/v1/me/**` is not on that path, so the caller's own salt
   applies and the same string decodes to nothing. A reference is salt-free and is already the marketplace's
   own handle. Alerts keep ordinary ids — they are the caller's own rows and were never encoded publicly.

2. **A child component's root element is matched by its parent's scoped CSS.** The heart used `class="chip"`
   with an `on` modifier; the marketplace's own filter-chip rule (`.chip.on`) then painted every saved heart
   solid brand-green. Component-internal class names are prefixed now. The same shape bit twice in one turn —
   a ghost `AppButton` given `class="danger"` was painted solid red by AppButton's own `danger` variant.

3. **`skipped` and `failed` are not the same delivery outcome.** The alert row carries the buyer's version —
   it arrived or it did not — and the audit row carries the per-channel reason underneath it, because a
   gateway that rejected a message and a channel switched off in configuration are different conversations.


---

## 11. Phase 1 / M3 — the finance beside the listing

The other half of the platform's headline sentence.

### What landed

**`mortgage_products`.** A lender's own rows, scoped by `institutionId` off the principal exactly as a
listing is scoped by `tenantId` — no new visibility machinery. Published rather than approved: §3.2's
Maker/Checker list is M6, M8, M10 and M13, and inventing a control the BRD does not ask for is not free.
What publication does get is `MORTGAGE_PRODUCTS_PUBLISH`, separate from `_UPDATE`, because drafting a rate
and putting it in front of the public are different acts.

**`affordability_checks`.** Keyed on the person for the third time in this codebase and for the third
version of the same reason: income is a fact about a household, not about an actor. The assessor's own
answer is kept verbatim in `provider_payload` — when somebody asks why a figure was what it was, the answer
has to be the thing that produced it rather than this application's reading of it.

**`AffordabilityProvider`, with `MockAffordabilityProvider` behind it.** Not a stub: net income after
obligations × a configured DTI ceiling → the repayment a household can carry → the annuity read backwards →
a loan, plus deposit → a price. Three outcomes rather than two, because a household a shilling past the
ceiling is not in the same position as one at twice it. The provider is chosen by a configuration row, so
the day OCP exists it is a new class and an edited value — and the way back is the same edit.

**Which lenders appear against a listing is the partnership table**, which already answered "which lenders
may work this seller's portfolio". No new column: the arrangement that governs the portfolio governs the
rates shown against it, and a seller partnered with nobody shows an honest empty panel rather than the whole
market.

**Front end.** A three-step product wizard whose pricing step carries a live worked example — a rate, an LTV
ceiling and a minimum deposit interact in ways nobody holds in their head, and a lender should see what a
buyer will be shown before publishing rather than after. A finance panel on every listing. A public
calculator at `/affordability` that a stranger can use and a signed-in person's account keeps. A read-only
platform list.

### Two privacy splits, both at the response rather than in a template

`ProductResponse` vs `PublicProductResponse` is the listing split again. `AffordabilityResponse` vs
`AffordabilitySummary` is the one that matters: the platform's list carries outcomes and derived figures and
**no income at all** — verified at the wire, not just on screen. Lender staff see neither; a lender learns a
buyer's finances when that buyer applies, which is M4. `AFFORDABILITY` is consequently the narrowest module
matrix in the catalogue, admitting platform types only.

Recording a check deliberately writes **no audit row**. The check is the record, it is the person's own, and
copying their income into the one table designed to be widely readable would undo the split above.

### Behaviour verified

A product drafted through the wizard and published from the list; the worked example's repayment matching
the server's to the shilling on the same terms; the finance panel on a KES 14.5m listing showing three
partnered-lender options with deposits and repayments that check out by hand; the calculator returning
MARGINAL at 43.25% against a 40% ceiling with the advice that a longer term or larger deposit would close
it; the saved check appearing under "your previous answers"; the platform's list returning no income field;
and a platform administrator reading all three products but refused 403 on both edit and withdraw.

### Three defects found by building it

1. **A POST under `/api/v1/public` is not public.** `PUBLIC_GET` carries the `/public/**` wildcard; writes
   are named one by one. The calculator returned 401 until it was listed — which is the rule working, and
   the entry now carries the reason it is safe: it writes nothing, calls nothing, holds no state.

2. **A platform-initiated partnership landed in nobody's queue.** `PartnershipService` scoped the approval
   to the side that did not propose, and when *neither* side proposed both columns came out null. The
   request was invisible to both organisations and undecidable by the platform, whose own proposer
   Maker/Checker bars. It goes to the seller now: a partnership opens their portfolio, so theirs is the
   consent that matters.

3. **The sidebar's overflow was invisible, and `scrollHeight` said it was fine.** Two new nav entries pushed
   the menu past the viewport; because the sidebar does not scroll, `nav { flex: 1 }` simply compressed it
   and the last item rendered underneath the footer. `scrollHeight === clientHeight` still reported no
   overflow — the check that had passed a turn earlier. The nav no longer shrinks below its content, so the
   sidebar becomes honestly too tall and the page scrolls; the correct measurement is the last link's bottom
   against the footer's top.
