# Quantumnex Property Portal — implementation plan for Hodi Market Place

**Source of requirements:** `Quantumnex_Property_Portal_Dev_Plan.md` (BRD v1.2, 13 Aug 2026), modules M1–M15.
**Applies to:** `hodimp-b` (Spring Boot 4.1.0 / Java 23 / Postgres / Redis, port 8085) and `hodimp-f` (Vue 3 + TS + Naive UI, port 3020).
**Companion document:** `HODI_ACCESS_MANAGEMENT_PLAN.md` — what is already built, and why it is built that way. This
document does not restate it; it audits it against the BRD and then sequences the rest.

**Status — 26 August 2026:** **all fifteen modules are built and verified**, phases 0 through 6 complete,
phase 7 (NFR) in its first pass. Everything below §7 is an "as built" record written after each module
landed. See **§0** for how to restart the machine and pick up.

| Phase | Contents | State |
|---|---|---|
| 0a / 0b | Profiles, auth audit, phone recovery, Maker/Checker | done — §7, §8 |
| 1 | M2 listings + marketplace, M1 consent, M3 affordability, M4 leads | done — §9–§12 |
| 2 | M8 KYC, the document vault, seller types, progress updates | done — §13, §14 |
| 3 | M5 valuation, M6 auction | done — §15, §16 |
| 4 | M9 agents, M10 vendors, M7 ratings | done — §17–§19 |
| 5 | M12 routing + diary, M13 seller operations, M15 reports | done — §20–§22 |
| 6 | M11 assistant | done — §23 |
| 7 | NFR hardening | first pass done — §24; the rest is deployment work, listed there |

---

## 0. Resuming after a restart

Nothing in this build depends on a running process — the state is in Postgres and in the two git
repositories. What follows is how to get back to a working machine.

### Start the infrastructure

Postgres and Redis are shared containers, not per-project ones:

```
docker start postgres redis          # database on localhost:54321, redis on 6379
```

The schema is `hodimp` on that instance, at Flyway version `20260826190000` (31 migrations). The application
validates the migration state on boot and refuses to start if it disagrees, so a successful boot is proof
the database is where this document says it is.

### Start the two applications

```
cd hodimp-b && mvn -o spring-boot:run     # http://localhost:8085
cd hodimp-f && npm run dev                # http://localhost:3020
```

Java 23 is the default on this machine — do **not** run `sdk use` first. The backend takes about fifteen
seconds and prints `Started HodiApplication` when it is up; the seeder line above it reports what it
reconciled, and zero of everything is the normal steady state.

### Sign in

| Who | Username | Password |
|---|---|---|
| Platform super admin | `superadmin` | `Hodi#Verify2026` |
| Seller owner (Acacia Ridge) | `wanjiru` | `Hodi#Verify2026` |
| Lender admin (Equatorial Bank) | `otieno` | `Hodi#Verify2026` |
| Support admin | `grace` | `Hodi#Verify2026` |
| Buyer | `wanjiru.kamau` | `Marketplace#2026` |
| Valuer | `aisha.noor` | `Valuer#Verify2026` |
| Agent (Karanja & Partners) | `mwangi.karanja` | `Agent#Verify2026` |
| Vendor (Mutiso & Associates) | `faith` | `Vendor#Verify2026` |

These are development credentials seeded or created during the build. They are not in any deployment.

### What is already in the database to look at

A working example of nearly every module, as of the last run:

- **16 listings** across two sellers and one agent, **2 sold** with a commission raised against each, **1**
  carrying a paid placement — so the Featured badge and the boosted sort are both visible on the front page.
- **2 auction lots**, both scheduled and in the public catalogue, one with an approved bidder registration.
- **1 approved agent** with a signed agreement and a client-owned listing; **1 approved vendor** with a
  published price.
- **2 reviews**, one of which was held by the word list and then published by a moderator — so the moderation
  queue has a decided item in it rather than being empty.
- **1 routing rule** putting Nairobi apartment enquiries on a named person, and **2 diary entries** — one
  projected from a confirmed viewing, one typed by hand.

Counts drift as the machine is used; what matters is that every module has something real behind it rather
than an empty table.

### Where to pick up

Phase 7 is the only phase with work left, and **none of it is code waiting to be written in these two
repositories** — it is VAPT, APM and alerting, load testing against the 50k/1k target, the read replica for
M15's views, DR/RPO/RTO, and a WCAG audit. §24 says why each is listed rather than attempted.

If the next session is feature work rather than hardening, the honest starting point is not this document
but the BRD: every module it names is built, so what comes next is whatever the business has learned since
v1.2 was written.

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

### 3.3 KYC *(with M8)* — **BUILT** (§13)

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

### 3.5 Valuer *(with M5)* — **BUILT** (§15)

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

### 3.9 Document vault *(with M8)* — **BUILT** (§13)

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
| **M4** Leads & buyer dashboard | `ENQUIRIES`, `SITE_VISITS`, `PURCHASE_REQUESTS` | `enquiry_tickets`, `enquiry_messages`, `site_visits`, `purchase_requests` | Built (§12). CRM connector (M14) still to come for the mortgage hand-off |
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
| **1** | **done** — M2 (listings, marketplace, shortlist, saved searches), M1 consent, M3 (products, affordability), M4 (enquiries, viewings, offers) | 6–8 |
| **2** | **done** — M8: KYC + §3.9 vault (§13), seller types with a mandatory-KYC policy, and listing progress updates (§14). Listing CRUD, media and the approval queue had arrived early with M2 | 6–8 |
| **3** | **done** — M5 (§15), M6 (§16) | 4–5 |
| **4** | **done** — M9 (§17), M10 (§18), M7 (§19) | 4–5 |
| **5** | **done** — M12 (§20), M13 (§21), M15 (§22) | 5–6 |
| **6** | **done** — M11 (§23) | 2–3 |
| **7** | NFR hardening (§5 below) — next | 2–3 |

M3 is deliberately built against a mocked OCP client behind the real interface, so the dependency blocks *correct
numbers*, not the funnel around them.

---

## 5. Non-functional checklist — current standing

| NFR | Standing |
|---|---|
| Search/affordability < 2s | Not yet measurable. The pattern is in place (paged endpoints, pg_trgm GIN, Redis regions) |
| 50k concurrent external / 1k staff | Untested. Stateless auth and per-request tenant binding scale horizontally; the buyer-facing read path needs the cache designed in at M2 |
| Horizontal scaling | Sessions are DB-backed refresh tokens with stateless access tokens, so no sticky sessions |
| TLS + encryption at rest | TLS is deployment. Config secrets encrypted (`EncryptionUtil`); vault documents declare SSE-KMS or SSE-S3 on write and the row records which (§13) |
| Least privilege on both portals | **Done and verified** — three-axis model, verified end to end from an empty database |
| Input validation / OWASP | **Done** for what this codebase controls. Parameterised queries throughout; the one place SQL is assembled (M15 reports) takes every fragment from a declared catalogue and nothing from a request. Bean Validation now on every `@RequestBody` write endpoint — the configuration-admin ones were the last holdout (§24) |
| VAPT | Not started. Phase 7 |
| Full immutable audit trail | Append-only by trigger on `audit_logs`, and again on signatures and executed agreements (§17). Auth events recorded |
| Logs exclude PII/credentials | **Done** — credentials replaced outright; personal data partially masked so two log lines about the same buyer still match without the file being worth stealing (§24) |
| APM + alerting | Not started |
| DR, RPO < 15 min / RTO < 4 h | Infrastructure, outside this codebase; flag to whoever owns the Postgres |
| WCAG 2.1 | Partial by construction — semantic markup, labelled fields, focus-visible rings, reduced-motion honoured. Needs a real audit |
| English / KES / DD-MM-YYYY | **Done** — `src/utils/format.ts` owns the house style and every screen uses it. The one surviving `toLocaleDateString` call asks for a weekday name, which is not a date format (§24) |

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


---

## 12. Phase 1 / M4 — leads

Ask a question, arrange to see it, offer to buy it. The three things a buyer does about a listing.

### Three tables, not one

An enquiry is a conversation, a viewing is an appointment two parties agree a time for, and an offer is a
figure with a decision on it. One polymorphic `interaction` table would carry two thirds nulls per row and a
CHECK nobody could read.

Every row carries **both** `user_id` (the buyer) and `tenant_id` (the seller). It is the only place in the
schema where the two visibility rules meet, and deliberately so — a lead *is* the meeting. The buyer's
methods never take a tenant and the seller's never take a user id, and the two live in separate controllers
(`MyLeadController`, `LeadController`) precisely so nobody later adds `@PreAuthorize` to the wrong half.

### Decisions worth recording

- **Contact details are snapshots, not label caches.** A seller ringing back a March lead should find the
  number that was on it in March.
- **A viewing keeps two times.** `requested_at` never changes; `slot_at` is what was agreed. Both are shown
  to both sides, which is what settles "but I asked for Saturday". Offering another time is a confirmation at
  a time the seller chose, not a fourth state nobody could act on differently.
- **Accepting an offer is not a contract**, and the dialog says so before the button is pressed. It does not
  take the listing off the marketplace; marking a property sold stays a separate act.
- **One live offer per buyer per property**, by partial unique index — a second while the first is
  outstanding is a changed mind, not a second figure for a seller to reconcile. A buyer declined in March may
  offer again in June, which is why it is partial rather than a table constraint.
- **Notifications go through the consent store**, under `TRANSACTIONAL`. The store always says yes for that
  purpose because its own CHECK requires it — but asking anyway is what keeps the day somebody adds a purpose
  from silently sending under it. Delivery is best-effort and never blocks the row.
- **The inbox's aggregates are maintained by the one method that adds a message**, so "waiting on us" cannot
  drift from the thread it describes.

### Behaviour verified

A buyer raised an enquiry, requested a viewing and made an offer on a live listing; the seller's counts read
1/1/1; the seller replied in the thread and the conversation left the "waiting on us" filter; the seller
moved the viewing from 11:00 to 14:00 and the buyer's screen showed *"Friday 28 August at 14:00 — you asked
for Friday 28 August at 11:00"* with the seller's note; the seller accepted an offer 4.8% under asking and
the buyer's screen showed it accepted with the seller's message.

### One defect found by building it

**Spring Data scans top-level types only.** The four repositories were briefly nested inside a single
`LeadRepositories` holder — tidy on paper, and the application refused to start with "no qualifying bean".
Split into four files, which is the convention everywhere else here anyway.


---

## 13. Phase 2 / M8, first slice — KYC and the document vault

Two things that had to arrive together: KYC is the first feature handling documents nobody but Compliance
should see, and building it on the store that serves listing photographs is the mistake §3.9 exists to
prevent.

### The vault

`VaultStorage` is a second storage class, not a flag on the first. `StorageService` exists to produce URLs a
browser fetches directly; a CR12 needs the opposite, so the vault has **no `urlFor` and no `url` column** —
the table comment says not to add one. It writes under its own `vault/` prefix, declares server-side
encryption and records *which* on the row, computes a SHA-256 of the bytes, and strips the original filename
out of the key (KYC uploads are named things like `john_doe_id.pdf`, and keys leak into logs).

`DocumentService.read` is the only route to bytes in the application. It resolves `vault_document_acl`,
refuses if no grant applies, and writes an audit row **either way** — a refused read is the more interesting
of the two. Platform staff are deliberately not exempt: this is the one place where "the platform sees
everything" does not hold, and a super administrator with no grant is refused like anybody else.

### KYC

`kyc_requirement_configs` is a versioned catalogue — Compliance changes what a SACCO must produce without a
deploy, and a submission freezes the version it was judged against so a past approval stays explainable.
Six seller types seeded, 32 requirement rows.

`kyc_submissions` is keyed on the **profile**, because `user_profiles.kyc_status` is what
`EffectivePermissionResolver` reads. A decision writes that status onto *every* live seller profile of the
organisation: a listing manager who never saw the pack can work the moment Compliance clears their employer,
and stops the moment it does not.

**Submitting deliberately changes nothing about permissions.** `PENDING` fails the gate, so writing it on
submission would take a seller's listing rights away the moment they started cooperating — punishing the act
the platform is asking for. The gate moves on a *decision*.

### Behaviour verified

The seller's pack created on first look with the right checklist for a COMPANY; a document uploaded, stored
with a SHA-256 and served back as an attachment with `no-store`; the owning organisation and a `KYC_REVIEW`
holder both allowed; **a different seller refused 403, and the refusal recorded as `UNAUTHORIZED` against
their name**; the pack submitted with permissions unchanged; rejection removing `PROPERTIES_CREATE` from the
seller's effective set; a fresh pack allowed after rejection; approval restoring the permission.

### Two things worth recording

1. **An approved seller opening the page was handed a blank new pack.** `mine()` created one whenever no
   *live* pack existed, which reads as "start again" and replaces the evidence of clearance with a checklist.
   It now prefers the live pack, falls back to the most recent whatever its outcome, and only creates one for
   a seller who has never had any. Starting again is `renew()` — a decision somebody makes, not a side effect
   of looking.

2. **Nothing yet makes KYC mandatory.** A seller who never opens a pack stays at `NOT_REQUIRED` and can
   list. Deciding when clearance becomes a precondition — at onboarding, per seller type, above a listing
   count — is a policy question for the registration slice. The machinery to enforce whatever it answers is
   already in place and now demonstrably works.


---

## 14. Phase 2 / M8, second slice — the policy, and progress updates

The two things §13 left open.

### KYC becomes mandatory, as a configuration row

The gate worked; what was missing was a statement of *when* it applies. `kyc.required.seller.types` names
the seller types covered, and `KycPolicy` applies it **at the moment a seller type is set** rather than
re-deriving it on every permission resolution — the resolver runs on every login and refresh, and making it
read a configuration string and a tenant row would put two more queries on the hottest path in the
application to answer a question that changes when somebody edits an organisation.

The write is one-directional: a profile moves from `NOT_REQUIRED` into `PENDING`, never the other way. An
already-approved organisation is left alone when its type is edited, and a type that stops being covered does
not silently clear anybody — withdrawing a requirement is not the same as passing it, and that reversal
should be a Compliance decision with a name on it.

`NOT_REQUIRED` and `PENDING` are deliberately distinct: the first means nobody asked, which is honest for a
platform administrator and for a seller onboarded before the policy existed; the second means the platform
asked and is waiting. Collapsing them would make "we changed the rules" indistinguishable from "you have not
answered".

**Seller types are now the six**, with a CHECK on the column and a sentence from the service — and they are
the same six the KYC requirement catalogue is keyed by, because a type with no checklist is a seller who can
never be cleared.

### Progress updates

`listing_progress_updates` (BRD FR087–FR089), for off-plan property where the gap between listing and
completion is measured in years. Drafted then published, like a mortgage product going on offer. The date is
**when the work happened**, not when the post was written, so a developer catching up on three months of
photographs produces a timeline in the order of the work.

The photograph goes through the *ordinary* media store, not the vault — this is marketing, and being able to
say which store a thing belongs in is the point of having two.

The public timeline is a separate query from the seller's, not the same one filtered: there is no path that
can return a draft by forgetting a condition. It is also fetched separately from the listing payload, since
most listings have none and a detail page should not carry an empty array for every finished apartment.

### Behaviour verified

Setting Baobab Homes to `DEVELOPER` removed `PROPERTIES_CREATE` from its owner's effective permissions on the
next login; `COMPNAY` was refused with *"Choose one of: INDIVIDUAL, COMPANY, SACCO, DEVELOPER, AGENCY,
GOVERNMENT."*; two progress updates were created and only one published — the seller sees both with the draft
marked, the public endpoint returns only the published one, and the listing page renders it as a timeline
with the milestone and the percentage.


---

## 15. Phase 3 / M5 — valuation, and a fifth kind of actor

The first actor whose visibility is neither "my organisation" nor "everything".

### The divergence from §3.5, and why

The plan sketched this as "{@code TenantScope.visibleIds()} gains an assignment source". It is
`ValuationScope` instead, living beside the rows it governs.

`TenantScope` answers exactly one question — *which organisations may this caller see* — and every module
composes that against its own `tenant_id`. A valuer's rule is not about organisations at all: it is about a
column that exists only on `valuation_requests`. Teaching the platform's central visibility primitive the
name of one module's column would invite the next row-level actor to add a second, and the primitive would
stop meaning one thing.

What §3.5 was protecting is preserved and then some. A valuer holds **no organisation**, so
`TenantScope.visibleIds()` is empty for them and every other module's lists are closed by construction —
verified: a valuer gets 403 on listings and on enquiries, and sees exactly the one job assigned to them.

### The PI rule (FR041)

Enforced at assignment, on **both** paths. The panel skips a valuer whose cover is below the property's
value; naming that valuer explicitly is refused with *"Kevin Mutiso's indemnity cover is below this
property's value, so they cannot be assigned to it."* The manual path is where somebody would otherwise
route around it, and "the administrator picked them" is not a defence when a claim exceeds the cover.

Cover lapses on a date, so availability is computed rather than stored — and `unavailableReason` says which
of the four rules failed, because a panel screen that showed "unavailable" without saying why generates a
support call every time.

### Other decisions

- A **report is written once**. A second submission is refused: a corrected figure is a new valuation, not
  an edit of one a lender has relied on.
- **A viewing keeps two times; a valuation keeps two figures.** Market value and the forced-sale value a
  lender actually lends against, with a CHECK keeping the second at or below the first.
- **The platform cannot commission a valuation.** It runs the panel; a seller or a lender needs the figure.
  Refused with a sentence rather than silently filed under nobody.
- **Suspending a valuer leaves their open jobs with them.** Taking them away would leave a requester waiting
  on somebody who has stopped looking.

### Two defects found by building it

1. **The actor-class CHECKs still enumerated four classes.** The seeder refused to insert the new user type
   on boot — which is the constraint doing its job: introducing a fifth kind of actor should not be possible
   by editing an enum alone. Both `user_types` and `user_profiles` were widened in one migration.

2. **Every seller owner held the valuer's verbs.** The owner-group top-up grants every non-platform-only
   permission whose module admits the user type, and `VALUATIONS` admits sellers so they can see what they
   commissioned — so `VALUATIONS_WORK` reached them too. The service refused them and the screen offered
   them, which is a permission model and a service disagreeing. Fixed in the matrix, where the codebase says
   this rule belongs: a new `VALUATION_WORK` module admitting only valuers and the platform.

3. **A valuer could read the whole panel** — every competitor's insurer, policy number, sum assured and
   workload. `VALUER_PANEL_VIEW` is the door; the list is now scoped so a valuer sees one row, their own.

---

## 16. Phase 3 / M6 — auction, and what isolation costs

**BRD:** UC006. **Plan:** §4.

Auction stock must never appear in buyer search. §4 said that exclusion "is a different query path, not a
flag", and this module is where that sentence had to be paid for.

### A lot is not a listing

`auction_lots` is its own table with **no foreign key to `properties`**. That is the whole isolation
mechanism: the marketplace cannot return a lot because a lot is not in the table the marketplace reads. A
flag on `properties` would have meant every public query remembering to exclude it, forever, including the
ones nobody has written yet.

It is also honestly a different thing. A listing has an asking price and an owner who wants to sell. A lot
has a guide price, a reserve nobody outside the room may see, a date, a venue, an auctioneer with a licence,
and usually a lender exercising a statutory power of sale — the borrower is not the one bringing it. Sharing
a table would have meant a dozen columns null for every listing and mandatory for every lot.

The cost is duplication: county, town, title number, bedrooms, an image, a `search_text` and a GIN index all
exist twice. That is the price of the guarantee, and it is worth it — the alternative is a filter somebody
eventually forgets.

Verified: the lot 404s on the marketplace by reference, a "Ruaka" search returns 0, no `AU`-prefixed
reference appears among the 14 listings, and the whole flow (edit → save → publish → catalogue) puts a lot
in the public catalogue without putting it in buyer search.

### The reserve is the reason there are two records

`LotResponse` carries `reservePrice`; `PublicLot` **does not have the field**. A reserve reaching bidders
tells them exactly where to stop, which is the one number an auction depends on nobody knowing. Two records
rather than one blanked field, for the same reason the listing address split exists: a response that
sometimes carries a reserve is one somebody will eventually forget to blank. Verified — `reservePrice` is
absent from the public JSON entirely, not null.

Two CHECKs guard the pair: the reserve must be at or above the guide, and a `SCHEDULED` lot must have a
date, an auctioneer and a `published_at`.

The lot management screen marks the reserve in warning colour beside a padlock. Somebody will have that
screen open in a room with bidders in it.

### The platform does not conduct the auction

`auction_registrations` records that somebody asked to be allowed to bid and whether the auctioneer
approved them. There is no bidding. The bidding happens in a room, and a platform that implied otherwise
would be making a promise about a legal process it does not run. The deposit is likewise a *claim* —
`deposit_confirmed` stays false until somebody with a licence says otherwise, because the platform never
takes the money.

One live registration per person per lot, by partial unique index. Registering twice is a duplicate, not a
second bidder.

### The public catalogue explains itself

A first-time bidder does not know that a guide price is not an asking price, that there is a reserve they
will never see, or that they must lodge a deposit before the day. The catalogue leads with a panel saying
all three. Withholding that would make the page technically accurate and practically misleading.

### The defect building it surfaced

**The auctioneer register was platform-only, and that made publishing impossible.** `AUCTIONEERS` admitted
only platform staff, reasoning that whoever benefits from a sale should not be the one confirming the
auctioneer's licence. Correct about *maintaining* the register, wrong about *reading* it: a lot cannot be
published without naming an auctioneer, so every lender got an empty picker and no way to publish anything
at all — a dead end no error message would have explained.

The invariant that mattered is carried by the permission, not the module. `AUCTIONEERS_MANAGE` is
`platformOnly`, so widening the module lets a principal see who is licensed while adding, editing and
deactivating stay with the platform. `AUCTIONEERS_VIEW` is not platform-only, so the owner-group top-up
handed it to existing organisations on the next boot — verified in the log, three owner groups gained
exactly one permission each.

`allowed_user_types` is deliberately not re-seeded once the row exists (it is the super admin's to edit at
runtime), so this needed a migration as well as the enum default, and the migration only touches rows nobody
has since edited themselves.

### Saying no before the click

Publishing checks four things the server also checks — a future date, a venue, a named auctioneer, and a
current licence. The button is disabled with the reason beside it. This is not duplicated validation so much
as the difference between "fix the date" and "something went wrong"; a lapsed licence in particular is not
something a reader would guess from a name. The auctioneer picker carries the same fact in each option's
hint, and the register warns about licences expiring within ninety days rather than only reporting them
after they lapse.

---

## 17. Phase 4 / M9 — agents, and a signature that is evidence

**BRD:** FR160–FR161. **Plan:** §3.4.

### The third TenantScope mode was not needed

§3.4 predicted "a third `TenantScope` mode: their own listings, plus client-owned listings where they are
the acting agent". Building it showed the platform already expresses that, and more cheaply.

`PrincipalFactory.resolveVisibleTenants` keys off `profile.tenant_id`. So an approved agent bound to their
own one-person organisation resolves to exactly their own rows **with no new code at all** — no branch added
to the one class standing between two organisations' data. An independent agent *is* a small selling
organisation; their listings, own and client-owned alike, are their organisation's rows, and which of the
two a listing is, is a column on it.

The limit, stated plainly: this models an agent acting for private clients, which is what FR160–161
describe. It does not model a seller organisation engaging an outside agent to sell rows the seller owns —
that is cross-organisation visibility and would need the partnership mechanism.

### Registering grants nothing, three times over

The application endpoint is public, and it creates somebody who will eventually publish property — a
materially different thing from a buyer signing up. It is safe because three independent conditions hold:

1. the application lands `PENDING` and only the platform moves it;
2. the profile's `kyc_status` is `PENDING`, which the existing gate in `EffectivePermissionResolver` reads
   as "may not write a listing"; and
3. **there is no organisation to list into** until approval creates one.

Three rather than one, because a single gate is a single thing to get wrong. Verified: a freshly registered
agent signs in, holds `PROPERTIES_VIEW` and not `PROPERTIES_CREATE`, and has no tenant.

Approval then does all three at once — creates the organisation, generates the agreement, sets the profile
to `APPROVED` — so the platform screen says so before the button is pressed rather than after.

**An agent's approval is their KYC.** The platform has just examined a licence, an identity number and a
signed acceptance of the terms; routing the same person through the seller document pack afterwards would
ask for the same assurances twice, in a queue built for a different question.

### The signature

`signature_artifacts` records the version, a SHA-256 **of the terms text as served**, the image, its own
checksum, and the address and user agent it arrived from. Both it and `agent_agreements` are append-only by
trigger, like `audit_logs`: a signature that can be edited proves nothing about the day it was made.

Two decisions worth keeping:

- **The hash is computed on the server, never accepted.** A hash supplied by the party being bound is an
  assertion about what they agreed to. The client sends only the *version*, which is then checked — a stale
  version is refused with "the terms have changed since this page was opened", which is what happens on the
  day somebody edits the terms. Verified by sending `2025.9`.
- **The image goes in the vault, not the media store.** It is a specimen of somebody's hand. `VaultStorage`
  gained a bytes overload so the signature and the application it evidences are written in one transaction;
  a two-call flow would leave half-applications waiting for evidence that never arrives.

The platform's evidence screen shows the drawn signature, both checksums, where it came from, and whether
what was signed is still what the platform serves today.

### Every listing says whose it is

`listing_ownership` (`SELF`/`CLIENT`) plus the client's name and number on `properties`, asked of an agent
and of nobody else — a seller organisation listing its own stock is not answering this question, and
defaulting them to `SELF` would put a claim on the row nobody made. The agent is taken from the caller,
never from the request.

The client's details are on the private listing record only. They belong to somebody who never signed up to
this platform, and `PublicProperty` has no field for them at all — verified: the public JSON for an agent's
client-owned listing has no `clientOwnerName` key, and shows the agency as the seller.

### Two things building it corrected

1. **`AUCTIONEERS_MANAGE`-style thinking applied again.** Agent-only verbs went into their own `AGENT_SELF`
   module admitting `AGENT` and nobody else, so the owner-group top-up cannot hand them to every seller
   owner — the trap M5 fell into. Confirmed in the seeder log: `+0 owner perms`.

2. **A maintained counter that nothing maintained.** `agent_profiles.listings_count` was declared as a
   denormalised aggregate and then written by nothing, so the register showed 0 beside an agent with a live
   listing. Counted on read instead: a stored counter would have to be maintained by every path that
   creates, archives or reassigns a listing, and one that is only mostly maintained reads as a fact while
   being wrong.

---

## 18. Phase 4 / M10 — the vendor marketplace

**BRD:** FR170. **Plan:** §3.6, §3.2.

The other half of buying a house. Somebody whose offer has just been accepted needs a conveyancer, a
surveyor, a mover and a security firm — and the platform is the one place that knows they are at that point.

### The agent's shape, reused deliberately

A vendor applies, the platform checks them, approval creates a one-person organisation. That is M9's flow
with different fields, and reusing it was the point: `TenantService.createForAgent` became one private method
with a kind argument, `PrincipalFactory` needed nothing, and the three-gate safety story is identical —
PENDING state, a KYC standing that fails the gate, and no organisation to publish into.

The differences are what is checked (a business registration and a KRA PIN rather than an estate agent's
licence) and what they publish.

### Publication goes through Maker/Checker

§3.2 listed M10 as a queue consumer and it is the right call: a catalogue item is a **public price from a
third party, shown to somebody in the middle of the largest transaction of their life**. A seller's listing
is checked before it goes live; there is no argument for holding a conveyancer's quotation lower. Editing a
live item takes it back through the queue, for the same reason editing a live listing does.

`CatalogueApprovalHandler` is the shortest handler yet and adds no `assertMayDecide` — a vendor organisation
is one person, so there is no colleague to be the second pair of eyes and the platform is the checker.
`CATALOGUE_APPROVE` being platform-only says exactly that.

### A price is three fields

`price`, `price_from` and `price_note`, because a price is three different things depending on the trade: a
flat fee, a starting figure, or a sentence a number cannot carry — *"1.5% of the purchase price, minimum KES
35,000, plus disbursements."* Forcing all of that into one number is how a marketplace displays prices nobody
will honour. A CHECK insists a published item says **something** about price; it does not insist that
something is a number.

### Suspension takes the catalogue down — unlike an agent's listings

The difference is the point. An agent's listings are somebody's house with buyers mid-enquiry on it, and
taking them down punishes the wrong people. A vendor's catalogue is that vendor's own prices, and leaving
them on a public directory while the platform has suspended them is the platform continuing to recommend
somebody it has just stopped trusting.

Reinstatement does not put them back. Each item goes through approval again, so nothing months stale
reappears without the vendor or the platform having looked at it.

### The taxonomy is a table, and nothing deletes from it

`vendor_categories` rather than a CHECK, because the list is expected to grow as the platform learns what
buyers ask for and growing it should not be a deploy. There is no delete: a category can only be
deactivated, which stops it being offered to new applicants and leaves everybody already filed under it
exactly where they are.

### Two defects it surfaced

1. **An agent's organisation was listed as a seller.** M9 gave each approved agent a `tenants` row so they
   could reuse `TenantScope` — and put them in the platform's "Seller organisations" screen beside
   developers, offering staff management and partnerships to a one-person agency. Fixed before M10 repeated
   it: `tenants.organisation_kind` (SELLER/AGENT/VENDOR), with the seller list defaulting to SELLER and able
   to show the others. Filtered rather than hidden — a default that silently excludes rows a table holds is
   how somebody later concludes an organisation was deleted.

2. **The vendor register timed out at thirty seconds with two rows in it.** `category_name` was
   denormalised onto vendors and items but the *code* was not, so the response mapper resolved it by
   matching the name against the whole category list, once per row — and that list counts its vendors per
   category. Twenty vendors meant twenty category loads and a hundred and sixty counts. The name was already
   cached for exactly this reason; the code is now cached beside it, and the same request takes 0.35s.

### What is not here

`product_rating` from the §4 table. Ratings arrive with **M7** as one mechanism covering property, service
and product, rather than a product-only table now that a general one would have to absorb a fortnight later.

---

## 19. Phase 4 / M7 — ratings and moderation

**BRD:** post-transaction feedback. **Plan:** §4.

### One table, not three

§4 names `property_rating`, `service_rating` and `product_rating`. They are the same row with a different
subject: a score, some words, who wrote them, and whether anybody has complained. Three tables would mean
three moderation queues, three report paths and three sets of aggregates — and the moderator's screen would
union them anyway.

So `ratings` with a `subject_type` discriminator over five subjects (property, seller, agent, vendor,
catalogue item) and **no foreign key** to any of them, the way `approval_workflows` does it. The alternative
is five nullable FK columns of which exactly one is ever set.

### Verified means the platform can see the transaction

A rating of a property or a seller can be checked — the enquiry, the viewing and the offer are all rows
here, and `RatingVerifier` reads them strongest-first so the row records *how* (`OFFER` beats `SITE_VISIT`
beats `ENQUIRY`).

A rating of a **vendor cannot be**. The platform introduces the two parties and takes no part in what they
agree, so there is no record of the work. Rather than pretend otherwise, the row says unverified and the
screens say so. An unverified review is worth less than a verified one and should look it.

### Published, then moderated

Ratings appear immediately. A review site that holds every review for a day is one nobody writes to, and the
platform already puts listings and catalogue prices through Maker/Checker — applying it to opinions as well
would make the feedback loop useless.

The queue holds what somebody has **complained about**, plus anything whose text trips a configured word
list on the way in. The list is deliberately short and deliberately editable: what it buys is that the
obvious cases never appear publicly even for the minutes before somebody reports them, which is the window
that matters for a name, a phone number or an accusation. Verified: a review containing "scam" and a phone
number landed `HELD`, its author was told why, and it counted towards nothing until a moderator published it.

**Reporting does not take a review down.** A platform where one complaint removes a review is one where the
unhappiest party decides what everybody reads — so the report is recorded, the moderator decides, and the
dialog says so before somebody clicks.

### One reply, and nobody rates themselves

The subject gets a right of reply — one, not a thread. A thread under a review turns a rating into an
argument in public, and the person who wrote it has already said what they came to say.

Nobody may rate their own organisation. Not hypothetical: the review panel appears on a vendor's own public
page, and the first thing anybody does with a new page is try it on themselves.

### The aggregate has one writer, and it recomputes

`rating_summaries` is keyed by (subject_type, subject_id) and touched by exactly one method, which
**recomputes** from the ratings rather than incrementing. An increment missed once is wrong for ever; a
recompute over one subject's ratings is cheap. This is the correction for M10's defect — a counter declared
as an aggregate and maintained by nothing — applied before it could happen again.

The histogram is stored as five small integers because a subject page wants the distribution and five
columns beat a GROUP BY per page view.

### One defect it surfaced

**Sellers, agents and vendors held `RATINGS_VIEW` with nothing to view.** The permission existed and no
endpoint answered the question it exists for — *which of these are about me?* Every subject resolves to an
organisation eventually, but resolving it at read time means five repositories and a switch per row. So
`ratings.subject_tenant_id` is written when the rating is, and "reviews about us" is one indexed predicate.

The headline figure needed the same fix twice over: reading only the `SELLER` summary showed a vendor with a
published review a count of zero, because the review was filed against the vendor rather than the tenant. An
organisation's figure is now every rating about them across every subject type — the only number that
answers "how are we doing".

---

## 20. Phase 5 / M12 — internal management, buyer side

**Plan:** §4 — `ticket_assignment_rule`, `event_calendar_entry`.

Two tables, and the §4 names say exactly what they are. Neither is a new ticket system: M4 already has
enquiries, viewings and offers, and a second inbox beside them would be the platform competing with itself
for somebody's attention.

### 1. Routing, so an enquiry lands on a desk

Before this, an enquiry arrived unassigned and stayed unassigned until somebody replied — at which point it
attached itself to whoever happened to open it first. That works for a two-person seller and fails for
everybody else: **the enquiries nobody opens are exactly the ones nobody is accountable for.**

A rule matches on what is knowable when a lead arrives — county, property type, organisation — and names
who it goes to, either a person or a group shared round in turn.

- **First match wins, by explicit order.** Deliberately not "most specific wins": that is a scoring system,
  and a scoring system is one nobody can predict the behaviour of by reading the list. The page says so at
  the top, because the two readings only diverge when something goes to the wrong person.
- **Round-robin remembers rather than randomises.** Random distributes badly over five people and twenty
  leads a week, and nobody can tell by looking whether it is working.
- **Routing never fails a lead.** No rule, or a rule pointing at somebody who has left, leaves the enquiry
  unassigned exactly as before. A buyer's question must not fail to send because an administrator wrote a
  rule badly.
- **A rule can only point inside its own organisation** — routing to somebody else's staff would be a data
  leak dressed as a workflow setting.

Verified: an apartment enquiry in Nairobi landed on Peter, an earlier one on the owner, and both are visible
in the seller's inbox with the assignee shown.

### 2. A diary, because the work has dates on it

Viewings, valuations and auctions all have times, in three different modules. Somebody whose job is the week
ahead should not open three screens to find it.

The calendar is a **projection**, not a second source of truth: `project(...)` upserts one entry per source
row and the source modules call it whenever a date is set or moved. A projected entry **cannot be edited
here** and says where to change it — a diary that could be dragged while the viewing stayed put would be a
diary that lies.

Projection never throws back into its caller. A viewing must not fail to be confirmed because a diary row
could not be written; the diary is a convenience over facts that live elsewhere.

**A list by day, not a month grid.** A grid looks like a calendar and answers a question nobody has — what
this page is for is "what is happening, in order, with enough detail to prepare for it", and a grid gives
four words per cell.

### What is deliberately not projected

`VALUATION` is in the source CHECK and nothing writes it. A valuation job records when it was assigned and
when it was finished, and **neither of those is an appointment** — projecting `assigned_at` would put a fact
in the diary that is not the fact it claims to be. The value stays in the constraint so that giving a
valuation a visit time later is a service change rather than a migration.

### One defect it surfaced

**The users list returns profile ids, and the first version of the rule form treated them as user ids.**
Both test rules resolved to the wrong person — the same person, which is what made it obvious. The fix is
not defensive decoding but a better model: a rule now points at a **profile**, because a profile *is* "this
person in this organisation", which is exactly what routing means. The organisation check became exact at
the same time, where before it accepted any of the target's profiles.

---

## 21. Phase 5 / M13 — internal management, seller side

**Plan:** §4 — `property_type_config`, `promotion_package`, `commission_record`.

All three exist because something was hardcoded, unpriced or uncounted.

### 1. The property types stop being a hardcoded list

Six values lived in a Java enum, three Vue arrays and a form's validation — and the *fields a form asked
for* came from nothing at all, so a plot of land was asked how many bathrooms it has.

`property_type_configs` holds the type and the questions it implies (`has_bedrooms`, `has_plot_area`, …),
and the listing form renders from it. Adding "godown" is now a row rather than a deploy in two repositories.

Nothing deletes: a type with listings under it can only be withdrawn, which stops it being offered to new
listings and leaves the existing ones where they are.

**A defect it surfaced immediately.** The seeded types came from the Java enum's six; the frontend's
hardcoded list had a seventh, `BUNGALOW`, with listings using it. A taxonomy that omits a type in use makes
the marketplace's own facet offer a filter the listing form cannot — which is exactly the drift the table
exists to end. Corrected in a follow-up migration.

### 2. Promotion, without inventing a payment flow

There is no payments integration, and a "Buy now" button that quietly did nothing would be the worst kind of
half-feature. So a seller **asks for** a placement and the platform starts it once they have been paid,
however they were paid. The page says so in a sentence rather than leaving somebody to discover it.

Two copies of the boost, both deliberate and both with one writer:

- **package → promotion**, so repricing a package next month does not restate what somebody bought last
  month;
- **promotion → `properties.promotion_boost`**, so marketplace search sorts on a column. Search is the
  hottest read path on the platform and a join per result to find out whether somebody paid is a join per
  result.

**Placement lifts within the chosen order; it never replaces it.** Somebody who sorted by price ascending
asked for the cheapest first and gets it — the boost is the primary key only on the default ordering, where
"relevance" is the platform's to define. And the card carries a **Featured badge**: a marketplace that
reorders itself silently is one nobody can trust.

Expiry is **swept hourly, not computed at read time**. The cost is that a placement ending at 14:00 stops
showing at 14:05; the alternative costs every buyer's search a comparison per row, to answer a question that
changes a handful of times a day.

### 3. Commission, raised when a sale completes

Marking a listing sold now raises a commission from the rate in force at that moment — and **the rate is
copied onto the row**. A rate change next quarter must not silently restate what was owed last quarter, and
a report that reads a live setting to explain a historical figure is one nobody can reconcile. The
commission table shows that rate as a column, so the property is visible rather than merely true.

The rate comes from the configuration layer, which means a seller with an agreed rate carries it as a
by-exception override without a table of their own. Zero is a valid setting and raises nothing at all.

**Raising never fails the sale.** Marking a listing sold is the seller recording a fact about their
business; the platform's invoice is a consequence of it. A commission that could not be written is
recoverable; a sale that could not be recorded because of it is not.

Writing money off requires a reason — in the service, and in the table's own CHECK.

---

## 22. Phase 5 / M15 — MIS and reporting

**Plan:** §4 — "reporting views only".

### Views, not tables

A reporting table is a second copy of the truth that has to be kept in step with the first; a view is the
first, shaped differently. Nothing here can drift, because there is nothing here to drift.

Eight views — listings, leads (three tables unioned into one shape), commission, placements, valuations,
auctions, compliance, reviews — and **every one carries `tenant_id`**, even where it took a subquery to get
it. That is what `TenantScope.sqlPredicate` splices onto. A reporting view without a scoping column is a
view somebody has to remember to scope, and reporting is precisely where "somebody forgot" turns into one
organisation reading another's figures.

This is the first use of `sqlPredicate`, which existed for exactly this and had never been called. Verified:
the platform's listings report returns fourteen rows across every seller, the same report run by a seller
returns eight and names one organisation, and the platform-only compliance report 403s and is not even
offered in their catalogue.

### Declared, not composed

Every part of the SQL comes from `ReportCatalogue`: the view, the date column, the column list, the numeric
columns. The request supplies a **report code matched against the catalogue**, two dates and a limit —
nothing from a request reaches the SQL as text. A report engine that took a table name or an `ORDER BY` from
a request would be an injection surface wearing a business-intelligence hat.

### Two permissions, because they are two acts

`REPORTS_VIEW` and `REPORTS_EXPORT` are separate. Reading a figure on a screen and walking out with the rows
behind it are different things, and only one of them leaves the building. The export is audited and its CSV
escapes leading `=`, `+`, `-` and `@` — a listing titled `=cmd|…` is how an export becomes an attack on
whoever opens it in a spreadsheet.

### Still the transactional database

§4 says a read replica or warehouse, "not the transactional DB". That remains right and remains Phase 7's
job. Naming these as views is what makes the move cheap: the reports read a shape, and pointing that shape
at a replica later is a connection string rather than a rewrite.

### One defect the first report found

**`markSold` was clearing `published_at`.** It was belt-and-braces for "off the marketplace" — but the
marketplace filters on `listing_state`, so clearing it removed the listing from nothing and destroyed the
only record of when it went live. The first report to ask *how long did it take to sell* got a dash in every
row, because the subtraction had nothing to subtract from. Now kept; verified on a fresh sale, which reports
one day.

---

## 23. Phase 6 / M11 — the assistant

**Plan:** §4 — "thin orchestration over M2/M3/M4; hand-off writes an M4 ticket with the transcript
attached."

That is exactly what was built, and the plan's own wording is the whole of the design: something that reads
a question, works out which of the platform's existing capabilities answers it, and answers from real data.

### It says what it is

There is no language model wired to this deployment. `AssistantProvider` is an interface with one rules-based
implementation — the same seam M3 used for its mocked affordability provider, so putting a model behind it
later is a second class rather than a rewrite of everything that calls it.

What matters more than the seam is the honesty. **A convincing imitation of a model would be worse than
none**: somebody about to spend everything they have on a house should not be guessing whether they are
talking to staff. So the first message of every conversation says it is software and what it can do, every
reply is attributed to "Assistant (software)", and the page says it above the composer.

Its reasoning is shown, not hidden: each reply carries what it decided the question was about — *"It searched
the listings"*, *"It ran the affordability arithmetic"*. When it gets that wrong, the reader can see why it
answered as it did.

### Five things, all of them the platform's own

Search is `PublicPropertyService`. Affordability is the same arithmetic the public calculator runs. "Where do
my enquiries stand" counts the caller's own rows. The glossary answers the ten questions a first-time buyer
actually asks — guide price, reserve, LTV, DTI, leasehold, stamp duty, service charge. Nothing here is a
second implementation of anything, and it has no opinions about property.

Place names come from the **live facets** rather than a hardcoded list, so a town nobody has listed in is not
a town the assistant claims to know.

### The transcript is the point of the hand-off

A buyer who gives up on a machine and asks for a person should not have to say it all again. The hand-off
raises an **ordinary M4 enquiry** against a real listing with the whole conversation quoted in the first
message — deliberately not a new kind of ticket, because the seller's team already has an inbox and a second
one for "assistant hand-offs" would be a queue somebody has to remember to read. Verified: the seller opens
one enquiry and sees the lot.

### Two things the first test run corrected

1. **"Do you sell cars" returned thirteen houses.** Anything unrecognised fell through to search, which is
   the worst failure mode available — a confident wrong answer from something the reader cannot argue with.
   Now a question is only treated as a search if something was actually extracted (a bedroom count, a
   budget, a town it knows) or a property word appears; otherwise it says it did not follow, lists what it
   can do, and offers a person.

2. **It claimed a deposit had been added when none was given.** With no deposit, the most a lender would
   advance *is* the most property it buys — and the sentence still said "once a deposit is added". Anything
   said about somebody's money has to be exactly true or not said, so with no deposit it now says so and
   notes that every shilling put down buys more.

Also corrected on the way: "three bedroom in Kilimani" matched no bedroom filter at all, because the pattern
read digits only — and then cheerfully offered a one-bedroom flat. Written numbers are understood now.

---

## 24. Phase 7 — NFR hardening, first pass

Three items from §5 that live in this codebase rather than in the infrastructure around it.

### 1. Logs exclude PII

The sanitiser masked credentials from the beginning. It did not touch personal data, and by the end of M13
personal data was everywhere: a login response logged a buyer's email, phone and full name in the clear, and
so did every enquiry, viewing and vendor application.

**Masked, not removed** — and the distinction is the whole decision. There is no version of a password that
is useful in a log, so those stay `***`. But a support engineer reading a request trace needs to know *which*
buyer, and a log where every email has become `***` answers no question anybody actually asks. So each kind
keeps just enough to correlate two lines with each other and not enough to be worth anything to somebody who
has stolen the file:

| Kind | Becomes |
|---|---|
| Email | `w***u@example.com` |
| Phone | `•••••222` |
| Name | `Wanjiru K.` |
| ID number, KRA PIN, licence number | `***` |
| Exact address line | `***` |
| Signature's originating IP | `***` |

The last three are removed outright. An identity document is what an impersonation is built from and there is
no partial form that is both safe and useful; the exact address is the one thing the public marketplace
deliberately withholds, and writing it into a log would be the platform leaking through the back what it
protects at the front.

Verified against a real login: `w***u@example.invalid`, `•••••222`, `Wanjiru K.`

### 2. Bean Validation everywhere a body is accepted

The configuration-admin endpoints validated in the service, and four others took a request body with no
`@Valid` at all. All now validate at the edge.

The configuration request is `@NotNull` and deliberately **not** `@NotBlank`: an empty value is meaningful
for several keys — "empty means nobody is blocked" is the documented behaviour of the mandatory-KYC list, and
a blank secret is how a deployment says it has no bucket. What must not reach the column is a null, which is
exactly what an absent field used to produce.

### 3. One formatter, not forty-five

Forty-five files each called `toLocaleDateString` with their own options, so one date appeared three ways
across one workflow. `src/utils/format.ts` now owns the house style: `en-GB` for day-before-month (a platform
where 03/04 might be March or April is a platform where somebody arrives at a viewing on the wrong day),
currency passed in rather than assumed because an organisation carries its own, and whole shillings because
two trailing zeros on every figure in a table is noise.

Adopted everywhere. Forty-two files were migrated in two passes — the fifteen screens from phases 3–6 first,
then the twenty-seven earlier ones — and the production build and type check pass after each. One
`toLocaleDateString` call survives, in the diary, and it asks for a weekday name rather than formatting a
date.

The sweep was done by matching each local helper's body before removing it: a `const` whose body neither
formats nor touches component state, and never one that reads `.value` or calls an API. Two of them turned
out not to be formatters at all and were left alone.

### A defect the final sweep found

Walking every screen in the navigation as each kind of user — 22 as a seller, 33 as the platform — turned up
one 400. **Platform staff clicking "Reviews" got an error**, because the endpoint demanded an organisation
and a platform administrator has none.

"Reviews about us" has no meaning for the platform. But *"everything people are saying"* does, and it is the
useful companion to the moderation queue — one screen for what was said, one for what was objected to. So
the unrestricted caller now sees everything rather than an error, and the headline figure is the whole
platform's.

Worth recording that this was only found by walking the navigation. Every endpoint behind it had been
tested; what had not been tested was somebody clicking the thing the navigation offered them.

### What remains, and where it lives

VAPT, APM and alerting, load testing against the 50k/1k target, the read replica for M15, DR/RPO/RTO, and a
real WCAG audit. Every one of those is either an exercise against a deployed environment or a decision for
whoever owns the infrastructure — none of them is a code change waiting to be written here, and listing them
as "not started" is more useful than a partial gesture at each.
