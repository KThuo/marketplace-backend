# Quantumnex Property Portal — implementation plan for Hodi Market Place

**Source of requirements:** `Quantumnex_Property_Portal_Dev_Plan.md` (BRD v1.2, 13 Aug 2026), modules M1–M15.
**Applies to:** `hodimp-b` (Spring Boot 4.1.0 / Java 23 / Postgres / Redis, port 8085) and `hodimp-f` (Vue 3 + TS + Naive UI, port 3020).
**Companion document:** `HODI_ACCESS_MANAGEMENT_PLAN.md` — what is already built, and why it is built that way. This
document does not restate it; it audits it against the BRD and then sequences the rest.

**Status:** **Phase 0a is built and verified** (see §7 for what landed and what it cost). Sections 3.2–3.6, 3.8,
3.9 and 4 remain planning. Section 2 is a verdict against code that is built and verified.

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
| Maker/Checker segregation (FR070, FR122) | **Partial** | The only segregation that exists is `PartnershipService.assertMayApprove`, and it separates *sides* (the seller side cannot approve its own proposal), not *users* — a second user of the same seller can approve their colleague's proposal. There is no generic mechanism, so every future approval (listing, KYC, auction, promotion, vendor product) would re-implement it. | Generic `approval_workflow` with a `submitted_by <> checked_by` constraint. See §3.2. |
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

### 3.2 Generic approvals *(blocks M6, M8, M10, M13)*

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

**Open question for Compliance:** the source plan flags (correctly) that the BRD implies segregation of duties
without stating it. The `CHECK` above makes self-approval impossible platform-wide. If Compliance wants it
configurable per entity type, say so before this lands — configurable is a different table.

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

### 3.8 Consent *(with M1)*

```
consent_preference          -- user_id, channel (EMAIL|SMS|PUSH), purpose (PROMOTIONAL|TRANSACTIONAL|...), granted
consent_preference_history  -- append-only: who, when, from where, previous value
```

Separate from the profile because "prove they opted in on this date" is the requirement, and a mutable boolean on a
profile row cannot answer it. Transactional notifications are not opt-out-able and are marked as such in the purpose
list rather than by convention.

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
| **M3** Affordability | `AFFORDABILITY`, `MORTGAGE_PRODUCTS` | `affordability_check`, `mortgage_product` | **Blocked** on the OCP microservice contract |
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
| **0b** | §3.2 approvals, folding partnerships into it | 1–2 |
| **1** | M2 (property + search + media) → M1 consent → M3 (mock affordability behind the real interface) → M4 | 6–8 |
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
