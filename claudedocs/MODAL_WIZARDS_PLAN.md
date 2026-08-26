# Step wizards for the long modal forms

**Scope:** the two modal forms long enough to be a wall of inputs, plus the one defect that has to be fixed
before any form is split into steps at all.
**Touches:** `components/ui/StepWizard.vue`, `pages/auctions/AuctionLotsView.vue`,
`pages/valuations/ValuerPanelView.vue`.

## 1. Which modals are actually long

Counted per modal, not per file — several of these files hold three or four dialogs and the file total says
nothing about any of them:

| Modal | Fields | Verdict |
|---|---|---|
| **New / edit auction lot** (`AuctionLotsView`) | **17** | wizard |
| **Add a valuer** (`ValuerPanelView`) | **14** | wizard |
| Tenant onboarding (`TenantListView`) | 10 | already a wizard |
| Edit catalogue item (`MyCatalogueView`) | 8 | leave |
| New / edit user (`UserFormModal`) | 8 | leave |
| Submit your report (`ValuationsView`) | 7 | leave |
| Request a valuation (`ValuationsView`) | 5 | leave |

Eight fields in a 620px modal is one scroll, and a wizard over it is chrome around a form somebody could
already see. Seventeen is three screens with the save button below all of them. The line is drawn between
them rather than at a field count that sounds tidy; the two above it are the two the eye actually loses.

`StepWizard` already exists and is used by five forms, two of them in modals. This is application, not
construction — except for §2.

## 2. The defect that has to be fixed first

**A wizard as it stands can hide a validation error completely.**

`useFormErrors.focusFirstInvalid` walks `document.querySelectorAll('[data-field]')` and focuses the first row
whose name the server rejected. A wizard renders only the current step — `<slot :name="`step-${step}`" />` —
so a field on any other step **has no element in the document**. And `capture` suppresses the banner whenever
the response carried field errors, on the correct reasoning that the fields are showing their own messages.

Put together, a rejected save whose error belongs to a step that is not on screen produces: no red field, no
banner, no focus move, and a toast saying the information is not correct. The reader is told something is
wrong and given nothing that says what or where. That is worse than the wall of inputs this change is meant
to fix, and it applies to the five wizards already shipped, not just the two being added.

**The fix**: `StepWizard` takes an optional `stepFields: string[][]` — the field names on each step, in step
order — injects `FORM_ERRORS` like `FormField` does, and when the error map changes, moves to the earliest
step holding a rejected field and focuses it there. Without the prop nothing changes, so the existing call
sites keep working while they are wired up one at a time.

The map is declared rather than discovered because it cannot be discovered: a field on a hidden step does not
exist to register itself. The alternative — rendering every step and hiding the inactive ones — trades this
bug for a worse one, since focus and the tab order would then reach fields nobody can see.

## 3. Accessibility, while the component is being spread further

The wizard is about to go from five callers to seven, so the gaps get closed now rather than in seven places
later. Against §25's standard:

- The progress track is a bare `<div>`. It becomes `role="progressbar"` with `aria-valuenow/min/max`.
- Nothing marks which step is current. `aria-current="step"` on the active circle.
- The circles are named by their visible number alone, so a screen reader hears "1", "2", "3". Each gets an
  accessible name: *"Step 2 of 4: Where it is"*.
- Steps ahead look like buttons and silently do nothing — `goTo` ignores them. They become genuinely
  `disabled`, so the affordance matches the behaviour.
- A step change moves content with no announcement. A polite live region carries *"Step 2 of 4, Where it
  is"*.

## 4. The steps

Both forms are already grouped by blank lines in their templates; the steps follow the grouping the author
had in mind rather than a new one.

**Auction lot** — 17 fields, 4 steps:

1. **The lot** — lot number, kind of property, title, description
2. **Where it is** — county, town, address, title number, bedrooms
3. **Money** — guide price, reserve, deposit to bid
4. **The sale** — date and time, venue, auctioneer, viewing arrangements, terms

**Add a valuer** — 14 fields, 4 steps:

1. **The person** — first name, last name, email, phone
2. **Registration** — firm, registration number, registered with, registered until
3. **Indemnity cover** — insurer, policy number, sum assured, cover expires
4. **Where they work** — counties, specialisations

Four is the ceiling here, not a coincidence: the indicator allots 128px per step, and five would not fit a
620px modal.

The existing gate on each form's save button becomes the gate on the step that owns those fields — the
auction lot needs a title, which is step 1, so step 1 will not advance without one. No new required fields
are invented: what may be saved is the server's decision and it has not changed.

## 5. What is not being changed

- **The eight-field modals.** Named above, deliberately left. Say the word and they are two lines each.
- ~~**The five existing wizards' step maps.**~~ **Done after all** — see §7. The reason for deferring was
  that mapping a step to its fields by reading somebody else's template is a guess; that turned out to be
  avoidable, because the mapping can be *extracted* from each template instead of recalled.
- **Cancel buttons.** The wizard owns the footer, so the explicit Cancel goes and the modal's own close
  control does the job — the same trade `UserGroupFormModal` already made.

## 6. Verification

`vue-tsc --noEmit`, and reading each rendered step against the field list above.

**Not verified: the wizards on screen.** Both modals are behind a sign-in, and I cannot sign in — entering a
password is not something I will do, whoever set it. So the step splits, the error jump and the ARIA are
argued from the code and the types, not from a screenshot. Worth ten minutes of somebody clicking through
both, in particular: submit the auction lot with a bad reserve (a step 3 field) from step 4, and confirm the
wizard jumps back to Money with the field marked.

---

## 7. As built

`StepWizard` gained `stepFields` and the ARIA of §3; the auction lot and add-a-valuer modals became
four-step wizards on the splits in §4, both losing their Cancel button to the wizard's own footer, and the
valuer modal widening from 560px to 620px to sit the four indicators comfortably.

**All five existing wizards were wired too**, which §5 had deferred. The reason for deferring dissolved once
the maps stopped being something to recall: a twelve-line script split each template on its `#step-N`
boundaries and read the `name` of every `FormField` inside, so the maps below are what the templates
actually contain rather than what reading them suggested. It also checked the two new ones against what had
been declared by hand — both matched.

| Wizard | Steps mapped |
|---|---|
| `AuctionLotsView` | 4 |
| `ValuerPanelView` | 4 |
| `AgentApplyView` | 3 |
| `UserGroupFormModal` | 2 |
| `TenantListView` | 3 |
| `PropertyEditView` | 5 (two carry no fields — photographs, progress) |
| `MortgageProductEditView` | 3 |

`UserGroupFormModal`'s second step holds the permission picker, which the extractor found empty because it
is not a `FormField`; `permissions` was added by hand, since that is the name the server would reject under.

### Two things worth knowing

**`PropertyEditView` declares `#step-4` before `#step-0` in its template.** Harmless — the wizard renders
`step-${step}`, so slot order in the file has nothing to do with step order — but it is the kind of thing
that makes a reader doubt an index map, hence recording it.

**A rejected field with no input at all is still invisible, wizard or not.** The auction lot sends `estate`
and `plotAreaAcres` in its payload, from `blank()`, and has never had inputs for either. If the server
rejects one, no step owns it, the wizard stays put and `capture` suppresses the banner because the response
did carry field errors. That is a pre-existing hole rather than one this change opened, and it is narrow —
both fields are always sent empty — but it is the same shape of fault as §2 and belongs in the same note.

### Verified

`vue-tsc --noEmit` clean and `vite build` clean, and each step's field list checked against the extraction
above.

**Not verified: any of it on screen.** Both modals sit behind a sign-in I will not perform, so the step
splits, the error jump and the ARIA are argued from code, not seen. The check that matters most, if somebody
has two minutes: open a new auction lot, fill in a title, advance to **The sale**, and save with a reserve
below the guide. The wizard should jump back to **Money** with the reserve marked and focused. If it does,
§2 is closed; if it does not, the `stepFields` map is the first thing to look at.
