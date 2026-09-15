# The bank approves a user

> "any user created has to be approved by bank staff."

## What is true today

`UserService.create` mints a staff account, stamps `enabled = true`, `status = ACTIVE`,
issues a temporary password and returns it once. The person can sign in immediately and
change the password on first use. Nobody else sees the account happen.

That is the gap. The seller sells through the bank; the bank carries the relationship and
the regulatory exposure. A seller's owner can currently create staff who reach the
marketplace's price and inventory screens without the bank ever knowing the person exists.

## What changes

A created staff account exists but cannot sign in until a second pair of eyes at the bank
says so. Everything else about creation is unchanged — in particular **the maker still gets
the temporary password in the response**, once, exactly as now. Handing over a credential
and enabling it are two different acts, and separating them is the whole point.

### The account's state while it waits

`enabled = false`, `status = STATUS_NEW (0)`, `status_flag = "New"`.

`STATUS_NEW` already exists and has never been used for a user; it means "created, not yet
put into service", which is precisely this. Deliberately **not** `STATUS_INACTIVE`: that is
the state of an account somebody switched off, and conflating "never approved" with
"deactivated" would make the two indistinguishable on the list and in the audit trail.

`AppConstant.isLive(0)` is true, so `assertUsable`'s status test still passes — it is
`enabled = false` that stops the login, and the message is its own (below).

### The queue entry

A new entity type on the existing Maker/Checker queue:

- `entity_type = USER`, `action = CREATE`
- scope: the new profile's `tenant_id` or `institution_id`, so the creating organisation
  sees their own request waiting; platform staff see everything, as everywhere
- `subject_label`: the person's name, their group and their organisation — what a checker
  needs before opening anything
- `submission_note`: who created them and what they will be able to do

No schema change. `entity_type` is deliberately a loose reference with no CHECK, and a type
with no handler is refused rather than silently marked decided.

### Who decides

`UserApprovalHandler`, `decidePermission = USERS_APPROVE` (new), `assertMayDecide` →
platform staff only. Identical to `DevelopmentApprovalHandler`: the bank is the checker on
anything that reaches a buyer, and a person with price and inventory rights reaches buyers.

`ck_approval_maker_checker` still applies on top, so the bank user who created an account
cannot be the one who approves it. Another bank user must.

`USERS_APPROVE` lands on the platform super-admin group automatically (`topUpPlatformGroup`
is built from "all permissions"). It is in no role template, for the same reason
`DEVELOPMENTS_APPROVE` is in none: approving is a decision an organisation grants to
somebody chosen, never a default.

### What a decision does

| Decision | The account |
|---|---|
| `APPROVED` | `enabled = true`, `status = ACTIVE`, flag `Active`. It can now sign in, and `must_change_password` still forces the temporary credential to become a real one. |
| `REJECTED` | archived — `status = DELETED`, `enabled = false`, the reason kept in `deactivation_reason`. An account the bank refused should not linger as something `activate` could later flip on. |
| `SENT_BACK` | left waiting. The maker fixes what the note asks for and saves, which resubmits. |

### Resubmission

`update` on an account that is still waiting restates the request rather than leaving it
decided-and-forgotten — `submitOrRestate`, so a send-back and an edit-while-waiting are the
same path. The last edit is the one the checker reads, which is the rule settled for
developments.

`update` must also **stop stamping `STATUS_EDITED`** on a waiting account: that would knock
it off `STATUS_NEW` and make it indistinguishable from an approved one.

### The doors that would otherwise go round the gate

Three existing endpoints could enable an unapproved account or dispose of a request without
anyone seeing it. Each refuses while the account is waiting:

- `activate` — would flip `enabled` directly. This is the important one: `USERS_ACTIVATE` is
  a far more commonly granted permission than `USERS_APPROVE`, so without this guard the gate
  is decorative.
- `deactivate` and `archive` — would leave a `PENDING` row pointing at a disposed account,
  which a checker could then approve into existence.

Disposal is therefore the bank's: they reject it. A maker who created an account by mistake
asks for a rejection, and that is correct rather than inconvenient — a maker who can make
their own mistake vanish unseen is not operating under maker/checker.

### The login message

`assertUsable` currently answers "This account is not active" for every disabled account.
A person holding a fresh temporary password needs to be told the truth: the account exists,
the password is right, the bank has not approved it yet. Its own branch, before the generic
one.

## What does not change

**Buyers.** They register themselves and are enabled on registration. Gating a buyer on the
bank would mean nobody can browse the marketplace until a bank officer processes them, which
is not what the bank asked for and would empty the funnel. `UserService.create` already
refuses to mint a buyer, so the two paths do not meet.

**The seeded bootstrap admin.** Created by `SeederService` against the repository directly,
not through `create`, so a fresh database is still reachable.

## The bootstrap consequence, stated plainly

On a genuinely fresh install there is exactly one platform account. It creates the first
bank administrator, and then cannot approve it — `ck_approval_maker_checker` is a database
CHECK and is not negotiable.

This is the correct behaviour, not a bug: one person cannot be both pairs of eyes. The
operational answer uses machinery that already exists — set `hodi.seed.bootstrap-username`
(and `-email`, `-password`) to a second value and reboot; `seedBootstrapAdmin` mints a second
platform administrator because the username is not taken. From then on the two can approve
each other's work and the config values are dead.

## Files

**Backend**
- `common/AppConstant.java` — `APPROVAL_ENTITY_USER`, `APPROVAL_ACTION_CREATE`
- `enums/AppPermissionEnum.java` — `USERS_APPROVE`
- `modules/users/UserApprovalHandler.java` — new
- `modules/users/UserService.java` — `create`, `update`, `activate`, `deactivate`, `archive`,
  `toResponse`, plus `applyApproval` / `applyRefusal` for the handler
- `modules/users/dto/UserDtos.java` — `awaitingApproval` on `UserResponse` and on
  `TemporaryPasswordResponse`
- `modules/auth/AuthService.java` — the waiting-for-approval login message

**Frontend**
- `types/api.ts` — the two new flags
- `pages/users/UserListView.vue` — a "Waiting for approval" badge; hide Deactivate/Delete on
  a waiting row
- `pages/users/TemporaryPasswordModal.vue` — say the account is waiting, so the administrator
  does not tell the new person to go and sign in
- `pages/approvals/ApprovalListView.vue` — `USER` in the subject map (and the three other
  types that were never added: `PROPERTY`, `DEVELOPMENT`, `CATALOGUE`)

**Tests**
- creation leaves the account disabled and queues a request
- the maker still receives the temporary password
- the maker cannot approve their own
- a non-platform caller cannot approve
- approval enables; rejection archives; send-back leaves it waiting
- `activate` refuses while waiting
- login says the account is waiting rather than "not active"
