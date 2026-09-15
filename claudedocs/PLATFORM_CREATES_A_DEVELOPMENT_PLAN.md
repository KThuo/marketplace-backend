# Platform staff can draft a development, and the bank can own one

Branch: `feature/coop-bank`, both repos.

---

## 1. The refusal, and why it was right once

```java
if (caller.getTenantId() == null && caller.getInstitutionId() == null) {
    throw new HodiException("A development belongs to the organisation building or financing it.", FORBIDDEN);
}
```

The comment above it says *"the owning principal is whichever organisation the caller belongs to — never a
parameter. A platform administrator has no organisation and is refused… somebody has to be building, and
the platform is not."*

That held when the platform was a neutral marketplace. It stopped holding twice over:

1. **Co-op runs the platform and also builds.** "The platform is not building" is no longer true.
2. **The bank's staff became platform staff.** They carry no `institution_id`, so the very people who
   would draft a bank-owned project are the ones this refuses.

So `superadmin` — who holds `DEVELOPMENTS_CREATE` — cannot create anything at all.

## 2. What the schema already allows

Nothing needs migrating. `developments` carries

```sql
CHECK ((institution_id IS NOT NULL)::int + (tenant_id IS NOT NULL)::int = 1)
```

— exactly one owner. Both cases the user asked for satisfy it as it stands:

| Case | Row |
|---|---|
| Platform staff draft **on behalf of a seller** | `tenant_id` = that seller |
| **Co-op builds it themselves** | `institution_id` = the bank's row in `banks` |

A genuinely owner-less, platform-owned project would need that CHECK relaxed. It is **not** in scope:
there is a row that means "Co-op", it is what `institution_id` has always pointed at, and inventing a
third ownership state to express something an existing one already says would be worse than the refusal.

## 3. The rule

Ownership stays derived for anybody who has an organisation, and becomes a parameter **only** for
callers who have none:

- **Seller staff** — their own tenant. Owner fields on the request are ignored, not refused: a seller
  cannot assign a project to somebody else, and the quietest way to guarantee that is to not read them.
- **Platform staff** — must name exactly one owner. Neither is an error that says what to do; both is an
  error, because a row cannot have two.

The asymmetry is the point. Making the owner a free parameter for everybody would mean one missing check
between a seller and somebody else's portfolio.

## 4. Changes

**Backend**
1. `SaveDevelopmentRequest` gains `ownerKind` (`SELLER` or `BANK`) and `ownerTenantHashId`.

   It was going to be two hash ids, one per kind. That was wrong: the client has no endpoint to learn a
   bank's id, because the directory of banks is the module that was retired. `BANK` names no id and the
   server resolves the single live row — refusing rather than guessing if there is none or several.
2. `DevelopmentService.create` resolves the owner through one new method, which is the only place the
   request's owner fields are ever read.
3. The named tenant or bank must exist and not be archived — a hash id that decodes to nothing is a
   not-found, not a silently owner-less row.
4. `update` does **not** read them. Moving a project between organisations after the fact would move its
   units, bookings, payments and media with it; that is a transfer, not an edit, and nothing asks for it.

**Frontend**
5. A "Whose project is this?" field on the wizard's first step, rendered only for platform staff: the
   bank, or a seller picked from the list. Everyone else sees nothing and sends nothing.

## 5. Out of scope

- Transferring an existing project to another owner.
- A project owned by the platform with neither column set.
- Letting a seller draft on behalf of another seller.

## 6. Status

**Done**, both repos. 236 backend tests pass, five of them new; frontend typechecks and builds.

The five pin the rule from both sides: platform staff drafting for a seller, platform staff drafting for
the bank, the two refusals (no kind named, `SELLER` with no organisation), and the one that matters most
— a seller sending `ownerKind: BANK` gets their own organisation, because for a caller who belongs
somewhere the fields are never read.

One existing test was removed rather than updated: *"the platform cannot own a development, because
somebody has to be building it"* pinned the refusal this change deletes.

**Not verified end to end.** The superadmin password is not one I have, so the click-through from the
wizard was not exercised — the logic is covered by integration tests against real Postgres, and the
client/server vocabulary was checked to match, but nobody has watched it work in a browser.
