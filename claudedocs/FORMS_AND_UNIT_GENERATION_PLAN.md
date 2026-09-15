# Four fixes: placeholders, phase percentage, unit price, generating several types

Branch: `feature/coop-bank`, both repos.

---

## 1. Placeholders stop being examples

Roughly 90 inputs carry an example as their placeholder — `Highrise Apartments`, `Wood Avenue`,
`Two hundred apartments over four blocks…`. Two problems with that: the example reads as a value
already entered until you look twice, and a long one is truncated to something that reads as a
half-finished sentence.

The placeholder becomes the field's own label. Scope:

- **Only `<input>` and `<textarea>`** inside a `FormField`. An `AppSelect`'s placeholder means
  "nothing is chosen" (`Everything`, `Every state`, `Whole project`) — it is not an example and a
  filter that said "Status" instead of "Every state" would lose the fact that blank means all.
- Placeholders that are **instructions** (`Why is this being switched off?`) or **format hints**
  (`{block}-{floor}-{nn}`, `https://`) stay. They are telling you what shape the answer takes, which
  is what a placeholder is for.

## 2. A phase percentage other than 100 can be saved

`ck_phase_complete` makes it a biconditional: `percent = 100` ⟺ a completion date exists. The service
reconciled the pair in **both** directions unconditionally, and the date's ran second:

```java
if (phase.getPercentComplete() == 100 && actualCompletionOn == null) → stamp today
if (actualCompletionOn != null && percent != 100)                    → percent = 100   // always wins
```

So once a phase had a completion date, every later save forced it back to 100 — a phase that slipped
could not be recorded as having slipped, which is the one thing the field is for. The form always
sends the percentage, so this fired every time.

**The typed percentage wins.** Dropping below 100 clears the completion date; reaching 100 stamps
today unless a date was given. The date only decides when no percentage was sent at all — which the
form never does, but an API caller may.

## 3. Generating units stops copying the price

`UnitSpec` already inherits: a unit with a null price takes its type's and marks the figure
`inherited`. The generator wrote `request.listPrice()` onto all 200 rows, so a prefilled value echoed
straight back broke that link — repricing the type afterwards then moved nothing.

- The field **prefills from the chosen type**, so somebody can see what the units will cost.
- The backend stores a price **only when it differs from the type's**. An unchanged prefill is stored
  as null, which is what keeps the inheritance.

That way the prefill is informative without being a decision.

## 4. Several types in one run

The modal generates one type, and a project is "70 two-beds, 40 three-beds, 12 penthouses".

A **queue**: preview a type, add it to the run, preview the next, then write them together. The
preview stays what it is — the server's own generator with nothing saved — because the labels are what
a buyer is quoted for the life of the project.

**The check that only a batch can do**: duplicate labels *across* types. Two-beds and three-beds both
producing `B-1-01` is a clash neither preview would catch alone, so the batch endpoint validates every
label in the run together and refuses the whole run. "Refused whole, never partially" already applies
within one type; this extends it across the run, in one transaction.

## 5. Out of scope

- Per-unit pricing rules (floor premiums, corner units). A price is one figure here.
- Editing a queued batch. Remove it and add it again.

## 6. Status

**Done**, both repos. 242 backend tests pass, six of them new; frontend typechecks and builds.

90 placeholders across 32 files. One was a **bound** `:placeholder` carrying an expression that changes
with the decision being made — the sweep replaced the expression with bare text and broke the build,
which is how it was caught. It was restored as a binding, with the examples swapped for labels.

The price and phase fixes each have tests that fail against the old code: an unchanged prefill leaves
the price null and inherited, a different one is stored, and a phase at 100% can be corrected to 60%
with its completion date cleared.

The batch test worth keeping is the cross-type clash: each batch is free of clashes alone, together
they produce `B-1-01` twice, and the run is refused. Its first assertion — that nothing is written —
was dropped: the test class is `@Transactional`, so the service joins the test's own transaction and
there is nothing there to observe a rollback with. What it asserts now is the refusal and its reason.

**Not verified in a browser.** The generator modal is behind a sign-in I do not have a password for.
