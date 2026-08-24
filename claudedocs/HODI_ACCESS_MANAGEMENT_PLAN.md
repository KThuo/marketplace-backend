# Hodi Market Place — Access Management Foundation: Implementation Plan

> **Status: built and verified, 22 August 2026.** Both halves ship. `mvn -q compile` and
> `vue-tsc --noEmit` are clean, the backend boots against a fresh database and seeds itself, and the
> journeys in §10 were run end to end against a running server. What the build changed relative to this
> plan is recorded in §14, and the defects verification surfaced are in §15 — several were real, and one
> was a cross-application data leak between local projects.

**Product in one paragraph.** Hodi Market Place is a property-selling and mortgage platform. Property owners,
developers and agencies list property for sale; lending institutions (banks, SACCOs, MFIs) partner with those
sellers so their own officers can work the sellers' portfolios and finance the buyers. Buyers browse, enquire
and apply for finance in their own name.

**Scope of this plan.** The access-management substrate only — configuration/settings, users, user groups, user
types, permissions and roles, session expiry, theme configuration, profile, dashboard, and the tech stack on
both sides. The core functional domain (listings, valuations, mortgage products, applications, disbursement)
is deliberately **not** designed here; the plan states where each of those hangs off this substrate so nothing
has to be retrofitted when that plan arrives.

**Reference codebase.** `../../axis/axis-b` + `../../axis/axis-f`. The patterns are ported as-is except where
noted; the deviations are listed in §11 with reasons.

---

## 1. Locked decisions

**Backend** — Spring Boot **4.1.0**, Java **23** (`java -version` → GraalVM 23.0.2, already the default; do
not run `sdk use` before Maven). Maven. Base package `com.hodi`. Artifact `hodi-core`, name
*Hodi Marketplace Core*. Port **8085** (8077 biomatch, 8078 imara, 8079 varro, 8081 elimu, 8082 tuma,
8084 axis are taken).

> The two lines in `~/.claude/CLAUDE.md` naming Java 22.0.1-graal / 17.0.11-graal predate this stack and
> conflict with each other. Java 23 is what is installed and what every recent project (axis, tuma) builds on.

**Frontend** — Vue 3 + TypeScript + Vite + Naive UI + Pinia + vue-router + axios + `lucide-vue-next` +
echarts (`vue-echarts`). Pure CSS design tokens, **no Tailwind**. Self-hosted fonts via `@fontsource`.
Dev port **3020** (3000 imara/elimu, 3010 axis, 5173 biomatch/varro/tuma are taken), `strictPort: true`.

**Data** — Postgres `localhost:54321`, database `hodimp`. Redis `localhost:6379`, key prefix `hodimp:`.
**No per-project docker-compose** — both services already run locally. Money `BigDecimal` → `DECIMAL(18,2)`;
rates → `DECIMAL(18,4)`. All timestamps stored UTC.

**Multi-tenancy — one schema, `tenant_id` column.** Axis's schema-per-tenant is explicitly *not* ported.
Hodi's central read path is a marketplace search that spans **every** seller's listings; per-tenant schemas
would turn that into a fan-out over N schemas, and there is no per-tenant backup/export/migration requirement
driving the cost. Everything lives in `public`, one Flyway set, and isolation is enforced by a choke point
(§4.3) rather than by the connection's `search_path`.

**Tenant resolution — single host, tenant from the JWT.** One marketplace on one host. There is no
`Host → tenant` lookup and no `TenantFilter`: the tenant is a property of the authenticated principal, and
unauthenticated marketplace traffic has no tenant at all. Vanity seller subdomains remain possible later
without changing the model — they would become a *pre-filter* on the public search, not an identity boundary.

**Conventions inherited from axis/tuma (non-negotiable, every module)**
- `ApiResponse<T>` / `PagedResponse<T>` envelopes; all endpoints under `/api/v1/**`
- `status` (int) + `status_flag` (varchar) soft lifecycle. No hard deletes; `status = 5` = archived
- HashId-encoded ids in every API response — never a raw bigint on the wire
- Permission codes `{MODULE}_{ACTION}` (`_VIEW`, `_VIEW_OWN`, `_CREATE`, `_UPDATE`, `_DEACTIVATE`,
  `_ACTIVATE`, `_DELETE`, plus module-specific verbs)
- Code-driven idempotent seeder from `enums/{AppModuleEnum, AppPermissionEnum, ConfigKey, UserTypeEnum}`
- `HodiLogger` + `RequestLoggingAspect` + `ActivityLogger` + `PayloadSanitizer`; CUD activity logging with
  error bucketing; service-invoked audit log (`REQUIRES_NEW`) with field-level diff
- Module file set: `Entity`, `Repository`, `Service`, `Controller`, `dto/`
- Flyway migrations named **`VYYYYMMDDHHmmSS__description.sql`**, never `V1`/`V2`
- Every list endpoint is server-paged, takes a `PagedDataRequest`, and searches a Postgres-generated
  `search_text` column behind a `pg_trgm` GIN index; the client debounces
- Reference numbers use the ported `RrnGenerator` (12-char RRN) — no new Postgres sequences
- **Denormalise by default.** Every denormalised column is classified snapshot / label cache / maintained
  aggregate and follows that kind's rule. Never authorise on a copy
- **Backend and frontend ship together.** No turn lands backend-only

**Repository hygiene** — `hodimp-b` and `hodimp-f` are **not yet git repositories**; the enclosing
`/Users/ericthuo` is, which means a stray `git add` from here would stage the home directory. First action of
slice 0.1 is `git init` in each project directory. `.gitignore` per project ignores `/config/`
(anchored — a bare `config/` also swallows `src/main/java/com/hodi/config/`), no `.example` twin, plus
`package-lock.json` on the frontend.

---

## 2. Who the actors are

The answer to "what is a tenant" shapes everything below, so it is stated plainly.

| Actor class | Org row | `users.tenant_id` | `users.institution_id` | Sees |
|---|---|---|---|---|
| **Platform staff** | — | NULL | NULL | everything, by permission |
| **Seller staff** | `tenants` | set | NULL | own tenant only |
| **Lender staff** | `lending_institutions` | NULL | set | the portfolios of sellers their institution is partnered with |
| **Buyer** | — | NULL | NULL | only their own rows, resolved from identity |

- **A tenant is a seller organisation** — a developer, an agency, or an individual property owner trading as
  one. Nothing else is a tenant.
- **A lending institution is not a tenant.** It is a first-class organisation record whose staff are ordinary
  `users` rows tied to it by `institution_id`. Lender staff are *cross-tenant by design*: their whole job is
  reading other people's portfolios.
- **A seller may be partnered with many lenders**, and a lender with many sellers.
  `tenant_lender_partnerships` is the join, and it is the **only** thing that widens a lender user's
  visibility beyond nothing.
- **Buyers self-register** and hold no org affiliation. What they may see is decided by *identity*, not by a
  permission grant: their own saved properties, enquiries and applications, resolved from the signed-in
  principal with no id in the request.

`users` carries `CHECK (tenant_id IS NULL OR institution_id IS NULL)` — a person belongs to at most one
organisation. Platform staff and buyers are both all-NULL and are told apart by
`user_types.actor_class`, never by inference.

---

## 3. Tech stack, concretely

### 3.1 `hodimp-b` dependencies

Ported verbatim from `axis-b/pom.xml`, minus what this phase has no use for.

| Kept | Why |
|---|---|
| `spring-boot-starter-web`, `-data-jpa`, `-data-redis`, `-security`, `-validation`, `-actuator`, `-cache` | the core |
| `spring-boot-starter-thymeleaf` | HTML email templates under `resources/templates/email/` |
| `spring-aspects` + `aspectjweaver` | `RequestLoggingAspect` |
| `postgresql`, `spring-boot-starter-flyway`, `flyway-database-postgresql` | one migration set, timestamped |
| `jjwt` 0.12.6 (api / impl / jackson) | HS256 access tokens |
| `dev.samstevens.totp` 1.7.1 | TOTP 2FA |
| `org.hashids` 1.0.3 | external id obfuscation |
| `mapstruct` 1.6.3 + `lombok` + `lombok-mapstruct-binding` | annotation processors, `defaultComponentModel=spring` |
| `springdoc-openapi-starter-webmvc-ui` 2.7.0 | `/api/v1/docs` |
| `logstash-logback-encoder` 8.0 | structured JSON logs on the prod profile |
| `software.amazon.awssdk:s3` 2.28.16 (netty excluded) | avatars now, property media later |
| Testcontainers Postgres + `spring-boot-testcontainers` | isolation and permission tests need a real Postgres |

**Deferred until the functional plan lands:** `poi-ooxml` / `pdfbox` (no reports yet). No OpenSearch —
Postgres full-text + `pg_trgm` is the search story until measurement says otherwise.

Surefire is configured to include `**/*IT.java` as well as `*Test`/`*Tests`, so integration tests run on
`mvn test` instead of silently not running.

### 3.2 `hodimp-f` dependencies

`vue`, `vue-router`, `pinia`, `axios`, `naive-ui`, `lucide-vue-next`, `echarts` + `vue-echarts`, `qrcode`
(TOTP enrolment QR), `@fontsource/*`. Dev: `typescript`, `vite`, `@vitejs/plugin-vue`, `vue-tsc`,
`@types/node`, `@types/qrcode`. `npm run build` = `vue-tsc --noEmit && vite build`.

### 3.3 Three route trees, one app

```
hodimp-f
  marketplace/   public — listings, search, lender products. Lazy chunk. NEVER imports admin or platform.
  app/           back office — seller staff AND lender staff, one shell, permission- and actor-gated
  platform/      platform console — super admin
  account/       buyer's own area — saved properties, enquiries, applications
```

`/` dispatches on nothing but authentication state, because there is one host: an anonymous visitor gets the
marketplace, a signed-in buyer their account, signed-in staff their dashboard. Two conditions make one app
safe rather than merely convenient, both carried over from axis and both mandatory:

- **Chunk isolation** — the marketplace entry chunk must not statically import from `app/` or `platform/`.
  An anonymous visitor pulling in ECharts and the admin router is a page-speed regression on the one screen
  that has to be fast. Enforced by a build check on the entry chunk, not by good intentions.
- **Origin hygiene** — admin routes share an origin with unauthenticated, seller-authored content (listing
  descriptions, photos), so an XSS there reaches a staff session. Strict CSP, server-side sanitisation of all
  user-authored HTML, staff access token in memory only, distinct refresh-cookie names for staff vs buyer,
  and `Cache-Control: no-store` on `/app/*` and `/platform/*`.

---

## 4. Access control model

**Three axes. An action is allowed only if all three pass.**

```
 ①  MODULE      is the module enabled for the actor's organisation      (tenant_modules)
                AND is the user's usertype code in
                app_modules.allowed_user_types (CSV)                     ← platform controls
 ②  PERMISSION  does the user's user group carry the action code         ← org controls
 ③  VISIBILITY  is the row inside the actor's visible tenant set         ← derived, never granted
```

Permissions govern **actions**; visibility governs **data**. Neither substitutes for the other — a mortgage
officer with `APPLICATIONS_VIEW` may read applications, but only for the sellers their institution is
partnered with.

### 4.1 Tables

```
tenants                       -- SELLER organisations. The only kind of tenant.
  id, name, slug UNIQUE, tenant_ref UNIQUE,
  contact_name, contact_email, contact_phone,
  country, currency, timezone,
  onboarding_status ENUM(PENDING, ACTIVE, SUSPENDED, TERMINATED),
  suspended_at, status, status_flag, deactivation_reason,
  created_at/by, updated_at/by, search_text

lending_institutions          -- banks / SACCOs / MFIs / insurers. NOT tenants.
  id, name, slug UNIQUE, institution_ref UNIQUE,
  institution_type ENUM(BANK, SACCO, MFI, INSURER, OTHER),
  contact_name, contact_email, contact_phone, country,
  status, status_flag, deactivation_reason, …, search_text

tenant_lender_partnerships    -- the ONLY thing that lets a lender user see a seller's data
  id, tenant_id, institution_id,
  tenant_name, institution_name,          -- label caches, for list screens
  portfolio_scope ENUM(FULL, SELECTED) DEFAULT FULL,
  requested_by_user_id, requested_at, approved_by_user_id, approved_at,
  revoked_at, revoke_reason,
  status, status_flag, …
  UNIQUE (tenant_id, institution_id)

user_types                    -- GLOBAL; only the super admin creates/edits
  id, code UNIQUE, name, description,
  actor_class ENUM(PLATFORM, SELLER, LENDER, BUYER),
  sort_order, status, status_flag, …, search_text

app_modules                   -- feature-module catalogue
  id, code UNIQUE, name, description,
  allowed_user_types VARCHAR(512),        -- CSV of user_types.code, exact-token matched
  is_core, sort_order, status, status_flag, …, search_text

permissions                   -- {MODULE}_{ACTION} catalogue, seeded from AppPermissionEnum
  id, action_code UNIQUE, action_name,
  app_module_id, module_code, module_name,  -- module_* are label caches
  platform_only, status, status_flag, …

user_groups                   -- roles = arbitrary permission bundles
  id, name, description,
  user_type_id + user_type_code + user_type_name,
  tenant_id,                              -- set = seller-owned
  institution_id,                         -- set = lender-owned
  is_template, is_system,                 -- both org cols NULL + is_template = cloneable global template
  status, status_flag, deactivation_reason, …, search_text
  CHECK (tenant_id IS NULL OR institution_id IS NULL)

user_group_permissions        -- fully dynamic; any subset, any size
  user_group_id, permission_id

users
  id, first_name, last_name, email UNIQUE (lower-case, CHECK-enforced), username UNIQUE, phone, password,
  user_group_id + user_group_name,
  user_type_id + user_type_code + user_type_name,
  tenant_id + tenant_name,
  institution_id + institution_name,
  avatar_key,                             -- storage key, never a URL
  totp_enabled, totp_secret, totp_confirmed_at, sms_otp_enabled,
  locked, locked_until, failed_attempts, enabled,
  must_change_password, username_changeable,
  password_changed_at, password_expires_at,
  email_verified_at, phone_verified_at,   -- buyers self-register; staff are pre-verified
  last_login, status, status_flag, deactivation_reason, …, search_text
  CHECK (tenant_id IS NULL OR institution_id IS NULL)

tenant_modules                -- per-seller module gating
  id, tenant_id, app_module_id, module_code, enabled_at, status, …
  UNIQUE (tenant_id, app_module_id)

refresh_tokens
  id, jti UNIQUE, token_hash UNIQUE, user_id, session_class,
  expires_at, revoked, revoked_at, replaced_by, user_agent, ip_address, created_at

password_history        (id, user_id, password_hash, created_at)
password_reset_tokens   (id, user_id, code_hash, expires_at, used_at, created_at)
```

### 4.2 Rules

- **Module access is declared on the module, not on the user type.** `app_modules.allowed_user_types` holds a
  CSV of user-type **codes**; the super admin edits it per module. A user type absent from that CSV cannot
  reach the module no matter what permissions an org grants. This is what keeps a mortgage officer out of
  listing management and a listing manager out of credit decisioning — one editable field rather than two
  parallel permission catalogues. Matching is **exact-token set membership over the split CSV**, never SQL
  `LIKE`: `'%ADMIN%'` also matches `SUPER_ADMIN` and `LENDER_ADMIN`, and all three codes exist.
- **User types are global.** Seeded from `UserTypeEnum`, editable only by the super admin. Codes are stable
  identifiers once seeded — a rename means rewriting every `allowed_user_types` CSV that mentions it, so treat
  it as a migration.
- **User groups come in three flavours, distinguished by their org columns.** Both NULL + `is_template` =
  seeded global role template, visible to every org and cloneable. Both NULL without `is_template` = platform
  role. `tenant_id` set = seller-owned, private to that seller. `institution_id` set = lender-owned, private
  to that institution. Editing a template does **not** retroactively change clones.
- **Permission assignment is unconstrained.** An org owner picks any subset of the catalogue, of any size.
  Two filters narrow the *picker* only: modules enabled for their org, and modules whose
  `allowed_user_types` includes the group's user type. **No hardcoded role→permission bundles anywhere.**
- **One group per user** (`users.user_group_id`). A hybrid role is composed as its own group, not stacked.
- **Effective permissions** = group's permissions ∩ live modules ∩ modules enabled for the org ∩ modules whose
  `allowed_user_types` contains the user's type code. Resolved by `EffectivePermissionResolver` and stamped
  onto the session at login and at each refresh — **not cached under a long-lived key**. Caching an authority
  set outlives the revocation that was supposed to remove it; recomputing a handful of times per session is
  the cheaper mistake. Authorities are also re-resolved from the database on every request by
  `JwtAuthenticationFilter`, so a deactivated account or a revoked role takes effect immediately.
- **`Seller Owner`, `Lender Admin` and `Platform Super Admin` are system groups** (`is_system`) that always
  carry the full permission set for their side. Guard: an org must retain at least one active user in its
  system group — the API refuses the edit that would lock an organisation out of itself.
- **`platform_only` permissions** (`PLATFORM_ANALYTICS_VIEW`, `TENANTS_*`, `INSTITUTIONS_*`, `USER_TYPES_*`,
  `APP_MODULES_*`) are never handed out by any automatic grant, including the org system-group top-up.

### 4.3 Visibility — the `TenantScope` choke point

This is the third axis and the one place a mistake leaks another organisation's data, so it gets exactly one
implementation that every query goes through.

```java
// null  = unrestricted (platform staff)
// set   = exactly these tenants
// empty = nothing at all (a lender with no active partnership)
Set<Long> TenantScope.visibleTenantIds()
```

| Actor | Resolves to |
|---|---|
| Platform staff | `null` — unrestricted |
| Seller staff | `{ own tenantId }` |
| Lender staff | tenant ids of **active** rows in `tenant_lender_partnerships` for their institution |
| Buyer | not tenant-scoped at all — identity-scoped, see below |

- `TenantScope.restrict("tenantId")` returns a JPA `Specification` composable with `SearchSpecs.allOf`;
  it returns `null` when unrestricted and an **always-false** predicate when the set is empty. Returning no
  predicate for an empty set is the bug that shows a stranded user everything.
- `TenantScope.assertAllowed(tenantId)` guards writes and throws 403 with a message that does not confirm the
  row exists.
- `TenantScope.sqlPredicate("tenant_id")` for the reporting paths that cannot use a Specification. The ids are
  the caller's own resolved scope and are longs, so interpolation is safe.
- **The lender's partnership set is resolved once per session**, into the principal, alongside the permission
  set. A partnership revoked mid-session is caught because the principal is rebuilt on every request.
- **Buyers are identity-scoped, not tenant-scoped.** Every buyer-facing read filters on
  `buyer_user_id = AuthContext.userId()` and takes **no id from the request**. A buyer endpoint that accepts
  an id it then authorises is the shape this rule exists to forbid.
- **Stranded actors are told so.** A lender user whose last partnership is revoked, or a seller user whose
  tenant is suspended, is blocked at login with a clear message rather than shown empty tables that look like
  missing data.

### 4.4 Seeded user types

| Code | Actor class | Description |
|---|---|---|
| `SUPER_ADMIN` | PLATFORM | Full platform control — tenants, institutions, modules, global config |
| `SUPPORT_ADMIN` | PLATFORM | Read-mostly troubleshooting |
| `PLATFORM_AUDITOR` | PLATFORM | Read-only audit and reporting |
| `SELLER_OWNER` | SELLER | Full control of one seller org: staff, roles, settings, portfolio |
| `LISTING_MANAGER` | SELLER | Property records and media |
| `SALES_AGENT` | SELLER | Enquiries, viewings, buyer conversations |
| `LENDER_ADMIN` | LENDER | Full control of one institution: staff, roles, partnerships |
| `MORTGAGE_OFFICER` | LENDER | Works applications against partnered sellers' portfolios |
| `CREDIT_ANALYST` | LENDER | Assessment and decisioning |
| `BUYER` | BUYER | Signs in to see their own saved properties, enquiries and applications |

### 4.5 Seeded modules and permission catalogue

Only this phase's modules are declared; each later stage appends its own constants.

| Module | `allowed_user_types` (seeded default) | Action codes |
|---|---|---|
| `TENANTS` | `SUPER_ADMIN,SUPPORT_ADMIN` | `_VIEW _CREATE _UPDATE _DEACTIVATE _ACTIVATE _SUSPEND _REINSTATE`, `PLATFORM_ANALYTICS_VIEW` |
| `INSTITUTIONS` | `SUPER_ADMIN,SUPPORT_ADMIN` | `_VIEW _CREATE _UPDATE _DEACTIVATE _ACTIVATE` |
| `PARTNERSHIPS` | `SUPER_ADMIN,SELLER_OWNER,LENDER_ADMIN` | `_VIEW _REQUEST _APPROVE _REVOKE` |
| `USER_TYPES` | `SUPER_ADMIN` | `_VIEW _CREATE _UPDATE _DEACTIVATE _ACTIVATE _DELETE` |
| `APP_MODULES` | `SUPER_ADMIN` | `_VIEW _UPDATE` (incl. the `allowed_user_types` editor) |
| `PERMISSIONS` | `SUPER_ADMIN` | `_VIEW` |
| `USER_GROUPS` | `SUPER_ADMIN,SELLER_OWNER,LENDER_ADMIN` | `_VIEW _CREATE _UPDATE _CLONE _DEACTIVATE _ACTIVATE _DELETE` |
| `USERS` | `SUPER_ADMIN,SUPPORT_ADMIN,SELLER_OWNER,LENDER_ADMIN` | `_VIEW _CREATE _UPDATE _DEACTIVATE _ACTIVATE _RESET_PASSWORD` |
| `APP_SETTINGS` | `SUPER_ADMIN,SELLER_OWNER,LENDER_ADMIN` | `_VIEW _UPDATE _OVERRIDE _VIEW_SECRET` |
| `AUDIT` | `SUPER_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LENDER_ADMIN` | `_VIEW` |
| `DASHBOARD` | all staff types | `DASHBOARD_VIEW` |
| `BUYER_PORTAL` | `BUYER` | `BUYER_PORTAL_ACCESS` — admits a buyer to their own area and nothing else |

`_DELETE` is a soft archive to `status = 5`, never a hard delete. Deactivated rows offer **Activate** and
**Delete**; lists hide `status = 5`.

### 4.6 Seeded global role templates

Cloneable by any org, editable after cloning: *Listing Manager*, *Sales Agent*, *Mortgage Officer*,
*Credit Analyst*. Plus three system groups that are not templates: *Platform Super Admin*, *Seller Owner*,
*Lender Admin*.

### 4.7 Frontend of the access model

- **User form** — user type select (filtered to the actor classes the creator may create) → user group select
  (groups of that type: global templates + own org's) → org affiliation, which is **derived, not chosen**: a
  seller owner creating a user can only create users in their own tenant. A super admin picks the org
  explicitly, and the picker switches between tenants and institutions on the user type's `actor_class`.
- **User group form** — name/description + user type + permission picker grouped by module, select-all per
  module, live count. Modules hidden when not enabled for the org or when the group's user type is not in the
  module's `allowed_user_types`.
- **Platform console** — user-type CRUD; on each app module an `allowed_user_types` multi-select persisting as
  CSV; the permission catalogue read-only.
- **Guards** — router and nav read the effective permission set from `authStore`. A lender-only route
  additionally requires a non-empty partnership set and renders a "no partnerships yet" state otherwise.
- **UI vocabulary** — "user group" and "user type", never "role", in user-facing copy. Names, not codes.
  Multi-step forms use a step wizard with a simple progress bar.

---

## 5. Session handling — sliding idle timeout

Implements `Plans/session_handling.md` in intent, as axis does. The point: with a short access token and a
long refresh token the client silently refreshes on every 401, the real session lasts as long as the refresh
token and the configured timeout is cosmetic. We want a true **sliding idle window `W`** — no interaction for
`W` logs you out, continuous use never does.

Both halves are required. The **server** makes the refresh token the idle enforcer (its TTL *is* `W`, rotated
on every use) — the hard backstop. The **client** drives the slide.

**Server** (`modules/auth`)
- `W` comes from configuration per **session class**, so it is tunable at runtime, not a compiled constant:
  `security.session.timeout.minutes.admin` (default 20), `…​.buyer` (default 1440),
  `security.session.refresh.grace.seconds` (default 60).
- Access token TTL = `W`. Refresh token TTL = `W` + grace, so a refresh fired at the boundary still lands.
  The grace does **not** extend the visible session — the client's own watchdog logs out at exactly `W`.
- Refresh tokens are **opaque random strings**, not JWTs — that is what makes them revocable. Row keeps a
  SHA-256 hash (high-entropy token looked up on every refresh; bcrypt would be the wrong tool), plus `jti`,
  `session_class`, `expires_at`, `revoked`, `replaced_by`, user agent and IP.
- **Rotated on every refresh**: validate → require usable → revoke the old → issue a fresh pair. A revoked
  token presented again means either a stale tab or a stolen token; we cannot tell, so **every session for
  that user is revoked** — in a separate transaction, because the method then throws.
- Login and refresh responses both carry `sessionTimeoutSeconds`; the client never hardcodes `W`.
- A `@Scheduled` reaper clears expired rows. Housekeeping, not a control — `isUsable()` already checks the clock.

**Transport** — refresh token in an httpOnly, SameSite cookie; **two cookie names** so a buyer session and a
staff session can coexist in one browser without either clobbering the other:

| Session class | Cookie | Default `W` |
|---|---|---|
| `ADMIN` (platform, seller, lender back office) | `hodi_rt` | 20 min |
| `BUYER` (marketplace / account) | `hodi_brt` | 24 h |

`POST /api/v1/auth/refresh` takes an optional `sessionClass`, reads the matching cookie, and defaults to
`ADMIN`. `localStorage` holds only the non-secret timing stamps — never a token.

**Access token claims and their verification.** `sub` (user id), `username`, `tid` (tenant id, with a `0`
sentinel for "no tenant" — an *absent* claim would make every pre-change token valid), `sessionClass`,
`iss`. `JwtService.parse` verifies signature, expiry **and issuer**. `JwtAuthenticationFilter` additionally
checks the token is not blacklisted, that the username still names the same row (catches a reused id after a
restore), and that the loaded user's `tenant_id` matches the token's. Authorities are always resolved from the
database, never read from a claim.

**Client** (`hodimp-f`)
- Shared `localStorage` stamps (`hodimp:last_activity`, `hodimp:last_refresh`, `hodimp:session_timeout_s`) so
  behaviour is correct across tabs: activity in any tab keeps all tabs alive; idle logout fires only when
  every tab is idle.
- Passive listeners on `mousemove`/`mousedown`/`keydown`/`scroll`/`touchstart`, throttled to 5s, plus a
  `visibilitychange` re-evaluation so a backgrounded tab is judged the moment it returns.
- Single-flight `refreshSession()` shared by the idle watchdog and the axios 401 interceptor, so concurrent
  callers await one in-flight rotation instead of racing.
- 1s tick while authenticated: `idle >= W` → logout; `idle >= W - min(60s, W·0.25)` → "Still there?" modal
  with a live countdown and **deliberately no refresh** (refreshing there would make the timeout
  unreachable); otherwise refresh when `now - lastRefresh >= W - min(60s, W·0.20)`. Lead times are clamped
  fractions of `W` so a 2-minute test window still behaves.
- Mounted once in the authenticated layout so it tears down on logout. On refresh failure → clear session →
  `/login?expired=1`, and the login page explains why.

---

## 6. Configuration and settings

**Global by default, org override by exception.** One row per key in `configurations`, managed by the super
admin. A tenant may override a key **only** if it is explicitly marked overridable; writing an override for a
non-overridable key is **rejected at the API**, not silently ignored.

```
configurations                -- the global system config
  id, category, config_key UNIQUE, config_value, value_type,
  label, description, is_secret, is_overridable, editable,
  status, status_flag, created_at/by, updated_at/by, search_text

tenant_configurations         -- sparse: a row exists ONLY where a tenant overrides
  id, tenant_id, category, config_key, config_value, value_type, is_secret,
  status, status_flag, updated_at/by
  UNIQUE (tenant_id, config_key)

configuration_logs            -- every change, both layers
  id, config_key, scope ENUM(GLOBAL, TENANT), tenant_id,
  previous_value, new_value, reason, actor_username, created_at
```

`ConfigurationService.resolve(key)` = **tenant override ?? global**. The `overridable` flag is checked
*before* the tenant lookup: a non-overridable key costs no second query, and a stray tenant row — hand-inserted
or restored from a backup taken when the flag differed — can never take effect. Both layers are Redis-cached
under **tenant-prefixed** keys; a cache key without a tenant prefix is a cross-tenant leak, and that gets its
own test. Which layer won is logged at debug, because "which key did it actually use" is the first question
when a gateway call fails.

Secrets are encrypted at rest (AES-256-GCM via `EncryptionUtil`), masked in API responses and logs, and
revealed only through `POST /configurations/reveal/{key}` behind `APP_SETTINGS_VIEW_SECRET`. A value that will
not decrypt logs loudly and falls back to the default rather than 500-ing the request.

**Key groups seeded in this phase** (`ConfigKey` enum is the single source of truth; keys are not duplicated
in `AppConstant`):

| Category | Keys | Overridable |
|---|---|---|
| `AUTH` | the three session/grace keys; `auth.totp.enabled`, `auth.totp.required`; password min length / require symbol / number / upper / history count / expiry days / reset TTL; `auth.login.max.attempts`, `auth.login.lockout.minutes` | **never** — platform security policy |
| `THEME` | primary, accent, accent-light, ink, logo URL, logo-mark URL, favicon URL, dark enabled, field hints | never (this phase — see §7) |
| `GENERAL` | company name / email / phone / address, app host, public URL, money decimal places | never |
| `NOTIFY` | notify base URL, API key (secret), SMS sender id, email domain, SMS/email enabled | **yes** — a seller may legitimately send from their own sender id |
| `STORAGE` | S3 bucket / endpoint / access key (secret) / secret key (secret), local dir, local base URL | never |
| `BUYER` | self-registration enabled, email verification required, phone verification required | never |

A tenant weakening its own session window, lockout threshold or verification requirement would defeat the
control, which is why the overridable set is deliberately tiny.

**Settings screens** (`pages/settings/`, tabbed): *Global config* (super admin, grouped by category, typed
editors), *Overrides* (org owner, only overridable keys, showing which layer currently wins), *Appearance*
(§7), *Change log*.

---

## 7. Theme configuration

One marketplace brand, driven from the database — **no hardcoded colours anywhere**.

- Brand tokens live in `configurations` under the `THEME` category and are served by
  **`GET /api/v1/public/theme`** — unauthenticated, because the login screen and the marketplace must be
  themed before anyone signs in. Returns `{ primary, accent, accentLight, ink, logoUrl, logoMarkUrl,
  faviconUrl, appName, darkEnabled, fieldHints, contactEmail, contactPhone, contactAddress }`.
- `themeStore` (Pinia) applies two independent axes:
  1. **Brand** — the configured colours written onto `:root` as the CSS custom properties declared in
     `styles/theme.css` (`--brand`, `--accent`, the `--ink-*` ramp, and recomputed gradients), plus Naive UI
     `GlobalThemeOverrides`.
  2. **Mode** — light | dark, persisted to `localStorage`, defaulting to the OS setting, stamped as
     `data-theme` on `<html>` and switching Naive UI's base theme.
- The `--ink-*` ramp ordering matters: the *configured* ink is `--ink-800`, with 850/900 as darker runtime
  derivations. CSS defaults that disagree cause a visible colour shift between first paint and hydration.
- The logo is an **inline SVG** component pair (`BrandMark`, `BrandLockup`) so it recolours with the runtime
  brand, stays crisp at any size, costs no extra request and remains readable to screen readers. `logoUrl` is
  the escape hatch for an uploaded raster.
- **Not tenant-overridable in this phase.** Hodi is one marketplace with one brand; per-seller theming only
  becomes meaningful alongside vanity seller subdomains. When that arrives, the change is flipping the
  `THEME_*` keys to `is_overridable` and having `/public/theme` accept a seller slug — no structural change.

---

## 8. Profile

`GET /api/v1/auth/me` is the one endpoint the client's own identity comes from, returning the user, their
effective permission set, actor class, org affiliation, and — for lender staff — their partnership set.

`pages/profile/`:
- **`ProfileHero`** — name, avatar upload (`POST /auth/avatar`; stores a **storage key**, never a URL, so
  moving between local disk and S3 does not freeze one deployment's arrangement into the data), org, user type.
- **`SecuritySection`** — change password (`POST /auth/change-password`), last login, password age and expiry.
  Policy is fetched from the public `GET /auth/password-policy` so the client's validation cannot drift from
  the server's.
- **`TwoFactorRow`** — TOTP enrol (`/auth/totp/setup` → QR via `qrcode` → `/auth/totp/confirm`), disable, and
  an SMS-OTP toggle. `auth.totp.required = true` forces enrolment before login completes.
- **`AccessSection`** — read-only: user type, user group, permission count grouped by module, org
  affiliation, and for lender staff the sellers they may see. This is the screen that answers "why can't I
  see X", so it names the axis that blocked it rather than just listing grants.
- **`ChangePasswordView`** — reachable while a forced change is outstanding, and the *only* thing reachable
  then. `PasswordChangeRequiredFilter` enforces it server-side (a temporary or expired password authenticates
  and may do nothing else) and the router guard mirrors it. Without both, a user navigates past the screen and
  keeps using a temporary credential, which is the whole point of the flag.
- **Username changeability** — a user may choose their own username during their **first session only**
  (`username_changeable`, set by the login that finds `last_login` null and cleared by the next). The username
  is what names a person in every `created_by`, `updated_by` and audit row; letting someone rename after acting
  makes their trail hard to follow.

---

## 9. Dashboard

**One endpoint, tailored by actor and permissions**: `GET /api/v1/dashboard`, gated on `DASHBOARD_VIEW`.
`DashboardService` assembles only the cards the caller may see, so the response never contains a figure the
caller is not entitled to. The frontend `DashboardView` is a dispatcher that renders
`PlatformDashboard` / `SellerDashboard` / `LenderDashboard`, with the buyer's equivalent living in the account
tree.

**What it can honestly show in this phase** — access-management facts, because that is all the data that
exists yet:

| Actor | Cards |
|---|---|
| Platform | tenants by status, institutions, active partnerships, users by actor class, sign-ins and failed sign-ins over 30 days, pending partnership approvals, config changes |
| Seller | staff count and by group, partnered lenders, pending partnership requests, recent staff activity |
| Lender | partnered sellers, staff count and by group, pending partnership requests |
| Buyer | profile completeness, verification state, saved searches (0 until listings exist) |

This is explicitly a **skeleton**: the figures that matter — listings, enquiries, applications, disbursements,
conversion — are added as the functional slices land, each one appending to the same endpoint rather than
introducing a second dashboard API. Cards are permission-gated individually, so a card added later does not
leak to an actor who should not see it.

---

## 10. Build slices

The unit of work is a **vertical slice**: one module delivered from migration to screen, backend first then
frontend **in the same turn**, so the frontend is written against a real endpoint and a real response shape.
Neither repository runs ahead of the other.

**Definition of done for every slice** — all of it, or the slice is not done:

| Backend (`hodimp-b`) | Frontend (`hodimp-f`) |
|---|---|
| timestamped Flyway migration | `src/services/<module>.ts` client functions |
| `Entity`, `Repository`, `Service`, `Controller`, `dto/` | Pinia store with loading/error state where state is shared |
| permission codes in `AppPermissionEnum` + seeder + the module's `allowed_user_types` | route + nav entry, gated on those codes |
| audit + activity logging wired | list screen: server paging, debounced search, empty/loading/error states |
| `TenantScope` applied if the entity is tenant-bearing | create/edit form surfacing server-side validation |
| `search_text` + GIN index for any list | `vue-tsc --noEmit` clean |
| `mvn -q compile` clean | — |

Plus, for the slice as a whole: **one user-visible journey works end to end in the running app** — not just a
green test. Every turn's summary names that journey.

| # | Slice | Journey it must make work |
|---|---|---|
| **0.1** | Skeleton & shell — `git init`, pom, `config/`, logback, Flyway baseline, `common/` (`ApiResponse`, `PagedResponse`, `PagedDataRequest`, `EncryptionUtil`, `RefGenerator`, `RrnGenerator`, exceptions + `GlobalExceptionHandler`), `security/hashid`, `logging/`, `SeederService` skeleton. FE: Vite/TS/Naive UI, CSS tokens light+dark, `AdminLayout`/`AuthLayout`/`MarketplaceLayout`/`AccountLayout`, ui primitives (`DataTable`, `PageHeader`, `AppModal`, `StatusBadge`, `RowActions`, `TableToolbar`, `DeactivateModal`, `EmptyState`, `SectionCard`, `OtpInput`, `AppPagination`), `services/api.ts`, router shell | app boots, health endpoint answers, shell renders in both themes |
| **0.2** | Identity tables + auth — `user_types`, `app_modules`, `permissions`, `user_groups`, `users`, `refresh_tokens`, `password_history`, `password_reset_tokens`; JWT + blacklist, rotating refresh, session classes, `PrincipalFactory`, `EffectivePermissionResolver`, password policy/history/expiry, lockout, `PasswordChangeRequiredFilter`; login / verify-otp / refresh / logout / me / change-password / forgot / reset / password-policy. FE: login, OTP, forgot, reset, forced change, `authStore`, axios single-flight refresh, `useIdleSession` + warning modal, `/login?expired=1` | the seeded super admin signs in, is forced to change a temporary password, goes idle, sees the warning, and is logged out at `W` |
| **0.3** | Configurations & settings — global config, tenant overrides, change log, secret encryption/mask/reveal, Redis cache with tenant-prefixed keys. FE: Settings tabs (Global, Overrides, Log) | super admin changes the session window in the UI and the next login honours it |
| **0.4** | Theme — public `/theme`, `themeStore`, `BrandMark`/`BrandLockup`, Appearance tab writing `THEME_*` | changing the accent colour in Appearance re-themes the login screen with no redeploy |
| **0.5** | User types, app modules, permissions — platform CRUD, `allowed_user_types` multi-select persisting CSV, permission catalogue endpoint | super admin removes a user type from a module's CSV and a holder of that type loses the nav entry on next refresh |
| **0.6** | User groups — global templates, clone, permission picker grouped by module | a seller owner clones *Sales Agent*, drops two permissions, and saves |
| **0.7** | Users — CRUD, org affiliation derived from the actor, reset password, deactivate/activate/delete, system-group lock-out guard | a seller owner creates an agent who signs in and sees exactly the granted nav |
| **0.8** | Tenants, institutions, partnerships — lifecycle (create / activate / suspend / reinstate / terminate), `TenantScope` choke point, partnership request→approve→revoke | super admin onboards a seller and a bank, links them, and a mortgage officer sees that seller and no other |
| **0.9** | Buyer self-registration — register, email/phone verification, `BUYER` session class + `hodi_brt`, `BUYER_PORTAL_ACCESS`, account route tree, identity-scoped reads | a stranger registers, verifies, signs in, and lands on their own account page |
| **0.10** | Profile, dashboard, audit — profile screens, avatar, TOTP + SMS OTP, `AccessSection`, `/dashboard` with role-dispatched views, audit log list | every actor class signs in and lands on a dashboard populated with figures they are entitled to |

---

## 11. Documented deviations from axis

1. **One schema, `tenant_id` column** instead of schema-per-tenant. The marketplace search spans every seller,
   and there is no per-tenant backup/export/migration requirement to pay for. Consequence: no
   `TenantProvisioningService`, no `TenantMigrationRunner`, no `MultiTenantConnectionProvider`, one Flyway
   set — and `TenantScope` becomes load-bearing in a way it is not in axis, which is why §4.3 gives it the
   choke-point treatment.
2. **No host-based tenant resolution.** Single host; the tenant comes from the JWT. Consequence: no
   `TenantFilter`, no `PlatformHosts`, no `tenants.domain`. `JwtAuthenticationFilter`'s tenant/schema
   cross-checks reduce to one check — the token's `tid` against the loaded user's `tenant_id`.
3. **Lending institutions are a second organisation kind that is not a tenant.** Axis has one org axis; Hodi
   has two, plus a partnership join that grants cross-tenant read. This is the one genuinely new piece of
   design, and it is why visibility is a resolved *set* rather than a single id.
4. **No location/branch scoping.** Axis's `LocationScope` is not ported. `tenant_id` (widened by partnership
   for lenders) is the only row boundary. If a branch axis is wanted later it slots in beside `TenantScope`
   as a second composable specification — but every query written now must go through the choke point for
   that to be a small change rather than an audit.
5. **Theme is global, not per-tenant.** One marketplace brand. Flipping the `THEME_*` keys to overridable is
   the whole change if per-seller branding is wanted later.
6. **Notifications are out of scope for this plan.** Axis lands its notification module in the same stage; the
   user's request did not include it and it has no access-management dependency. Flagged in §12.

---

## 12. Open questions for the functional plan

Not blocking this substrate; each one has a stated default so work can start.

1. **Individual property owners.** Is a private seller their own tenant, or a sub-account under an agency
   tenant? *Default: their own tenant, with a lighter onboarding path.*
2. **Module gating for lending institutions.** Sellers have `tenant_modules`; institutions are currently gated
   by user type alone. If lenders get plans or packages, an `institution_modules` table mirrors it exactly.
   *Default: user-type gating only for now.*
3. **`portfolio_scope = SELECTED`.** The column exists so a seller can partner with a lender over part of a
   portfolio, but the per-listing join belongs with the listings design. *Default: `FULL` until listings exist.*
4. **Buyer ↔ lender visibility.** May a lender officer see a buyer's application only when it came through a
   partnered seller, or does an application create its own visibility grant? This decides whether the
   application table is tenant-bearing or gets its own scope rule. *Needs an answer with the applications
   design; leaning tenant-bearing.*
5. **Notifications.** Every user-visible event should come from a `notification_templates` row rather than
   hardcoded copy, and buyers/officers both need an inbox. Port `imara-b/modules/notifications` as its own
   slice once the events exist.
6. **KYC and document verification.** Buyer identity documents and seller title documents are both sensitive
   reads that will need their own permission codes rather than riding on `_VIEW`.
7. **Impersonation.** Support staff troubleshooting a seller account is a common need and a common breach
   vector. If wanted, it is consent-gated and fully audited, never a silent capability.

---

## 13. Tests that are not optional

Testcontainers Postgres — H2 cannot model `pg_trgm` or the generated `search_text` columns.

- **Tenant isolation** — two sellers, writes to both, assert neither reads the other through any service entry
  point.
- **Lender visibility** — an officer sees exactly the partnered sellers; revoking the partnership removes
  access on the next request, not at token expiry.
- **Buyer identity scoping** — a buyer cannot read another buyer's rows even with a guessed id.
- **Permission enforcement per endpoint** — a parameterised test over every controller method asserting the
  declared authority is actually required.
- **Three-axis intersection** — a granted permission is *not* effective when the module is disabled for the
  org, and *not* effective when the user's type is absent from `allowed_user_types`.
- **Refresh rotation and reuse detection** — a rotated token is single-use; presenting a revoked token revokes
  every session for that user.
- **Sliding window** — a session with no activity dies at `W`; a session refreshed inside the window does not.
- **Config layering** — override wins for an overridable key, is ignored for a non-overridable one even when a
  row exists, and cache keys are tenant-prefixed.
- **Lock-out guard** — the API refuses the edit that would leave an org with no active member of its system
  group.


---

## 14. What the build changed relative to this plan

Written after the fact, because a plan that quietly disagrees with the code is worse than no plan.

1. **`sessions_valid_from` was added to `users`.** Not in the original design, and needed: revoking
   refresh tokens stops a session being *renewed* but leaves the access token already in somebody's
   hands valid for the rest of its window. So "change your password" and "sign out everywhere" both left
   a usable credential alive for up to twenty minutes — exactly the window that matters when either
   action is a response to a suspected compromise. The column is a per-user cutoff; any token minted
   before it is refused.
2. **Access tokens carry a millisecond issue time (`imt`).** The registered `iat` claim is defined in
   whole seconds, which makes the cutoff comparison irreducibly ambiguous for one second — see §15.
3. **`BuyerVerificationRequiredFilter` is its own filter**, not a check inside the JWT filter. The
   original put it there and an unverified buyer then authenticated *nothing*, `/auth/me` included, so
   the client could not discover why it was refused.
4. **`AUDIT` is not a core module.** Everything else in this phase administers the platform or the
   organisation itself, so per-tenant module gating had nothing it was allowed to switch off — the code
   existed with nothing to act on. Audit is the honest first candidate for an optional module.
5. **`TENANTS` admits `SELLER_OWNER`, and `INSTITUTIONS` admits lender and seller staff.** The
   self-service permissions (`TENANT_SELF_VIEW`, `INSTITUTION_SELF_VIEW`) live in those modules, so
   excluding their own actors made the seeded owner group internally inconsistent.
6. **Redis cache keys are namespaced per application.** See §15 — this one was not a Hodi bug so much as
   a latent one across every project sharing the local Redis.
7. **The marketplace is an honest placeholder.** It carries the public shell, the theme bootstrap and
   buyer sign-up, and says plainly that listings are not built. No fake listings.

---

## 15. Defects verification surfaced

Each of these was found by running the thing rather than by reading it, which is the argument for the
end-to-end journeys in §10 being part of the definition of done rather than a follow-up.

| # | Defect | Why it happened |
|---|---|---|
| 1 | **Cross-application Redis collision.** Booting Hodi against a Redis that axis had used failed every login with *"Could not resolve type id `com.axis.…ConfigurationCache$GlobalEntry`"*. | Spring's `RedisCacheManager` keys entries as `<cacheName>::<key>` with nothing identifying the application, and every project in this family declares a cache called `configValues`. The loud failure was the lucky case: two applications whose cached record shapes *did* line up would have deserialized each other's values happily, and one platform's configuration would have silently answered the other's reads. **Fixed twice: first by prefixing the key at the cache manager, then properly — see §16 — by putting the application's name in the region name itself.** Worth checking in the sibling projects, which have the same shape. |
| 2 | **Every first partnership proposal was rejected** as "already waiting for a decision". | The guard ran against the freshly-built object, whose builder defaults (`status = 1`, null approval and revocation stamps) are precisely what `isPending()` tests for. Now guarded on `getId() != null`. |
| 3 | **The token cutoff had a one-second hole, and closing it broke fresh logins.** | `iat` is second-granular. `isBefore` left a revoked token valid when the revoke landed in the same second it was issued; `!isAfter` closed that and rejected the brand-new token from the login somebody performs immediately after revoking. Both are the same root cause — one second of ambiguity no operator can resolve — so the fix was to remove the ambiguity with a millisecond claim rather than to choose which side to be wrong on. |
| 4 | **A user-facing message shipped a literal `%s`.** | `.formatted()` binds to the string literal immediately before it, so on a concatenated message it applied to the half with no placeholder in it. A scan found no other instances. |
| 5 | **Seeded role templates granted permissions their own user type could not hold.** | The template referenced `TENANT_SELF_VIEW` for a user type the module did not admit, so cloning failed outright and the resolver would have dropped the authority at login — a group that reads as granted and behaves as empty. Fixed on both sides (§14 items 4 and 5). |
| 6 | Vue warned once per row on the settings screens; a fresh install 404'd on `/favicon.ico`. | `v-model` into a record that starts empty passes `undefined` to a prop declared as a required `String`. Fixed in the two components rather than at every call site. |

### Verified end to end

Against a running server, from an empty database: three-axis resolution · seller isolation (two sellers,
neither sees the other) · staff never exposed across organisations, *including* between partnered ones ·
platform-only endpoints refused · privilege escalation refused (`TENANTS_CREATE` into a seller's own
group) · template clone → staff → the agent's effective permissions · the lock-out guard · partnership
propose → self-approval refused → approve → visible on the same token → revoke → gone on the same token ·
buyer self-registration, indistinguishable duplicate registration, attempt counting, unverified access
refused but `/auth/me` allowed · config layering, platform-policy override refused, one seller's override
invisible to another · secret masking in list, log and at rest, with an audited reveal · per-organisation
module gating on and off, core module refused · the access-token cutoff · the audit trail.

### Not verifiable without more infrastructure

- **Delivery of buyer verification codes.** The send is skipped when no notify API key is configured, so
  the code never reaches an inbox. The failure paths — wrong code, attempt counting, retirement of a
  superseded challenge — were verified; the delivery leg needs a gateway.
- **Concurrency.** The refresh-token reuse-detection path and the single-flight client refresh were
  exercised serially, not under real concurrent load.


---

## 16. The application name is in every cache region

The first fix for §15.1 prefixed the *key* at the cache-manager level. That stopped the collision but left
the **region** anonymous, so the name was still unqualified everywhere a region name is read by a person:
in the `@Cacheable` annotation, in Spring's own cache logging, in `CacheManager.getCacheNames()`, and in
the error text when a region is missing.

The name now lives in the region:

```
com.hodi.config.CacheRegions
  APP                    = "hodimp"          ← spelled exactly once
  CONFIG_VALUES          = "hodimp:configValues"
  TENANT_CONFIG_VALUES   = "hodimp:tenantConfigValues"
  BLACKLIST_KEYS         = "hodimp:blacklist:"   (raw RedisTemplate namespace, not a cache region)
  ALL                    = { CONFIG_VALUES, TENANT_CONFIG_VALUES }
```

Three supporting changes, each closing a way for this to regress:

1. **`RedisConfig` registers `CacheRegions.ALL` and calls `disableCreateOnMissingCache()`.** A
   `@Cacheable("configValues")` that skipped `CacheRegions` — i.e. one without the app name — now fails on
   first use with "cannot find cache" instead of quietly creating an unnamespaced region. The manager's
   `computePrefixWith` is back to just `name + "::"`, because prefixing there as well would double it.
2. **There is no `hodimp.redis.prefix` property.** Region names appear in annotations, so they must be
   compile-time constants; the name could not come from configuration even if that seemed desirable. One
   source of truth, and `TokenBlacklistService` derives its raw namespace from the same constant rather
   than repeating the literal.
3. **`CacheRegionsTest` asserts the rule by reflection** over the declared constants, so a region added
   later is covered by a test written now. Mutation-checked: removing the prefix from one region fails the
   build with the reason.

Verified against live Redis — every key, after exercising both regions and the blacklist:

```
hodimp:configValues::security.session.timeout.minutes.admin
hodimp:configValues::notify.email.domain
hodimp:tenantConfigValues::t1:notify.email.domain      ← tenant scope preserved inside the key
hodimp:blacklist:eyJhbGciOiJIUzM4NCJ9…
```

Nothing unnamespaced.

### One more defect this surfaced

**The seeder was never running in a transaction.** `seedOnBoot()` called `seed()` on `this`, and Spring's
`@Transactional` is proxy-based — a self-invocation does not pass through the proxy, so the annotation was
doing nothing. Every repository call ran in its own transaction, which mostly looked fine because the
seeder is idempotent, until a step read an entity in one transaction and touched it in another and it
failed with *"Session/EntityManager is closed"*. It depended on which branch each step took, so it
appeared on an already-seeded database and not on a fresh one — which is why the §15 verification, run
mostly against fresh databases, did not catch it.

Now started explicitly with a `TransactionTemplate`, so reconciling the catalogue is atomic: a
half-applied seed leaves permissions naming modules that do not exist. A reflective scan for the same
mistake elsewhere found only redundant annotations — private overloads and nested calls inside an
already-open transaction, where the inner annotation would have joined the same transaction anyway.
