# Payments, rebuilt: where we are, and what it takes to get there

**20 September 2026.** An investigation, not a build. Three audits were run in parallel over `hodimp-b`,
`hodimp-f` and `../../new-hodi` (the reference), the two Postman collections were read
(`QUANTUMNEX - Hodi PropTech` and `coop biller`), the existing plan documents were read, and the
payments and Co-op test slice was run. Every claim below was either read in code or observed by running
something; where it is inferred, it says so.

---

## 0. The ask, restated as checkable requirements

| # | Requirement | Verdict today |
|---|---|---|
| R1 | Payments are made against bookings | **Met.** `payments.booking_id` is NOT NULL; balance is derived from `v_booking_balances`. |
| R2 | A payment is made via the configured payment types | **Met, with rough edges.** Catalogue, per-account descriptor-driven config, AES-GCM secrets, OTP + Maker/Checker all exist and are tested. |
| R3 | M-Pesa (STK) prompt, which **the buyer can initiate themselves** | **Not met.** Staff-only today; the buyer has no payment surface at all. The staff path exists but blocks a server thread and the UI misreports it. |
| R4 | IPN and Biller notifications create payments against bookings | **Half.** IPN is live and well-tested. Biller is two route constants with no controller behind them. |
| R5 | Every non-cash/cheque payment has a statement row | **Not enforced.** No constraint, no service rule; two write paths produce electronic payments with no statement. |
| R6 | Unreconciled money is flagged "unused" and can be manually reconciled | **Half.** Statements are stored `UNMAPPED` with a reason. Nothing lists them, nothing attaches them, no UI. |
| R7 | Slip validation: admin enters a bank ref, we find it among **unused** statements in our DB, see the channel it arrived through, attach it to the chosen booking | **Not built.** No finder by bank reference, no attach endpoint, no screen. |
| R8 | The UI/UX around the module is coherent | **Not met.** Two competing forms, false negatives on the STK screen, whole reconciliation half missing. |
| R9 | IPN and biller credits are matched on the **listing reference or the 4-character pay code** | **Half.** Only the 4-character code is matched today; the listing reference is not tried. |
| R10 | PesaLink disbursement: a bank admin sends money to a developer or any other payee. **Not a payment**, it debits. | **Adapter built, nothing wired.** No disbursement row, no endpoint, no approval, no screen. |

Clarifications from the client, 20 September:

- **Disbursements are their own module.** Outbound money never touches bookings, payments or statements.
  It goes through Maker/Checker and account validation, as the earlier plan said.
- **Slip validation pulls only unused statements**, and the answer carries the payment type the money
  arrived through, so the operator sees "KES 50,000 via Co-op Biller, quoted C1" before attaching.
- **Matching is on the listing reference or the 4-character code**, for both IPN and biller.
- **Who is offered what**, decided on the server from the principal, never from a client-sent flag:

  | Method | Buyer (the customer) | Platform staff |
  |---|---|---|
  | Co-op STK prompt | **Yes.** The money leaves their own phone on their own PIN; nothing is asserted on their behalf. | Yes, prompting the buyer's phone. |
  | Slip validation (find an unused statement by bank ref, attach it) | **By configuration.** A platform setting, `payments.buyer.slip.validation`, OFF by default. | **Always**, where an inbound channel (IPN account or biller) has a live account. |
  | Cash, cheque | **Never**, even where a cash account is configured. Cash is somebody asserting money arrived, and the buyer is the somebody. | Yes, and never against a listing their own organisation owns (the existing `mayRecordByHand` rule). |
  | Transfers, enquiries | Never. Money out and questions about money are not ways to pay. | Never on a pay form. |

  Today `offered()` in `PaymentAccountService` already applies the cash rule for staff and lets STK through
  for anyone who can reach the endpoint; what it lacks is a buyer principal at all (Step 5) and the slip
  method with its setting (Step 3). new-hodi's `TENANT_SLIP_VALIDATION` estate setting is the precedent.

Rough distance: **the inbound and configuration foundations (about 40% of the module) are solid and
worth keeping. The reconciliation half (R5–R7) and the buyer half (R3) are the other 60% and are
essentially unbuilt. The frontend needs to be rebuilt around one flow rather than patched.**

---

## 1. What exists, and its condition

### 1.1 Keep as-is (solid, tested)

| Piece | Where | Evidence |
|---|---|---|
| Channel catalogue with `category`, `kind` (COLLECT / SEND / ENQUIRY), `method`, descriptor + encrypted config | `modules/payments/PaymentType`, `ChannelConfig` | `PaymentTypeServiceIT` (27), `ChannelConfigIT` (5) |
| Payment accounts: OTP, Maker/Checker, edit takes account out of use until re-approved | `PaymentAccountService`, `PaymentAccountApprovalHandler` | `PaymentTypeServiceIT` |
| `Payment` row: booking link, snapshots, balance before/after, void trail, `statement_id` | `Payment`, `PaymentService` | `PaymentServiceIT` (9) |
| IPN receiver: Co-op's real field names (`AcctNo`, `PaymentRef`, `TransactionId`, tilde narration), HTTP Basic closed while unset, store-first-place-later in `REQUIRES_NEW`, `ref_no` unique index as the idempotency guarantee | `infra/coop/CoopIpnController`, `CoopIpnService`, `CoopInbound` | `CoopIpnIT` (11), `CoopIpnApiIT` (4), `CoopInboundTest` (5) |
| Corroboration before auto-credit: 4-char pay code + (phone OR exact deposit/price) | `CoopIpnService.corroborated` | `CoopIpnIT` |
| Intent settlement idempotency: `succeeded()` guard, `failed()` never overrides success, late callback links rather than credits | `CoopIntentSettlement` | `PaymentIntentIT` (7) |
| "Silence is PENDING, only a bank-given code is FAILED" | `CoopAnswer` | `CoopAnswerTest` (9) |
| Co-op client: OAuth2 client-credentials token cached to expiry, host/paths from config not code, two timeouts, never throws, detects the firewall's HTML "Request Rejected" page | `CoopClient` | **untested** (see 1.3) |

Test run on 20 September, Java 23 (GraalVM 23.0.2), Testcontainers Postgres:

| Class | Tests |
|---|---|
| CoopIpnIT | 11 |
| PaymentIntentIT | 7 |
| CoopAnswerTest | 9 |
| CoopInboundTest | 5 |
| CoopIpnApiIT | 4 |
| PaymentServiceIT | 9 |
| PaymentTypeServiceIT | 27 |
| ChannelConfigIT | 5 |
| **Total** | **77, all green** |

Note: the pom targets Java 23. The global instruction to use Java 17 for this project is stale; with 17
the surefire fork dies on class file version 67.

### 1.2 Exists but is wrong or unreachable

| Problem | Where | Why it matters |
|---|---|---|
| **Manual `receive` accepts an STK-category account** and writes `source=MANUAL`, `statement_id=NULL`, free-text `external_reference`. | `PaymentService.receive` :103–133 | An operator can fabricate an electronic payment with no bank evidence. This is the R5 hole. |
| **Intent-settled payments have no statement.** `recordFromIntent` passes `null`; a late IPN links `PaymentIntent.statementId` but never `Payment.statementId`. | `PaymentService.recordFromIntent` :185–188; `CoopIntentSettlement.attachStatement` | Two half-populated links to one fact. R5 cannot be enforced until this is one link. |
| **`pushAndWait` holds a servlet thread up to 150 s**, sleeping 2 s between re-reads. | `CoopStkService.pushAndWait` :178–199; `PaymentIntentController.prompt` | ~200 concurrent prompts is a full API outage on default Tomcat. The browser also waits 180 s. |
| **`PAYMENTS_RECEIVE` is platform-only** (migration `V20260915120000` revoked it from every non-platform group) and `POST /payments/intents/stk` requires it. | `AppPermissionEnum`, `PaymentIntentController` :66 | Only platform staff can fire a prompt. R3 ("buyer initiates") is structurally impossible today. |
| **Frontend renders `PROCESSING` as "Not paid"**; no polling anywhere; the Requests tab is gated on `isPlatformStaff`, not a permission. | `hodimp-f/src/components/PaymentModal.vue` :171–173; `BookingDetailView.vue` :210 | The common outcome (prompt outlives the wait) shows the operator a falsehood with no route to the truth. Highest-damage UI bug. |
| **Two competing "take money" modals** with different fields, method sources, date controls and error handling. | `ReceivePaymentModal.vue` (from /app/payments), `PaymentModal.vue` (from bookings) | The operator's experience depends on which link they clicked. |
| `ReceivePaymentModal` defaults `form.method` to `BANK_TRANSFER`, which may not be in the served method list; the "Came through" card grid usually renders nothing for seller staff and offers STK cards to platform staff for money that already arrived. | `ReceivePaymentModal.vue` | Contradicts the server's own `offered` rules. |
| **Biller routes declared, not served, not whitelisted.** The account form hands Co-op two URLs that this app answers with 403/404. | `CoopRoutes.BILLER_VALIDATION/ADVICE`; `SecurityConfig` :77 lists only `/notifications` | A live onboarding trap. |
| IPN IP allow-list **defaults to open**; `X-Forwarded-For` trusted blindly; untrusted (no Basic) notifications are stored as UNMAPPED into a queue nobody reads. | `CoopIpnService.isFromAllowedAddress` :418–427, `callerAddress` :126–133 | Acceptable only once the queue has a consumer and the allow-list is set. |
| `CoopIntentSweep` is `@Scheduled` with no distributed lock. | `CoopIntentSweep` :43 | Two instances double the query rate and race on the same intents. |
| `accountFor()` picks `findFirst()` over an unordered query. | `CoopStkService` :329–336 | With two live STK accounts, which one collects is arbitrary. |
| `COOP_STK_PUSH` and `COOP_IPN_ACCOUNT` payment types ship **Inactive**; only the enquiry types were activated by migration. | `V20260908090000` :147–153; `V20260918100000` :763 | Out of the box the prompt throws "not switched on yet". |
| `PAYMENT_ACCOUNT` approvals render as the raw token in the approvals queue. | `hodimp-f/src/pages/approvals/ApprovalListView.vue` :63 | The second half of the account wizard is unnarrated. |
| `ChannelFieldSet` renders descriptor `type: 'select'` as a text input. | `hodimp-f/src/components/ChannelFieldSet.vue` | Any select-typed field (environment, say) is free text. |
| README §"STK push is not wired" and both older plan docs are written in `Pesi*` names. | `README.md` :154–157, `claudedocs/PAYMENTS_*` | Describes a system two commits out of date. |

### 1.3 Dead or unreachable code

- `CoopTransferService` (PesaLink validate/send/status): **zero callers** in main or test. The client
  wants it working, as a disbursements module (Step 8). The adapter is the right shape and stays.
- `CoopStatementRepository.findUnmapped` / `countUnmapped`: **zero callers**. The queue was designed in
  August; its consumer was never built.
- `AppConstant.STATEMENT_IGNORED`: in the CHECK constraint, never written.
- `CoopChannel.COOP_FUNDS_TRANSFER`: enum member with no catalogue row.
- `DevelopmentVisibility` injected and unused in `PaymentService` and `PaymentQueryService`.
- `GET /payments/receipt/{reference}` and `GET /payment-types/find/{id}`: served, typed in the frontend
  services, called by nothing.
- Nine constraint/index names on `coop_statements` still say `pesi` (`ck_pesi_state`, `idx_pesi_unmapped`, …).

**Untested entirely:** `CoopClient`, `CoopStkService.push/pushAndWait/query`, `CoopIntentSweep`,
`CoopTransferService`. There is no WireMock or MockWebServer in the repo, so **no outbound Co-op call has
ever been exercised by a test**. `PaymentIntentIT` drives `CoopIntentSettlement` directly.

### 1.4 What the Postman collections confirm, and what they do not

**`QUANTUMNEX - Hodi PropTech`** (outbound, Co-op OpenAPI):

| Call | Path | Matches code? |
|---|---|---|
| Token | `POST /token`, client-credentials, form-urlencoded | Yes: `CoopClient` |
| STK push | `POST /FT/stk/1.0.0`, Bearer; body `MessageReference`, `CallBackUrl`, `OperatorCode`, `MobileNumber`, `Amount`, `Narration`, `OtherDetails[]`; answer `MessageCode "0"` = "REQUEST ACCEPTED FOR PROCESSING" | Yes: `CoopStkService.pushBody` sends our intent reference as `MessageReference` and `PUBLIC_URL + /api/v1/public/coop/notifications` as `CallBackUrl` |
| STK status | `POST /Enquiry/STK/1.0.0/`, body `MessageReference`; sample codes `1032` cancelled, `2001` invalid PIN | Yes: `CoopAnswer` treats any non-zero given code as FAILED |
| Account validation | `POST /Enquiry/Validation/IPSL/1.0.0/` | Adapter exists (`CoopTransferService.validate`), no caller |
| PesaLink | `POST /FundsTransfer/External/A2A/PesaLink_v2/2.0.0` + `TransactionStatus_V3` | Adapter exists, no caller |

**`coop biller`** (inbound, Co-op → us):

| Call | Body | Matches code? |
|---|---|---|
| IPN notification | `AcctNo, Amount, CustMemoLine1-3, EventType, Narration, PaymentRef, TransactionId, …` under HTTP Basic | **Yes**: `CoopInboundTest.theBanksExampleParses` uses this body verbatim |
| Bill validation | header `{connectionID, connectionPassword, messageID, serviceName}` + request `{TransactionReferenceCode, TransactionDate, InstitutionCode}` | **No code.** Nothing parses this envelope |
| Bill notification (advice) | same header + request `{TransactionReferenceCode, PaymentReferenceCode, PaymentAmount, AccountNumber, PaymentMode, PaymentDate, InstitutionCode, …}` | **No code.** |

Two gaps in the collections, and what `../../pesi` settles:

1. **STK callback body.** Neither collection has one, and pesi's own outbound plan records the same:
   *"Async callback bodies are NOT in the collection."* pesi therefore accepts **two envelopes** on its
   STK callback route: Co-op's `{MessageReference, MessageCode, MessageDescription}` and M-Pesa's
   `{Body: {stkCallback: {CheckoutRequestID, ResultCode, ResultDesc, CallbackMetadata}}}`, correlating on
   `MessageReference` first. `CoopInbound` here already reads `MessageReference`; Step 5 adds the second
   envelope. The client has offered a real sample, which would turn this from a defensive guess into a
   pinned test. What pesi **did** observe is where the receipt lives on the **status query**:
   `TransactionMetadata.Items[Name="Narration"].Value` formatted `"<description>~<receipt>~<date>"`
   (`CoopResponseParser.stkReceiptFromMetadata`). Our query settlement should read the receipt from there.
2. **Biller response schema.** Fully specified in pesi (`CoopValidationResponse`, `CoopAdviceResponse`,
   `CoopBillerService`) and ported in Step 4. Resolved.

**Security note on the collections.** Both files contain live-looking credentials: an HTTP Basic pair on
the IPN request and a base64 client key/secret on the token request. They should be treated as secrets,
rotated if they have been shared beyond the team, and never committed to either repository. They are not
reproduced in this document.

---

## 2. What the reference (new-hodi) does that this platform does not

The user's description of "seamless" maps to specific mechanisms in `new-hodi/hodi-b` and `hodi-f`:

| Mechanism | Reference | Here |
|---|---|---|
| **One reconciliation path.** IPN, slip, upload and STK all call `CreditReconciler.applyTo`, which is the only writer of a statement-backed payment and sets both links in one place. | `payments/collection/services/CreditReconciler.java` | Three writers (`receive`, `recordFromGateway`, `recordFromIntent`), only one sets `statement_id`. |
| **Unused = a boolean on the statement**, plus `unallocated_reason`, plus `status` for "set aside". Filter `allocated=false` **is** the worklist. | `statements.allocated`, `StatementRepository.unallocatedFor` | `state IN (MAPPED, UNMAPPED, IGNORED)` + `unmapped_reason`. Equivalent, and fine. Just unused. |
| **Statement list endpoint with `allocated` filter, a `waiting` tally (count, sum, oldest age)**, `allocate {occupationId}` with **no amount field**, `set-aside {reason}`. | `StatementController` | None of these endpoints exist. |
| **Slip validation**: look the reference up locally, return the statement's own figure, then a separate attach endpoint that takes a reference and **no amount**. (new-hodi searches all states and refuses already-applied ones by name; the client has decided this platform searches **unused only**, see Step 3.) | `SlipValidationService`, `BookingMoneyService.takeConfirmedSlip` | Nothing. |
| **Void releases the statement** back to the worklist via an event, never frees the reference. | `VoidedPaymentListener`, `Statement.release` | Void leaves the statement MAPPED to a voided payment. |
| **Who is offered what is decided server-side from the principal**: STK to anyone including anonymous; inbound (slip) to signed-in users; cash/cheque to office only and never on their own invoice; transfer to nobody. | `PaymentAccountService.offered` | Partially: `offered()` exists with the cash/cheque rule, but STK requires a platform-only permission, and there is no buyer principal path. |
| **Frontend**: `StatementsPage` (one list, Used/Unused filter, "KES X waiting · N credits · oldest for D days", payer's verbatim text in mono, reason under the status chip, row actions Apply / Not rent), `AllocateCreditModal` (seeded with what the payer typed, read-only "what the bank said" panel, one select, live confirmation sentence, no amount input), `MakePaymentModal` (tabs by `renderAs`, prompt form replaces itself with "Waiting for the PIN…", slip form's lookup-then-lock). | `hodi-f/src/pages/payments/StatementsPage.vue`, `components/forms/AllocateCreditModal.vue`, `components/billing/MakePaymentModal.vue` | No statements screen, no allocate modal, two inconsistent payment modals. |
| Daily unallocated digest (in-app task, escalating email to the estate's contacts). | `UnallocatedDigestJob` | Nothing. |

**Do not copy**: pesi's synchronous STK shape (Co-op is asynchronous, this platform's intent + sweep is
the right shape and should stay); the `ResponseModel "00"` envelope; the `ledger` string discriminator;
the 11-dependency `StatementService`; `SlipValidationService` making an HTTP call inside a transaction.
Slip validation here is **DB-only** by the client's own definition, which also removes that last problem.

---

## 3. The plan

Ordered so each step is testable on its own and nothing is built on a hole.

### Step 1 — One writer, one link, one rule (backend, R5)

1. `PaymentService.write` becomes the single place a payment row is created; `recordFromGateway`,
   `recordFromIntent` and `receive` all call it and it **always** sets `payments.statement_id` when a
   statement is in hand.
2. Intent settled by status query with no IPN yet: **write a statement row from the query answer**
   (`trans_type = STK_QUERY`, `ref_no` = the bank's receipt or, failing that, our intent reference
   prefixed, `raw_payload` = the answer), then credit through it. A later IPN for the same money then
   finds the statement by reference and links rather than credits. One link, `Payment.statement_id`,
   and `PaymentIntent.statement_id` mirrors it.
3. `receive` restricted to **manual channels only** (`category IN (CASH, CHEQUE)`), and the account, if
   given, must be a manual one. An STK or inbound account is refused with a sentence pointing at slip
   validation.
4. Migration: `ALTER TABLE payments ADD CONSTRAINT ck_payment_statement CHECK (method IN ('CASH','CHEQUE')
   OR statement_id IS NOT NULL)`. Backfill first: report any existing electronic payments with a null
   statement (expected: intent-settled ones from testing) and decide per row.
5. Void releases the statement: `CoopStatement` gains `release(reason, by)` → state `UNMAPPED`, reason
   "The payment it was applied to was voided: …", `mapped_*` cleared. Called from `voidPayment` when
   `statement_id` is set.
6. **The matching rule, for IPN and biller alike** (R9). `CoopIpnService.tryToPlace` resolves what the
   payer quoted in this order, first hit wins:
   1. our own intent reference (a prompt we started), as today;
   2. **the listing reference**, `properties.reference` (16 chars, unique), exact after trim and uppercase;
   3. **the 4-character pay code**, `properties.pay_reference`, taken as the last four alphanumerics, as today.

   From the unit, the live booking as today. Corroboration (phone or exact amount) **stays for the
   4-character code only**, because the typo arithmetic in `V20260827090200` is about four characters from
   a 32-letter alphabet; a full listing reference is not one mistyped letter away from another live one,
   so a listing-reference match places directly. This split is a judgment and is the one point in this
   step to confirm with the client. One resolver, `PayeeResolver`, used by IPN, biller advice, biller
   validation and the unused queue's search, so the four cannot drift.

Tests: an STK-category account on `receive` is refused; a query-settled intent yields a payment with a
statement; a subsequent IPN for it links and does not double-credit; void puts the statement back in the
queue; the CHECK refuses a direct electronic insert without a statement.

### Step 2 — The unused queue and manual reconciliation (backend, R6)

New `StatementController` under `/api/v1/statements`, scoped like payments (by development ownership):

| Method | Path | Permission | Does |
|---|---|---|---|
| GET | `/list` | `STATEMENTS_VIEW` | Paged; filters `search` (ref, payer's text, name, phone), `state`, `paymentTypeId`, `developmentId`, `paidAt` range. One list for ledger and worklist. |
| GET | `/waiting` | `STATEMENTS_VIEW` | `{count, total, oldestPaidAt}` for UNMAPPED. |
| GET | `/find/{id}` | `STATEMENTS_VIEW` | Full row incl. raw payload (platform staff only for the payload). |
| POST | `/{id}/attach` | `STATEMENTS_RECONCILE` | Body `{bookingId}`. **No amount.** Refuses if already MAPPED (names the booking), if IGNORED, if the booking is not open, if the booking's development is outside the statement's account reach. Credits via Step 1's single writer. |
| POST | `/{id}/set-aside` | `STATEMENTS_RECONCILE` | Body `{reason}` → `IGNORED`. Refuses if MAPPED ("void the payment first"). |
| POST | `/{id}/restore` | `STATEMENTS_RECONCILE` | `IGNORED` → `UNMAPPED`. |

Two new permissions, because "money arrived" and "that money is this booking's" are different
authorities. `STATEMENTS_RECONCILE` is platform-only, matching `PAYMENTS_RECEIVE`.

Also: the IPN `unmapped` reasons already exist; add `CoopStatementRepository.findByReference`,
`findByRefNo`, `searchUnmapped`. Use the two indexes that already exist and are unused.

### Step 3 — Slip validation (backend, R7)

`GET /api/v1/statements/validate?reference=…` (`STATEMENTS_RECONCILE`), DB-only, **unused only**:

1. Normalise (trim, uppercase). Under six characters ⇒ "A bank reference is at least six characters."
2. Look up among `state = UNMAPPED` rows by `ref_no` (the bank's id) **or** by `reference` (what the
   payer quoted). Nothing outside the unused set is returned.
3. Found ⇒ `{valid:true, statementId, refNo, amount, currency, paidAt, payerName, payerPhone, quoted,
   paymentTypeId, paymentTypeName, category, accountNo, accountName, arrivedAt}`. The payment type is the
   one the money arrived through, resolved from `payment_account_id` → `payment_types`; it is what the
   attach writes onto the payment, so the operator sees it before committing.
4. Not found ⇒ `{valid:false, message}`. The message still says which of three things is true, without
   returning the row: "already applied to booking BK…", "set aside on <date>", or "no notification with
   that reference has reached us". A clerk who is told only "not found" for a slip that paid last week
   will key it by hand, which is the double credit this whole step exists to prevent.

Then the attach is Step 2's `POST /{id}/attach {bookingId}`, wrapped in a lock on the statement row so an
IPN or sweep landing between the lookup and the click cannot credit twice (the `succeeded()` guard already
protects the intent side; the statement side needs `SELECT … FOR UPDATE`).

Offered only where an inbound channel (IPN account or biller) has a live account, exactly as the client
described. `offered()` gains a `SLIP` entry (rendered as `VALIDATE`) under that condition: always for
platform staff; for a buyer only when `payments.buyer.slip.validation` is ON. A new `ConfigKey`, group
PAYMENTS, default OFF, editable in app settings. The validate and attach endpoints check the same rule
server-side, so a buyer cannot reach them by URL when the setting is off.

### Step 4 — Biller (backend, R4)

Serve `CoopRoutes.BILLER_VALIDATION` and `BILLER_ADVICE`, both added to `SecurityConfig` public POSTs.

The request and response schemas are **no longer an open question**: pesi implements them in
`../../pesi/src/main/java/com/pesi/payments/biller/CoopBillerService.java` with DTOs
`CoopValidationRequest/Response` and `CoopAdviceRequest/Response`, and the Postman bodies are pesi's own
test bodies. Port the shapes verbatim.

- **Envelope**: `header.{connectionID, connectionPassword, messageID, serviceName}` in; verified in
  constant time against the biller account's encrypted config (the descriptor already has the fields);
  **closed while unset**, same as IPN. `header.messageID` stored as the trace id and echoed back.
  Response header is `{messageID, statusCode, statusDescription}`.
- **Status codes, as pesi answers them**: `200` success; `400` missing header/body/fields or bad amount;
  `401` connection credentials wrong; `402` duplicate transaction; `404` customer reference not found (or
  biller not found); `405` end system unavailable or misconfigured.
- **Validation** (`request.{TransactionReferenceCode, TransactionDate, InstitutionCode}`):
  `TransactionReferenceCode` is what the buyer typed. Resolve with the Step 1 resolver (listing reference
  or pay code → unit → live booking). Success body: `TransactionReferenceCode` and `TransactionDate`
  echoed, `TotalAmount` = the amount due (deposit if unpaid, else next instalment, else balance),
  `Currency`, `AccountNumber` = the reference echoed, `AccountName` and `AdditionalInfo` = the buyer's
  name, `InstitutionCode` echoed, `InstitutionName` from the account's config. Unknown ⇒ `404`.
- **Advice** (`request.{TransactionReferenceCode, PaymentReferenceCode, TotalAmount, PaymentAmount,
  Currency, PaymentDate, PaymentMode, AccountNumber, AdditionalInfo, InstitutionCode, …}`): writes a
  `coop_statements` row (`trans_type = BILLER`, `ref_no = TransactionReferenceCode` as pesi dedupes on,
  `PaymentReferenceCode` kept as the trace, `reference = AccountNumber`, amount `TotalAmount`, raw payload
  kept), then the same `tryToPlace` as IPN. Redelivery ⇒ `402 Duplicate transaction` with the same
  body, which is what stops Co-op retrying. Success ⇒ `200 Payment successfully received` with the
  request's key fields echoed. An advice that authenticates but cannot be placed is still `200`: the
  money is real, whose it is becomes the unused queue's problem.

Tests: both Postman bodies verbatim; wrong password ⇒ refused; validation of a known code returns the
booking's figure; advice delivered twice ⇒ one statement, one payment; an advice for an unknown code lands
in the unused queue with a reason.

### Step 5 — STK the buyer can start, without holding a thread (backend, R3)

1. **A buyer principal path.** `POST /api/v1/payments/intents/stk` accepts either `PAYMENTS_RECEIVE`
   (staff, any open booking in scope) **or** a signed-in buyer whose `buyerUserId` matches the booking.
   Phone defaults to the buyer's own, amount defaults to what is due, capped at the balance. `offered()`
   for a buyer returns STK, plus slip validation when the setting allows it, and never cash or cheque
   however many cash accounts the seller has configured.
2. **Return the acknowledgement, not the outcome.** The endpoint writes the intent, posts to Co-op with the
   patient timeout for the request itself, and returns the intent (`PROCESSING`) immediately. Remove the
   150 s `pushAndWait` loop. The browser polls `GET /payments/intents/{id}` every 3 s for up to the
   configured callback timeout, then shows "still waiting, we will keep checking" rather than "not paid".
3. The sweep gains a lock (`ShedLock` on Postgres, or `pg_try_advisory_lock`) so two instances do not
   double-query.
4. `accountFor` picks deterministically: the booking's own organisation's live STK account, else the
   platform's, else refuse; ordered by id.
5. Activate `COOP_STK_PUSH` and `COOP_IPN_ACCOUNT` in a migration, since the operator has now configured
   them; keep the enquiry types active.
6. **The callback accepts both envelopes** pesi accepts: Co-op's `{MessageReference, MessageCode,
   MessageDescription}` and M-Pesa's `Body.stkCallback`. Correlate on `MessageReference`, then
   `CheckoutRequestID`. A success with no receipt in the callback is still a success; the receipt is
   filled in by the status query, which reads it from `TransactionMetadata.Items[Narration]` split on
   `~`, index 1. Whichever of callback, IPN and query arrives first credits; the rest link.
7. WireMock tests for `CoopClient` and `CoopStkService`: token cached and reused, HTML page ⇒ unsent,
   accepted ⇒ PROCESSING, cancelled `1032` on query ⇒ FAILED, blank code ⇒ still PROCESSING, receipt read
   from the metadata narration, both callback envelopes settle the same intent once, sweep stops at the
   cap.

Buyer-facing read: `GET /api/v1/account/bookings/{id}/payments` and `/intents` scoped by identity (the
plan's "my payments"), which the `/account` area has nowhere to show today.

### Step 6 — One payment flow in the UI (frontend, R8)

Replace `ReceivePaymentModal.vue` and `PaymentModal.vue` with **one `TakePaymentModal`**, opened from the
booking (list, detail) and from `/app/payments` (which first asks for the booking). Shape follows
new-hodi's `MakePaymentModal`:

- A row of method cards from `GET /payment-types/offered/{bookingId}`; the form beneath is chosen by
  `renderAs`, never by an id.
  - **STK**: amount (defaults to due), phone (`AppTelInput`, defaults to buyer), "Paid by". Submit ⇒ the
    form is replaced by "Waiting for the PIN on 07…" with a live state pill fed by polling. `PROCESSING`
    reads as waiting, never as failure. Close is allowed; the Requests tab keeps the truth.
  - **Slip** (only when an inbound channel is configured): reference field + "Find it". On success the
    field locks and a panel shows the bank's figure, payer, date and "this figure cannot be changed";
    submit ⇒ attach. Failure messages inline, verbatim from the server.
  - **Cash / cheque** (platform staff, not their own listing): amount, paid on, reference, paid by.
- Uses `useFormErrors` and `StepWizard`'s `stepFields` where steps exist; no hand-rolled `.err`
  paragraphs beside the automatic toasts.

New **`/app/statements`** page (permission `STATEMENTS_VIEW`), nav under Money:

- Tally line "KES X waiting to be applied · N credits · oldest for D days".
- Filters: search, Used / Unused / Set aside, channel, development, date range; all round-trip through the
  URL so `?state=UNMAPPED` is the worklist link.
- Columns: Bank ref · Amount · Receipt (link, or "—") · They quoted (mono, verbatim) · Channel · Payer ·
  Paid on · Arrived · State (with the reason underneath).
- Row actions on UNMAPPED: **Apply to a booking** (`AttachStatementModal`: read-only "what the bank said",
  a booking search seeded with the quoted text, a confirmation sentence, no amount field) and **Set aside**
  (reason required). On IGNORED: **Restore**.

Booking detail: Requests tab gated on a permission the seller can hold (`PAYMENTS_VIEW`), polled while any
intent is in flight, states worded Sending / Waiting for the payer / Paid / Not paid / Still unknown.

Buyer `/account`: "My bookings" with balance, schedule, a **Pay** button whose methods come from
`offered()` (STK, and slip validation when the setting is on; never cash or cheque), and receipts.

Small fixes that ride along: approvals `SUBJECTS` gains `PAYMENT_ACCOUNT`; `ChannelFieldSet` renders
`select`; methods tab paginates; `PaymentDetailView` gets a print action and a real not-found vs
forbidden distinction; the copy-pasted `.tabs`, `.notice/.err` and money-tile blocks become components.

### Step 7 — Housekeeping

- Remove `pushAndWait`, the unused `DevelopmentVisibility` fields, the dead frontend service methods.
- Rename the nine `*_pesi_*` constraints and indexes in one migration.
- Rewrite `README.md` §payments and archive the `Pesi*` plan documents with a pointer here.
- Set `coop.ipn.allowed.addresses` in production and treat `X-Forwarded-For` only behind a known proxy.

### Step 8 — Disbursements: the bank sends money out (R10, its own module)

**Not payments.** A disbursement debits the bank's account and credits a payee; it never touches a
booking, a payment or a statement. Its own package, `modules/disbursements`, its own table, its own
permissions, its own screen. The adapter already exists, `CoopTransferService`, and stays as the protocol
layer; everything below is the missing module around it.

Purpose, as the client put it: a bank admin disburses funds to a developer, or for any other purpose.

1. **`disbursements` table**: our reference (`DB…`), `payee_kind` (`SELLER_ORGANISATION` | `OTHER`),
   `tenant_id` when the payee is a seller organisation, `payee_name` (typed), `bank_code`, `account_no`,
   **`validated_name`** (what Co-op resolved), `validated_at`, `amount`, `currency`, `narration`,
   `purpose` (free text, required), `source_account_id` → the platform's `COOP_PESALINK` payment account,
   `state` (`DRAFT` → `AWAITING_APPROVAL` → `APPROVED` → `SENT` → `SUCCEEDED` | `FAILED` | `REFUSED`),
   `bank_reference` (`Destinations[0].TransactionID`), `response_code`, `response_description`,
   `raw_response` jsonb, `status_query_attempts`, `processing_reason`, maker/checker columns, audit.
2. **Account validation is the first step and is not skippable.** `POST /disbursements/validate
   {bankCode, accountNo}` → `CoopTransferService.validate` → the account holder's name, stored on the
   row as `validated_name` when the draft is saved. A draft with no validated name cannot be submitted.
   Changing the account number clears the name.
3. **Maker/Checker through `ApprovalService`**, exactly as payment accounts do: `submit` raises a
   workflow with the before/after jsonb so the approvals queue shows **amount, destination account,
   resolved name, purpose**. A `DisbursementApprovalHandler` on `DISBURSEMENTS_APPROVE`; the maker cannot
   be the checker. Refusal ⇒ `REFUSED` with the reason; nothing was sent.
4. **Send on approval, asynchronously.** Approval calls `CoopTransferService.send` with the patient
   timeout; `MessageCode "0"` = accepted for processing, so the row moves to `SENT`, not `SUCCEEDED`.
   Settlement comes from either the FT callback (pesi observed it as the Transaction Status V3 shape:
   envelope + `Source` + `Destinations[]`) posted to a new `CoopRoutes.FT_CALLBACK`, or from
   `CoopTransferService.statusOf` via the `COOP_FT_STATUS` type. Per-leg `Destinations[0].ResponseCode`
   decides; `"0"` succeeded, a given non-zero code failed (the collection's sample is `-5`, insufficient
   balance), silence stays `SENT`. The existing sweep gains a second pass over `SENT` disbursements past
   their deadline, same cap, same reasons. **No automatic retry of a send, ever**: a transfer that may
   have gone out is chased with the query, never re-sent.
5. **Permissions**, all platform-only: `DISBURSEMENTS_VIEW`, `DISBURSEMENTS_MAKE`, `DISBURSEMENTS_APPROVE`.
6. **Catalogue**: `COOP_PESALINK` (kind SEND) is the send channel and needs a platform account carrying
   the source account number; `COOP_FT_STATUS` and `COOP_ACCOUNT_VALIDATION` are already seeded Active.
   Remove the orphan `CoopChannel.COOP_FUNDS_TRANSFER` or seed it; today it is an enum member with no row.
7. **UI**: `/app/disbursements` under Money: a table (reference, payee, resolved name, amount, state,
   bank ref, purpose, maker, checker) with URL-round-tripped filters, and a **Send money** `StepWizard`:
   *Who* (seller organisation from the list, or another payee) → *Account* (bank code, account number,
   **Check the account** button; the resolved name appears as read-only text and the wizard cannot advance
   without it) → *How much and why* (amount, narration, purpose) → *Confirm* (everything, with the
   resolved name in bold) → submits for approval. The approvals queue row for a disbursement reads
   "KES 450,000 to 011… — JANE W. MWANGI, for …". A row's detail shows the bank's raw response and a
   **Ask Co-op now** button for `SENT` rows, uncounted like the STK one.
8. **Tests**: draft without validation refused; maker cannot approve their own; approval sends exactly
   once; accepted ⇒ `SENT`; callback and status query settle the same row once; a given failure code ⇒
   `FAILED` with the bank's description; silence stays `SENT`; WireMock for validate/send/status.

---

## 3a. Progress

| Step | State | Notes |
|---|---|---|
| 1 | **Done, 20 September** | `receive` takes cash and cheque only; a query-settled intent writes an `STK_QUERY` statement and credits through it; a void releases every statement on the payment; `ck_payment_statement` (NOT VALID, voided rows exempt so legacy payments can still be voided); `PayeeResolver` matches the listing reference, then the pay code, corroboration on the code only. The receive form's default of "bank transfer" is now refused with a message pointing at slip validation, until Step 6 replaces the form. |

## 4. Order, and what each step unblocks

| Step | Depends on | Unblocks | Size |
|---|---|---|---|
| 1 One writer, one link, CHECK | — | Everything below relies on "statement ⇒ payment" being one path | M |
| 2 Unused queue endpoints | 1 | 3, 6 | M |
| 3 Slip validation | 2 | 6 | S |
| 4 Biller | 1 (+ Co-op's response schema) | R4 complete | M |
| 5 Buyer STK, non-blocking, tested | 1 | 6 buyer surface | L |
| 6 UI rebuild | 2, 3, 5 | R8 | L |
| 7 Housekeeping | any | — | S |
| 8 Disbursements | Maker/Checker exists already; shares the sweep and WireMock harness with 5 | R10 | L |

Steps 1–3 are one coherent piece and should land first: they make money that has arrived visible and
placeable, which is the outcome the client most needs and the one that has been designed-but-unbuilt the
longest. Step 4 and Step 5 are independent of each other and can run in parallel. Step 6 is where the
"poor UX" complaint is answered and it should not start before the endpoints it renders exist. Step 8 is
independent of everything but the sweep lock in Step 5, and is the only step that moves money out, so it
goes last and gets the most careful review.

## 5. Open questions (none block starting)

1. **A real STK callback sample.** The client can obtain one. Until it arrives, Step 5 accepts both
   envelopes pesi accepts, and the status query settles what the callback does not. The sample becomes a
   pinned test the day it lands.
2. **A real FT callback sample**, same reasoning, for Step 8. pesi's plan assumes the Transaction Status
   V3 shape.
3. **Source IPs** Co-op posts from, for the allow-list.
4. Whether the IPN Basic pair and the biller `connectionID/Password` are the same credentials or two.
   pesi keeps them separate; the descriptors here already do too.
5. **Corroboration on a 4-character code** (Step 1.6): keep it, as proposed, or place on a bare code
   match as new-hodi does. The listing reference places directly either way.

## 6. Checks (definition of done)

- No payment row with `method NOT IN ('CASH','CHEQUE')` and `statement_id IS NULL` can be inserted.
- A notification that cannot be placed appears on `/app/statements?state=UNMAPPED` within one request of
  arriving, with its reason, and can be attached to a booking with two clicks and no amount typed.
- Slip validation returns only unused statements, each with the channel it arrived through. Entering a
  reference that already paid a booking is not offered for attachment and the message says which booking;
  entering one we never saw says so; entering an unused one shows the bank's figure and attaches it.
- A credit quoting a listing reference lands on that listing's live booking; one quoting the 4-character
  code lands on its unit's live booking; the same resolver answers a biller validation with that booking's
  buyer and amount due.
- A disbursement cannot be submitted without a Co-op-resolved account name, cannot be approved by its
  maker, is sent exactly once on approval, and never reads as succeeded until the bank says so.
- A buyer signed into `/account` can prompt their own phone for their own booking; the screen never says
  "not paid" while the bank has not said so.
- Both Postman biller bodies, posted verbatim with the right password, produce the expected answer and, for
  the advice, exactly one statement and at most one payment however many times it is redelivered.
- `mvn test` green including new WireMock tests for the outbound client; `npm run build` green.
