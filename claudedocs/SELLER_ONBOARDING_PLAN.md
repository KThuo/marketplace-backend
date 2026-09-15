# Seller onboarding

Branch: `feature/coop-bank`, both repos.

> 1. Capture seller basic/personal information and send creds, then allow the rest once logged in.
> 2. First ask whether the seller has a Co-op account. **Yes** → validate the account, pull their details,
>    continue. **No** → take the details by hand, then AML and IPRS checks (simulated for now — show a skip
>    and say pending integration).
> 4. Upload personal and legal documents — ID, KRA PIN, CR12.
> 5. Submit to the bank for review and approval.
> 6. No listing until verified and approved.

---

## 1. What already exists

More than the request implies, and the plan is mostly wiring rather than building.

| | State |
|---|---|
| Listing gate | **Built.** `EffectivePermissionResolver.KYC_GATED` withholds `PROPERTIES_CREATE/UPDATE/SUBMIT/MEDIA` from any profile whose `kyc_status` is not cleared |
| Which sellers must clear | **Built.** `ConfigKey.KYC_REQUIRED_SELLER_TYPES`, default all six seller types |
| KYC lifecycle | **Built.** Profile: `NOT_REQUIRED → PENDING → SUBMITTED → APPROVED → REJECTED`. Pack: `DRAFT / SUBMITTED / APPROVED / REJECTED / MORE_INFO` |
| Document checklist | **Built.** `kyc_requirement_configs`, versioned, seeded per seller type — ID, KRA PIN, CR12 are already in it |
| Document storage | **Built.** `DocumentService.store` — ACL per tenant/user/permission, SHA-256, audited on every read, and platform staff are deliberately *not* exempt |
| Compliance review queue | **Built.** `ComplianceReviewView.vue`, per-document verdicts, `APPROVE / REJECT / MORE_INFO` |
| Seller's own checklist | **Built.** `MyCompliancePackView.vue` |
| Public application → approval → organisation | **Built, for agents and vendors.** The precedent to copy |
| `tenants.onboarding_status` | Column, constants and badge exist. **`PENDING` is never written** — every create path hardcodes `ACTIVE` |
| Seller self-registration | **Does not exist.** Only platform staff can create a tenant (`TENANTS_CREATE`) |
| Co-op account validation, AML, IPRS | **Do not exist** |
| Reusable upload component | **Does not exist** — the KYC page has its own inline file input |

### The one thing that is not what it looks like

`assertOrganisationTradeable` sounds like the listing gate and is not. It lives in `AuthService`, runs at
login and refresh only, and checks `SUSPENDED` / `TERMINATED`. **Tenant state gates nothing about listing;
profile KYC state does.** Any design here has to pick which lifecycle is authoritative — today KYC is, de
facto, and this plan keeps it that way rather than introducing a second gate that can disagree with it.

---

## 2. The shape

Follow the agent flow exactly, because it already solves points 1, 5 and 6:

```
public application  →  account created, can sign in immediately
                       profile.kyc_status = PENDING, tenantId = null
                       SellerApplication.state = PENDING
        ↓
seller signs in     →  completes the rest: organisation detail, documents
        ↓
submits             →  KycSubmission SUBMITTED, application SUBMITTED
        ↓
bank reviews        →  per-document verdicts, then APPROVE / REJECT / MORE_INFO
        ↓
approved            →  tenant created (ACTIVE), profile attached, kyc_status = APPROVED
                       → listing permissions start resolving
```

**Why the account exists before approval.** Point 1 asks for credentials up front so the rest can be
completed while signed in. Registering grants nothing: no organisation exists to list into, and the KYC
gate withholds every listing verb. This is the argument already written into
`ConfigKey.AGENT_SELF_REGISTRATION_ENABLED`, and it holds identically here.

**Why not the staff-creation path.** `UserService.create` now parks an account disabled until a bank user
approves it. That is right for somebody the bank is hiring and wrong for somebody applying — they could
not sign in to complete their own application, which is the whole of point 1.

---

## 3. The steps

`StepWizard.vue` drives it, as the property and development editors do.

**Step 1 — Do you bank with Co-op?**
A yes/no, and the only question on the step. It decides the next two.

**Step 2a — Account validation** *(yes)*
Account number, validate, pull names/email/phone, show them read-only with a "not you?" escape.
Simulated: `CoopAccountProvider` interface, `SimulatedCoopAccountProvider` returning a
`PENDING_INTEGRATION` verdict. The panel says so plainly and lets them continue by hand.

**Step 2b — Personal details, then AML and IPRS** *(no)*
The same fields, typed. Then two checks, each its own provider with the same simulated verdict and the
same visible "pending integration — skipped" state.

Both paths converge on the same captured identity. The difference is recorded
(`identity_source = COOP_ACCOUNT | SELF_DECLARED`) because it is exactly what a reviewer wants to know.

**Step 3 — The organisation**
Seller type (which selects the KYC checklist version), trading name, registration number, address.

**Step 4 — Documents**
`kyc_requirement_configs` for that seller type, rendered as a checklist. Reuses the existing
`POST /api/v1/kyc/my-pack/documents`. A new `FileDrop.vue` replaces four ad-hoc file inputs across the app.

**Step 5 — Review and submit**
Everything back, then submit. Application → `SUBMITTED`, pack → `SUBMITTED`.

---

## 4. The simulated integrations

Three providers, one shape, declared the way `OCP_BASE_URL` already is — *"so the real provider is a class
and two rows, not a migration."*

```java
interface IdentityCheckProvider {
    String code();                       // COOP_ACCOUNT | AML | IPRS
    CheckResult run(CheckRequest request);
}
record CheckResult(String verdict, String reference, String detail) {}
// verdict: PASS | FAIL | REVIEW | PENDING_INTEGRATION
```

Every run is persisted to `seller_identity_checks` whether or not it reached anything, so the bank sees
"IPRS — pending integration, skipped, 15 Sep" rather than silence. **A simulated check never returns
PASS.** A stub that passes is indistinguishable from a real one that passed, and the first time the
integration lands, nobody can tell which historical rows were real.

Config keys added empty: `coop.account.base.url`, `coop.account.api.key`, `aml.base.url`, `aml.api.key`,
`iprs.base.url`, `iprs.api.key`.

---

## 5. Point 6 — no listing until approved

Already true through `KYC_GATED`. Two gaps to close:

1. **`UNITS_MANAGE` and `DEVELOPMENTS_*` are not in `KYC_GATED`.** An uncleared seller cannot create a
   listing but can create a development and generate units, which reach the marketplace as typology cards.
   That is the same act by another door.
2. **The KYC module admits `SELLER_OWNER` only** (`AppModuleEnum`), so an agent or vendor is KYC-gated and
   cannot open the screens to clear it. Pre-existing, and it will bite a seller whose owner delegates.

---

## 6. Files

**Backend** — new `modules/sellers/`: `SellerApplication`, `SellerApplicationService`,
`PublicSellerController` (`POST /api/v1/public/sellers/apply`), `SellerController` (bank queue),
`SellerApprovalHandler`, `identity/` providers, `seller_applications` + `seller_identity_checks`
migrations, `TenantService.createForSeller`, `SecurityConfig` permit, `AppPermissionEnum.SELLERS_*`.

**Frontend** — `pages/sellers/SellerApplyView.vue` (public wizard),
`SellerOnboardingView.vue` (signed-in continuation), `SellerRegisterView.vue` (bank queue),
`components/ui/FileDrop.vue`, `services/sellers.ts`, routes, and re-point the nav's
**"List a property"**, which currently goes to the *agent* application.

---

## 7. Decisions needed

1. **Who starts an application** — the seller (public, as agents do) or a bank officer on their behalf?
   The plan assumes public, because point 1 says "send creds".
2. **Does the Co-op path shorten the review**, or is it pre-fill with the same approval at the end?
   The plan assumes pre-fill only.
3. **Does an existing Co-op account skip AML/IPRS?** The plan assumes yes — the bank has already done
   both to open the account.

---

## 8. Done separately, already

- **Buyer landing → Browse.** `home()` in the router; the duplicate in `LoginView` now calls it.
- **Payments read-only for sellers.** `PAYMENTS_RECEIVE`, `PAYMENTS_VOID` and `PAYMENT_TYPES_MANAGE` are
  platform-only; a migration revokes them from every non-platform group. `PAYMENTS_VIEW` and
  `PAYMENT_TYPES_VIEW` are untouched.
