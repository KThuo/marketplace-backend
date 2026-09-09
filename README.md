# Hodi Marketplace Core (`hodi-core`)

Backend for **Hodi Market Place** — a property-selling and mortgage platform. Sellers list property,
partnered lending institutions finance the purchases, and buyers see both in one place.

This repository currently contains the **access-management foundation**: organisations, staff, user
groups and permissions, sessions, settings, theme, profile, dashboard and audit. The functional domain
— listings, mortgage products, applications — is a separate plan.

Spring Boot 4.1.0 · Java 23 · Postgres · Redis · port **8085**.

---

## Before it will start

**`config/` is not in this repository, and the application will not boot without it.** Two files go
there, placed per environment by whoever owns that environment:

| File | What it is |
|---|---|
| `config/application.properties` | Datasource, Redis, JWT secret, AES key, CORS origins, seeder bootstrap credentials |
| `config/logback-spring.xml` | Logging pattern and appenders |

Runtime-tunable values are deliberately **not** in that file: session windows, password policy, theme
colours, gateway credentials and buyer-verification rules all live in the `configurations` table and are
edited from the Settings screen. See `enums/ConfigKey`.

Secrets (`JWT_SECRET`, `AES_KEY`, `DB_PASSWORD`) must come from environment variables anywhere that is
not a laptop.

## Running it

```bash
# Postgres on 54321 and Redis on 6379 are expected to be running already —
# there is deliberately no docker-compose in this repository.
createdb -h localhost -p 54321 -U postgres hodimp     # first time only

mvn spring-boot:run        # or: mvn package && java -jar target/hodi-core-1.0.0.jar
```

Flyway migrates on boot, then the seeder reconciles the catalogue from the enums and creates a
bootstrap administrator (`superadmin` / the configured `BOOTSTRAP_PASSWORD`, forced to change on first
sign-in).

The seeder is **idempotent** and safe to leave enabled: it reconciles rather than inserts, which is what
makes "add a permission to the enum and restart" a complete deployment step.

---

## The access model

Three axes. An action is allowed only if all three pass.

```
 ①  MODULE      is the module enabled for the actor's organisation      (tenant_modules)
                AND is their user type in app_modules.allowed_user_types  ← platform controls
 ②  PERMISSION  does their user group carry the action code               ← organisation controls
 ③  VISIBILITY  is the row inside the actor's visible-tenant set          ← derived, never granted
```

Permissions govern **actions**; visibility governs **data**. Neither substitutes for the other.

### Four populations, one identity table

| Actor | `tenant_id` | `institution_id` | Sees |
|---|---|---|---|
| Platform staff | — | — | everything, by permission |
| Seller staff | set | — | their own organisation |
| Lender staff | — | set | the sellers their institution is **partnered** with |
| Buyer | — | — | only their own rows, resolved from identity |

**A lending institution is not a tenant.** Its staff read *other* organisations' portfolios, and
`tenant_lender_partnerships` is the only thing that grants that. Approving a partnership is the moment
cross-organisation access begins; revoking one ends it on the lender's very next request, because the
visible-tenant set is resolved per request rather than cached.

### Where the rules actually live

| Concern | Class |
|---|---|
| Which rows a caller may read | `security/TenantScope` — **one choke point**, used by every tenant-bearing query |
| Which actions a caller may take | `security/EffectivePermissionResolver` |
| Which user types a module admits | `AppModule.allows()` — exact-token CSV match, never SQL `LIKE` |
| Session windows and rotation | `modules/auth/RefreshTokenService`, `security/jwt/JwtService` |
| Global vs per-organisation config | `modules/configurations/ConfigurationService` |

`TenantScope` is load-bearing in a way it would not be under schema-per-tenant: every organisation
shares one schema, so a query that forgets to go through it reads everybody.

---

## Sessions

A true **sliding idle window**, not a long-lived refresh token with a cosmetic timeout.

- The refresh token's TTL **is** the window (plus a small grace), and it rotates on every use. An unused
  token dies on its own; an active client keeps pushing the deadline forward.
- Reusing a rotated token revokes **every** session for that user — we cannot tell a stale tab from a
  stolen token, so the safe reading is taken.
- Access tokens carry a millisecond issue time, and `users.sessions_valid_from` invalidates every
  outstanding one at once. Password change, administrator reset and revoke-all all set it, so those
  actions end sessions immediately rather than at the end of a window.
- Two client classes with separate windows and separate cookies (`hodi_rt`, `hodi_brt`), so a staff and
  a buyer session can coexist in one browser.

## Conventions

- `ApiResponse<T>` / `PagedResponse<T>` envelopes; everything under `/api/v1/**`
- HashId-encoded ids on the wire — never a raw bigint. Salted per user, except on `/api/v1/public/**`
  where a shared link has to decode for whoever it was sent to
- `status` (int) + `status_flag` soft lifecycle; `status = 5` is archived. **No hard deletes**
- Every Redis namespace carries the application's name — region names are built from `CacheRegions.APP`,
  so a stored key reads `hodimp:configValues::…`. `RedisConfig` registers exactly the declared set and
  refuses anything else, and `CacheRegionsTest` fails the build if a new region skips the prefix
- Permission codes `{MODULE}_{ACTION}`, seeded from `AppPermissionEnum`
- Flyway migrations named `VYYYYMMDDHHmmSS__description.sql` — never `V1`/`V2`
- Every list endpoint is server-paged and searched against a generated `search_text` column behind a
  `pg_trgm` GIN index

## Layout

```
common/       envelopes, exceptions, paging, search specs, crypto, reference generators
config/       security filter chain, Redis + cache keys, transactions, Jackson
enums/        AppModuleEnum, AppPermissionEnum, ConfigKey, UserTypeEnum — the seeder's source of truth
infra/        notify (SMS/email) and storage (S3 or local disk)
logging/      request logging, activity logging, payload sanitiser
security/     TenantScope, EffectivePermissionResolver, JWT, principal, password, TOTP, HashIds
seed/         SeederService — reconciles the database against the enums on every boot
tenant/       TenantContext + the filter that binds it from the principal
modules/      auth, users, usergroups, usertypes, permissions, appmodules, tenants, institutions,
              partnerships, tenantmodules, configurations, buyers, dashboard, audit, publicapi,
              developments (incl. the cost ledger, facility drawdowns and cost categories),
              bookings (on a property: a house or a development's unit), payments
```

## Figures

Nothing on the dashboard or the analytics page is stored, generated overnight or cached. Every figure is a sum
over bookings, payments, the development cost ledger, the drawdowns and the units at the moment the page asks,
through `security/OwnerScopeSql` — the one place a caller's scope becomes SQL for a JDBC read. See
`claudedocs/DASHBOARD_AND_ANALYTICS_PLAN.md` and `claudedocs/DEVELOPMENT_FINANCE_AND_PROGRAMME_PLAN.md`.

## One row per home

Every home the platform knows about is a row in `properties`: an ordinary listing (`HOUSE`), the card for one
kind of home in a development (`TYPOLOGY`), and each home in a development (`UNIT`). Bookings, payments, leads
and media all point at that row, whichever kind it is, so a sale is one row updated. A unit is written through
its development's inventory and its booking, never through the listing screens. See
`claudedocs/UNITS_AS_PROPERTIES_PLAN.md`.

## Known gaps

- **STK push is not wired.** The payment-type catalogue carries the M-Pesa, KCB and Co-op prompt channels,
  switched off, because there is no outbound Pesi client yet. Inbound credits (IPN) and hand-recorded
  payments work; see `claudedocs/PAYMENTS_AND_PAYMENT_TYPES_PLAN.md`.

- **Notifications** are not built. SMS and email go out through `NotifyClient`, but there is no
  in-app inbox or template catalogue yet.
- **Buyer verification codes cannot be delivered** until a notify gateway is configured — the send is
  skipped when no API key is set, so end-to-end verification needs that configuration.
- **Institutions have no per-organisation module gating.** Their staff are gated by user type alone.
- **`portfolio_scope = SELECTED`** exists as a column but is refused by the API: partial portfolio
  sharing needs the listings model first.
