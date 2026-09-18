# Payments, completed: Co-op through Pesi, tied to a listing and to the person who paid

**The ask (17 September 2026):** complete the Co-op integration. Billers, IPN and STK push are what the
client needs first. Tie payments to listings and to the customers who make them. Stop offering payment
types nobody has configured. And when a listing has been paid for — even partly — say so, or take it off
the market, as a choice made when the booking is created.

Two repositories are the reference: `../../pesi` (the gateway itself) and `../../new-hodi/hodi-b` (a
mature collection module — statement mapping, IPN termination, an outbound client).

---

## 1. What is already here

Worth stating, because the gap is narrower than the ask suggests and the work should be the gap.

| Piece | State |
|---|---|
| `PesiIpnService` | **Good.** Stores the statement first, acknowledges, then places best-effort. Refuses to place on a bare reference match — a four-character code from a 32-letter alphabet is one mistyped letter away from another live one — so it wants the amount or the phone to agree as well. Unplaceable money is stored and queued, never refused. |
| `PesiStatement` | Stored, with the raw payload kept. |
| `Payment` | Links booking, development, property, buyer name and phone; carries source, method, external reference, balances before and after, void trail. |
| `PaymentType` / `PaymentAccount` | A catalogue of channels, and per-organisation accounts against them. |
| `PesiChannel` | Categories — CASH, CHEQUE, STK_PUSH, TRANSFER, VALIDATE — with the rule that **the category decides behaviour, never a catalogue id**. |
| Receive-payment form | Exists, picks a booking, records against it. |

## 2. What is missing, precisely

### 2.1 There is no outbound client — so STK push cannot happen
`PesiChannel` says it plainly: *"Not wired here yet: there is no outbound client, so these channels stay
switched off in the catalogue."* Pesi exposes `POST /api/ext/v1/transactions/coopbank/stk-push` (and a
generic `/pay`), authenticated with an `X-API-Key` header. Nothing in this codebase calls it.

`hodi-b`'s `PesiClient` is the shape to follow, and two of its decisions are load-bearing:

- **Two clients, two timeouts.** A quick one for asking about money; a patient one (three minutes) for
  moving it, because the gateway holds the connection until the payer has decided. Anything shorter times
  out on a payment that then succeeds — money taken, no record of it.
- **Never throws.** A gateway that is down returns a named failure, not an exception at the call site.
- **`status == "0"` decides, not the HTTP code.**

### 2.2 A payment is not tied to the person who made it
`Payment` carries `buyer_name` and `buyer_phone` — denormalised text — and no user id. A signed-in buyer
cannot reliably be shown their own payments, because the match is a name someone typed. `UnitBooking`
already has `buyerUserId`; the payment does not copy it.

### 2.3 A payment is not tied to a listing it can be found by
`property_id` exists and is nullable, and `booking_id` is NOT NULL — so every payment must belong to a
booking, and a payment against a plain listing (a house, not a development unit) has nowhere to go. The
client asks for payments tied to listings; today they are tied to bookings of development units.

### 2.4 The receive form offers channels nobody configured
`PaymentQueryService.methods()` returns `PaymentMethods.ALL` — a static list of six. So "Card" is offered
by an organisation with no card channel, and a payment can be recorded through a method the platform
cannot actually collect. The offered list must come from what this organisation has configured and
switched on, plus the ones that need no configuration at all (cash, cheque over a counter).

### 2.5 A paid-for listing keeps advertising itself
Nothing takes a listing down, or marks it, when money arrives against it. The client wants that as a
decision made per booking: *hold it*, *mark it*, or *leave it up*.

---

## 3. What we are building

### A — The outbound client, and STK push
1. `PesiClient` in `infra/pesi`: `X-API-Key` from configuration, quick and patient timeouts, `Outcome<T>`
   that never throws, `status == "0"` as the success test.
2. `POST /payments/stk-push` — amount, phone, booking or listing, payment account. Writes an **intent** and
   returns the acknowledgement; the payment itself is written when the money lands, by the callback or by
   the status query. See §8.3, which is the authority on this.
3. The catalogue's STK_PUSH channels become offerable once an account exists for them.

### B — Billers and IPN, completed
1. The IPN receiver keeps its shape. What it gains is the **unmatched queue as a screen**: what arrived,
   what it could not be placed against, and a person's decision to place it.
2. Placing an unmatched statement is an ordinary payment write with the statement's id on it, so the money
   is never counted twice.

### C — Payments tied to a listing and to a person
1. `payments.buyer_user_id`, copied from the booking when there is one and from the signed-in payer when
   the payment is theirs. A buyer's own payments then resolve by identity, not by a typed name.
2. `payments.property_id` filled for every payment, not only unit ones, and `booking_id` made nullable so a
   listing can be paid for without a development behind it.
3. A buyer-facing "my payments" read, scoped by identity.

### D — Only configured channels are offered
`methods()` becomes: the distinct methods of this organisation's **active** payment accounts, plus cash and
cheque where the organisation is allowed to take them. Nothing else. The receive form then cannot offer
what the platform cannot collect.

### E — A paid listing says so
1. `unit_bookings.on_payment` — `HOLD`, `MARK`, or `NONE`, chosen when the booking is created.
2. On the first payment that clears, the listing is withdrawn (`HOLD`), or flagged as *reserved — deposit
   paid* on its card (`MARK`), or left alone (`NONE`).
3. The marketplace shows the flag; search keeps or drops the row by the same rule.

---

## 4. Decisions worth recording

- **The gateway's answer is the record, not our reading of it.** The raw response is stored on the payment
  exactly as the IPN payload is stored on the statement.
- ~~**Synchronous STK, not fire-and-forget.**~~ **Superseded by §8.3 on 18 September**: STK push and funds
  transfer are asynchronous, so the call is an acknowledgement rather than an outcome. A payment is still
  written only when money has actually arrived — that part stands — but it is written by the callback or
  by a status query, not by the return value of the request.
- **Idempotency is by external reference.** A gateway may deliver the same credit twice; the unique index on
  the external reference is what makes the second one a no-op rather than a double posting.
- **Cash and cheque need no configuration.** Everything else does. A channel with no account behind it is a
  method that cannot collect, and offering it is how money gets recorded against nothing.
- **Hiding a listing is a choice, not a rule.** A seller taking a holding deposit on a show house may well
  want it still advertised; one selling a single plot does not. The booking says which.

## 5. Sequence

1. D — only configured channels (small, self-contained, fixes a live complaint).
2. C — payments tied to listing and person (migration; everything else builds on it).
3. A — the outbound client and STK push.
4. B — the unmatched queue as a screen.
5. E — a paid listing says so.

Each lands with its own tests and its own commit.

## 6. Checks

- A method not configured for this organisation cannot be selected, and cannot be posted.
- An STK push that the payer declines writes no payment; one they approve writes exactly one, and a
  repeat delivery of the same reference writes none.
- A buyer sees their own payments and nobody else's.
- A booking marked HOLD takes its listing off the marketplace on first cleared payment; MARK leaves it up
  and flags it; NONE changes nothing.
- `mvn package` green, `npm run build` green.

---

# Part II — the architecture, after reading pesi properly

**Added 18 September 2026**, on the instruction that the Co-op connection should be built the way pesi
builds it, the taking-in and display the way hodi does it, that STK and funds transfer are **asynchronous
for now** and therefore need a status query and account validation, and that credentials belong to each
payment type as encrypted per-type configuration driven by a JSON descriptor.

## 7. What pesi actually does, and what we copy

### 7.1 A super type carries a JSON descriptor of its own configuration
`super_transaction_types.required_config_fields` is `jsonb`, and it is a *form descriptor*, scoped:

```json
{"super":[],"company":[],"business":[],"accountsLabel":"Biller",
 "accounts":[{"key":"institutionCode","label":"Institution Code (assigned by Co-op)",
              "type":"text","required":true},
             {"key":"connectionPassword","label":"Co-op Connection Password",
              "type":"password","required":true},
             {"key":"validationUrl","label":"Validation URL",
              "type":"text","required":true,"fullWidth":true}]}
```

Nothing in pesi's code knows what a Co-op connection ID *is*. The descriptor says which fields exist, what
to call them, which are secret and which span the form's width; the screen renders from it and the adapter
reads them by key. **Adding a provider is a row, not a deploy** — that is the property worth copying, and
the reason a hardcoded credentials table would be the wrong answer here.

### 7.2 Secrets are encrypted at rest, AES-256-GCM
`EncryptionUtil` in pesi is `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag. **This codebase already has the
same thing** — `com.hodi.common.EncryptionUtil`, same transformation, already used by
`ConfigurationService` for secret configuration values. So there is nothing to build: the `password` fields
of the descriptor are encrypted through it on write and decrypted only where the adapter needs them.

### 7.3 The category decides behaviour, never an id
`PesiChannel` in this codebase already states the rule. The adapters hang off the same axis.

## 8. The shape we are building

### 8.1 Configuration, per payment type
- `payment_types.required_config_fields jsonb` — the descriptor, in pesi's shape, seeded per channel by
  migration.
- `payment_accounts.config jsonb` — the values for one configured account of that type. Every field whose
  descriptor says `"type":"password"` is stored encrypted; the rest in clear.
- The account form renders itself from the descriptor. No screen hardcodes a Co-op field.
- A read never returns a secret: the API answers `"••••"` for a set secret and null for an unset one, and a
  save that sends back the mask leaves the stored value alone. Same rule `ConfigurationService` already
  applies to secret configuration.

### 8.2 Adapters, one per Co-op flow
`CoopStkPushAdapter`, `CoopFundsTransferAdapter`, `CoopBillerAdapter`, behind one `PaymentGateway`
interface, chosen by `PesiChannel.Category`. Each reads its credentials from the account's decrypted config
by the descriptor's keys. All calls go to pesi over `X-API-Key`, never to Co-op directly — pesi is the
integration surface and this platform is one of its clients.

### 8.3 Asynchronous, which changes the shape of everything
STK push and funds transfer **accept** and answer later. So:

1. A **payment intent** row is written before the call: amount, booking or listing, payer, channel, our
   own reference. It is the thing the answer lands on, whichever way it arrives.
2. The gateway's acknowledgement moves the intent to `PENDING` with pesi's transaction id on it. **No
   payment is written yet** — a payment means money arrived.
3. Two ways it completes, and both must work because neither is reliable alone:
   - **Callback**, which is the IPN path this codebase already has;
   - **Status query** — `POST /payments/intents/{ref}/refresh`, asking pesi what became of it, for when the
     callback never came. A scheduled sweep does the same for intents left pending, because a customer who
     closes the tab still paid.
4. Whichever arrives first writes the payment, keyed on the external reference, so the second is a no-op.

### 8.4 Account validation before money goes out
pesi exposes `POST /api/ext/v1/transactions/coopbank/funds-transfer/validate?accountNumber&bankCode`. The
transfer form calls it and shows the resolved account holder's name **before** the operator commits.
Paying the wrong account is not recoverable by us, and a name on the screen is the only check that catches
a transposed digit.

### 8.5 Display, the way hodi does it
Statements in, matched or queued; the queue is a screen; a payment carries its statement id; the listing
and the person are on the payment, not inferred. That is §3 C and B of Part I, unchanged.

## 9. Sequence, revised

1. ~~Only configured channels are offered~~ — **done**, `432881f`.
2. ~~Cards, not dropdowns~~ — **done**, `1ca9d98`.
3. **Config descriptors and encrypted per-type config** (§8.1) — everything else reads credentials from it.
4. **`PesiClient` + adapters** (§8.2), with the two timeouts and `Outcome<T>` from hodi-b's client.
5. **Intents, status query and the sweep** (§8.3).
6. **Account validation** (§8.4).
7. Payments tied to listing and person; the unmatched queue as a screen; the paid-listing rule.

## 10. Open questions for the client

- **Whose Co-op credentials?** One set for the platform, or one per selling organisation? The descriptor
  supports both (`company` scope versus `accounts`), and the answer changes who fills the form in.
- **Funds transfer — who may send money out?** It is the one flow that moves money away from the platform,
  and it should probably need Maker/Checker rather than a single permission.

## 11. The client's answers (18 September 2026)

**One credential set, not one per seller.** Only Co-op has the financial ability to transact, so the
Co-op configuration is the platform's. In the descriptor's terms that means the fields live in the
`company` scope, not `accounts`, and one screen — platform-only — fills them in. A per-seller override is
not built, and should not be: it would imply a second bank that does not exist.

**Inbound credentials too, and pesi names them.** The descriptor for `COOP_BILLER_B2B` carries both
directions, and the comment above it in pesi's own migration says which is which:

- `connectionID` / `connectionPassword` — Co-op → pesi ingress auth.
- `callbackUsername` / `callbackPassword` — **pesi → the end system**. This platform *is* the end system,
  so this is the pair pesi presents when it calls us.

Today `PesiIpnController` accepts an optional `X-Pesi-Signature` and treats its absence as untrusted —
stored, never credited without a person. That is a sound floor and it stays. What it gains is the pair
above: HTTP Basic on the notification endpoint, verified against the stored `callbackUsername` and the
encrypted `callbackPassword`, so an unauthenticated caller cannot even write a statement row. The
signature stays supported, because a deployment already using it must not break on the day this ships.

**Maker/Checker on funds transfer, without a doubt.** Sending money out is the one flow where a single
permission is not enough. It goes through `ApprovalService` like any other — an intent is raised, a second
person approves, and only then does the adapter call pesi.

**And the consequence the client named:** an approval must show *what is being approved*, in detail. That
is not specific to payments — it is true of every row in that queue, and it was not being done. Built and
shipped ahead of the rest: `approval_workflows` carries the maker's own before and after as jsonb, the
difference is computed once on the server, and the queue lists the fields that moved. A transfer's
approval will use the same mechanism, so the checker sees the amount, the destination account and the
resolved account name before releasing money.

---

# Part III — correction: there is no intermediary

**18 September 2026.** Parts I and II assumed the marketplace was one of pesi's business clients — calling
`/api/ext/v1/transactions/...` with an `X-API-Key`, and receiving notifications signed with
`X-Pesi-Signature`. **That is wrong and everything resting on it is withdrawn.**

The topology is:

```
Marketplace ──OAuth2 client credentials (consumerKey/consumerSecret → Bearer)──▶ Co-op APIs
     ▲                                                                              │
     └──────────── Co-op posts to us, HTTP Basic ───────────────────────────────────┘
```

**We are the end system.** Co-op reaches this platform directly. `X-Pesi-Signature` is pesi's own device
for authenticating *its* business clients and has no place here; `PESI_IPN_SECRET` is the same mistake
already in the code.

"Take what pesi does" therefore means **port pesi's Co-op adapters**, not call pesi.

## 12. The Co-op protocol, as pesi implements it

Read out of `CoopBankComponent` rather than guessed:

| Flow | Call |
|---|---|
| Token | OAuth2 client credentials, form-urlencoded, separate sandbox and production hosts. The token is cached against its own JWT expiry — one token serves many calls, and asking per request would rate-limit us into failures. |
| STK push | `POST {base}/FT/stk/1.0.0`, Bearer |
| STK status | `POST {base}/Enquiry/STK/1.0.0/` |
| Transaction status | `POST {base}/Enquiry/TransactionStatus_V3/3.0.0/` |
| Account validation | `POST {base}/Enquiry/Validation/IPSL/1.0.0/` |
| PesaLink transfer | `POST {base}/FundsTransfer/External/A2A/PesaLink_v2/2.0.0` — two legs on one token |

Requests and responses carry Co-op's own header envelope (`CoopRequestHeader` / `CoopResponseHeader`), and
the body's status decides the outcome, not the HTTP code.

## 13. Inbound: Co-op → us

pesi authenticates Co-op with **HTTP Basic** against a configured username and password, and — the part
worth copying exactly — **refuses every request when those are not configured**, rather than falling open.
A misconfigured deployment that accepts anonymous payment notifications is worse than one that accepts
none.

So `PesiIpnController` becomes the Co-op IPN endpoint: Basic auth, verified against the stored inbound
credentials, 401 when it fails or is unset. The current "store it but do not credit it without a person"
rule stops being the security boundary and goes back to being what it should be — a rule about *matching*,
for notifications that authenticate correctly but cannot be placed.

## 14. What this correction costs, honestly

The names in the codebase now assert an architecture that is not true:

- `infra/pesi/` — `PesiIpnController`, `PesiIpnService`, `PesiStatement`, `PesiStatementRepository`
- `pesi_statements` table, `PESI_IPN_SECRET` config key, `X-Pesi-Signature` header
- `PesiChannel`, whose values include `DARAJA_*`, `BUNI_*` and `EQUITY_*` — pesi's provider catalogue, not
  Co-op's products

None of it is wrong *code*; the IPN receiver's logic is sound and stays. It is wrong *naming*, and naming
that encodes a false architecture is a trap for whoever reads it next — it already trapped me into
designing two parts of this plan around an intermediary that does not exist.

Recommended, and to be confirmed before it is done: rename the Java types to `Coop*` and move them to
`infra/coop`, rename the config key, drop the signature header, and cut the channel catalogue down to the
Co-op products this platform actually offers. The table rename is the only part with data behind it and
can be done in the same migration or left with a comment — the client's call.

## 15. Sequence, corrected

1. ~~Only configured channels~~ · ~~cards not dropdowns~~ · ~~approvals show what changed~~ — **done**.
2. Co-op credentials as an encrypted, descriptor-driven configuration: consumer key and secret, the
   environment, and the inbound Basic pair.
3. `CoopClient` — OAuth with a cached token, Co-op's header envelope, `Outcome<T>` that never throws, two
   timeouts, body status deciding.
4. Inbound Basic auth on the notification endpoint, refusing when unconfigured.
5. Intents, status query and the sweep.
6. Account validation, then funds transfer behind Maker/Checker.
