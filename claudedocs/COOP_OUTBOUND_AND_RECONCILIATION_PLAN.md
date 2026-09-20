# Asking Co-op for money, and finding out whether it arrived

> **Superseded, 20 September 2026.** Written when payments went through the pesi gateway. The names,
> the shape and the sequence below no longer describe the code. The current plan and progress is
> [PAYMENTS_REBUILD_GAP_ANALYSIS_AND_PLAN.md](PAYMENTS_REBUILD_GAP_ANALYSIS_AND_PLAN.md). Kept for the
> reasoning that still holds.


**18 September 2026.** STK push, funds transfer, and the status query that runs when the callback
never comes.

---

## 1. Where we actually are

| Piece | State |
|---|---|
| Inbound notifications | **Live.** `POST /api/v1/public/coop/notifications` → `CoopIpnService.accept` → `coop_statements`, behind HTTP Basic and an IP allow-list. |
| `CoopClient` | **Built, and called by nothing.** OAuth token cached to expiry, two read timeouts, `Outcome<T>` that never throws, body `status == "0"` deciding. Zero callers — verified by grep, not by memory. |
| Channel configuration | **Live.** Each channel carries its endpoint; the host and token path are platform settings. |
| Accounts | **Live.** Descriptor-driven, encrypted, behind Maker/Checker. |
| STK push | **Not built.** |
| STK status query | **Not built.** |
| Funds transfer / PesaLink | **Not built.** |
| Account validation before a transfer | **Not built.** |
| Payment intents | **Not built** — and nothing else can be, because there is no row that remembers "we asked and are waiting". |
| The sweep | **Not built.** |

So the honest summary: this platform can *receive* money and cannot yet *ask* for any.

## 2. The rules pesi paid for, which we are not going to relearn

Read out of `TransactionTimeoutJob` rather than invented here. Each one is a scar.

1. **A missing callback must never auto-fail a payment.** Silence is not failure. A payment marked
   failed on a timeout, whose money did arrive, is a refund request and a customer who has paid twice.
2. **Only a definitive provider answer is terminal.** Completed, or failed *because the bank said
   failed*. Anything else — still pending, query errored, nothing wired — stays in flight.
3. **Automatic queries are capped.** pesi's cap is 2, one per sweep, because without it a stuck record
   was re-queried every 30 seconds indefinitely. Past the cap it stops and waits for a person.
4. **Inconclusive stamps a reason.** A row nobody can explain is worse than one that says
   "callback not received in 60s; query 2 of 2 gave no final answer; awaiting manual resolution".
5. **A person's manual query is neither counted nor capped.** The cap exists to stop a machine looping,
   not to stop an operator working.
6. **The callback timeout is per channel**, configured, not compiled — pesi defaults to 60s.

## 3. What we build

### 3.1 The intent — the row that remembers we asked

Nothing else is possible without it. `payment_intents`: our own reference, the channel, the account,
the listing and the person it is for, amount, the bank's reference once known, state
(`PENDING` → `PROCESSING` → `SUCCEEDED` / `FAILED`), `processed_at`, `callback_timeout_seconds`,
`status_query_attempts`, and `processing_reason` — the sentence a human reads.

**Ours before theirs.** The reference is generated and stored *before* the call goes out, so a request
that times out on the socket is still a row we can query about. The alternative — remembering only what
the bank's reply told us — loses exactly the payments that need chasing.

### 3.2 STK push

`CoopStkService.push(intent)` → `CoopClient.post(channel, body, patient = true)`. A person is standing
at their phone, so it uses the patient timeout. The body's `status == "0"` means *accepted for
processing*, not paid — the money is confirmed by the callback or by the query, never by the acknowledgement.

### 3.3 The status query

Its own payment type, as the channel model already assumes — `COOP_STK_STATUS` carries its own endpoint.
Called from two places, and only two: the sweep, and an operator's button.

### 3.4 Funds transfer, and validating the account first

**Validation is not optional and not a nicety.** `CoopClient` resolves the destination and returns the
account holder's name; the name is shown to the approver and stored on the intent. Transferring to an
unvalidated account number is how money reaches a stranger who has no reason to give it back.

Then: **funds transfer goes through Maker/Checker**, carrying amount, destination, and the *resolved
name* — so the checker approves "KES 450,000 to 011… — JANE W. MWANGI", not an account number they
cannot evaluate.

### 3.5 The sweep

`@Scheduled(fixedRate = 30s)` over `PROCESSING` intents past their own deadline, applying §2 exactly.
Query-capable channels get queried; the rest get a stamped reason and wait for a person.

### 3.6 The unmatched queue

A credit that matches no intent is already stored. It needs a screen: what arrived, what it quotes, and
a way to attach it to a booking by hand. Money in the bank that the platform cannot explain is the one
outcome worse than a failed payment.

## 4. Order

1. `payment_intents` + the reference, and STK push writing one. *(Nothing can be tested before this.)*
2. The callback matching an intent, and the statement crediting it.
3. The status query, and the operator's manual button.
4. The sweep, with the cap and the reasons.
5. Account validation, then funds transfer behind Maker/Checker.
6. The unmatched queue as a screen.

Steps 1–4 are one coherent piece: the ask, the answer, and what happens when no answer comes. 5 is
separate work — money going out rather than coming in. 6 can follow either.

## 5. What this does not do

- **No auto-refund, ever.** A failed or duplicated payment is a person's decision.
- **No stubbed endpoints.** The biller's validation and advice routes stay unserved until they are real;
  a stub that accepts an advice and does nothing loses money silently.
- **No retry of the push itself.** Re-pushing an STK a customer may already have paid is how a person is
  debited twice. The query answers the question; the push is not repeated automatically.
