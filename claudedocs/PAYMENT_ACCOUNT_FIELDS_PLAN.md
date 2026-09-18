# A payment account asks what its channel needs

**18 September 2026.** Four instructions, one root cause between them.

---

## 1. What is wrong

### 1.1 The account form asks the same four questions of every channel

`payment_accounts` carries `pay_bill_no`, `account_no`, `account_name` and `short_code`, and
`PaymentAccountService.create` writes all four whatever the channel is. The screen decides which to
show from two booleans it invented — `requiresShortCode`, and `category == VALIDATE`.

Those booleans are not derived from anything. They are a guess at what a channel needs, and the guess
is wrong in both directions:

| Channel | What it actually needs | What the form asks for |
|---|---|---|
| `COOP_STK_PUSH` | An **operator code**, a consumer key, a consumer secret | Account number, account name |
| `COOP_BILLER` | Institution code, service name, institution name, connection ID, connection password, auth username, auth password, validation URL, advice URL — **nine**, per biller | Account number, account name, paybill, short code |
| `COOP_IPN_ACCOUNT` | Account number, callback URL, auth username, auth password | Account number, account name, paybill, short code |

`account_name` is not a field any of them has. The Biller's nine have nowhere to go at all.

**The operator code is the clearest case.** A Co-op phone prompt takes no paybill and no short code —
the value is a short word naming the operator. Asking for a paybill there is asking for something the
channel has no slot for, and storing it in `pay_bill_no` makes the column a lie.

### 1.2 A descriptor already exists — one level too high

`V20260918100000` gave `payment_types` a `required_config_fields` descriptor and a `config` blob, and
the reasoning in that migration is the reasoning here:

> A host or a path compiled into the application has to be redeployed when the bank moves one […]
> Adding a channel is a row, not a deploy.

But it describes the **channel's own** configuration — hosts, paths, callback URL — set once by the
platform. Nothing describes what an **account** on that channel needs, which is the form an
organisation fills in. So the channel is data-driven and the account is still hardcoded.

### 1.3 Two more, from the same instruction

- **OTP on create and edit.** `PaymentAccountService` issues a code to the owner's phone and consumes
  it before writing. The platform has had Maker/Checker since `V20260918090000`, and a second pair of
  eyes is a stronger control than a code sent to the same person who is typing. Two controls on one
  action, one of them weaker, is one too many.
- **Who collects.** Money is channelled to the platform. The ownership columns for the alternative are
  already there — `PaymentAccount.tenantId` / `institutionId`, neither set meaning the platform's own,
  with `PaymentScope` enforcing who may see which. What is missing is the **switch** that says an
  organisation collects its own.

---

## 2. What we build

**One row per account**, carrying the code the channel resolves by and one blob of everything that
account needs. The biller's nine fields are not homeless; they are one column.

A note on where the evidence comes from. Co-op's own field lists were read off a working integration
against the same bank, which is why the biller's nine are named rather than guessed. What is *not*
carried across is that system's structure — it fronts many banks for many companies and this platform
banks with one, for itself. Where its shape encodes layers this platform does not have, the shape is
dropped; §2.1 and §2.1a are both that correction.

### 2.1 An account *is* the configured method, and asks for one flat list of fields

`payment_accounts` is the scoped, configured method on this platform: one organisation, one channel,
one row. So its descriptor uses the same grammar `required_config_fields` already uses one level up —
a `fields` list — and everything that reads a descriptor reads both without knowing which it was
given.

**Corrected during implementation.** The first draft stored the descriptor *grouped* — `super`,
`company`, `business`, `accounts` — on the reasoning that a descriptor lifted from a gateway that has
those layers should then need no editing. That was structure kept for a system this platform no
longer talks to. There is no company layer here and no business layer, so every group rendered on the
one form in the one order, the grouping decided nothing, and reading it cost `ChannelConfig` eight
overloads and a pair of group constants. All of it is gone; the fields are one list.

```json
{"fields": [{"key":"consumerKey","label":"Co-op consumer key","type":"text","required":true},
            {"key":"consumerSecret","label":"Co-op consumer secret","type":"password","required":true},
            {"key":"accountNumber","label":"Operator code","type":"text","required":true}],
 "accountsLabel": "Biller",
 "accountKey": ["institutionCode", "serviceName"]}
```

`accountKey` and `accountsLabel` stay, because neither is ceremony: the first is forced by Co-op's
biller advice carrying an institution code and a service name and no id (§2.2), and the second is
what the form calls the thing it is adding.

A field is `{key, label, type, required, fullWidth?, options?}`; `type` is `text`, `password` or
`select`. Nothing in the code knows what a connection ID *is*.

So the three Co-op channels ask for what they actually need, and nothing else:

| Channel | Fields on the account |
|---|---|
| `COOP_STK_PUSH` | consumerKey, consumerSecret, **operator code** |
| `COOP_IPN_ACCOUNT` | whitelistedIps, account number, callbackUrl, callbackUsername, callbackPassword |
| `COOP_BILLER` | the nine: institutionCode, serviceName, institutionName, connectionID, connectionPassword, callbackUsername, callbackPassword, validationUrl, adviceUrl |

No paybill. No short code. Neither appears in any Co-op descriptor, which is the whole of the first
complaint.

### 2.1a One host, one endpoint per channel, no environment

**Corrected during implementation.** `V20260918100000` gave every payment type a sandbox host, a
production host, a token path, a request path and a status path, with a `coop.environment` setting
picking which host was live.

That is two sources of truth for one fact. An address that reads sandbox *is* the sandbox; a setting
beside it that can disagree is a way to pay the wrong bank. And a bank has one base address, not one
per channel.

So:

- `coop.base.url` — one platform setting, shared by every channel. What is in it decides which
  environment this deployment talks to, and nothing else does. Blank means no call is attempted.
- `coop.token.path` — one for the bank, not one per channel.
- `coop.environment` — **deleted**.
- `payment_types.config` keeps only `endpoint`, the one path that is that channel's own, plus the
  `callbackUrl` Co-op should call back on.

One endpoint and not a choice of several, because each payment type is one operation: where a second
is needed — a status query against a prompt — that is a second operation and gets its own type.
`CoopClient.post` therefore no longer takes a path key.

Nothing was configured yet — every Co-op setting and every `payment_types.config` was blank — so this
was a reshape with no values to carry.

There is no `environment` field on any account descriptor either, for the same reason.

### 2.2 `account_no` is the code the channel resolves by — and the platform holds no bank account

Money is going to a bank, so behind every channel but cash and cheque there is a real account. **This
platform does not store its number, deliberately.** The bank already knows which account a code maps
to, and a copy here would be a second record of something we are not the record of.

What is stored is the **code**, one per channel, and one organisation holds several of them resolving
at Co-op to the same account: `HODI` on one payment type, `QUANTUMNEX` on another — the shape live
Co-op integrations already use.

That code is also what makes the list readable: somebody can see which account a channel lands in
without the number being on the screen at all.

**Composed, because one code is not always one field.** A biller advice carries an institution code
and a service name in its header and no id, so the only way to know which biller it is for is to
compose the pair — whitespace stripped, lowercased, so the key reads `210001918breezeestate` rather
than `210001918`. `accountKey` in the descriptor names the fields that
compose it, so a channel keyed on one needs no code at all and a third way of keying needs a row.

**Resolution takes whichever Co-op sends.** An inbound notification carries the code or the account
number depending on the channel, and for an account IPN those are the same string — the credit arrives
naming where it landed. So there is one column and one lookup: match the payload against `account_no`
for that payment type.

Unique per `(account_no, payment_type_id)` on live rows, not platform-wide: one organisation may
legitimately hold the same string on two channels, and what must not collide is two accounts on one
channel answering to one code.

### 2.3 `payment_accounts.config jsonb`, secrets encrypted field by field

**Corrected during implementation.** The draft encrypted the whole map into one `text` blob. This
codebase had already answered the same question one migration earlier, differently and for the same
module:
`payment_types.config` is `jsonb` with only the `password` fields encrypted, and `ChannelConfig`
holds the read/write/mask rules.

Two storage strategies for "config values by descriptor key", in one module, would be worse than
either. So the account reuses `ChannelConfig` exactly, unchanged — the flattening in §2.1 is what makes that
possible, since both columns now carry the same `fields` list.

What that keeps, unchanged: a secret goes in encrypted and reads back as `MASK`; the mask sent back on
a save means "leave it alone"; a blank secret clears it; a key the descriptor does not name is
dropped. What it gives up against a single encrypted blob: the non-secret values are readable in the
database, which they already are on `payment_types`.

### 2.4 Consuming it: the method pulls its own account

The flow, per request:

1. The request arrives carrying whatever identifies the account — institution code and service name
   for a biller, the paid-to account for an IPN.
2. Compose the key from the descriptor's `accountKey` and look the account up by
   `(account_no, payment_type_id)`.
3. Decrypt its config.
4. **Authenticate against it** — the biller compares `connectionID` and `connectionPassword` from the
   config with the header, in constant time. Credentials belong to the biller, not to the platform,
   so this is per-account and cannot be a global setting.
5. Read the rest by key: `validationUrl` and `adviceUrl` to call the end system, `institutionName`
   for the response.

Each step is the account's own values. Nothing about Co-op is compiled in.

### 2.5 The old columns

`pay_bill_no`, `account_name` and `short_code` hold live data, and `short_code` carries an index and
`findLiveByShortCode`, which routes inbound credits. They stay, and are written **only where the
descriptor declares a field of that name** — otherwise left null rather than filled with whatever the
form happened to show. `account_no` is now the composed code and is always written.

**Cash and cheque are the exception and stay one:** they need no account at all, because the money is
in front of somebody who records it by hand. Every other channel must have a code, since without one
no notification on it can be attributed.

Retiring them is a later migration, once nothing reads the columns directly. A schema change and a
behaviour change in one step is two things to be wrong about.

### 2.6 Maker/Checker replaces the OTP

`PaymentAccountApprovalHandler implements ApprovalHandler`:

- `entityType()` → `PAYMENT_ACCOUNT`
- `decidePermission()` → `PAYMENTS_ACCOUNT_APPROVE`, the domain's own. Not a decide-anything
  permission, for the reason `ApprovalService` states.
- `assertMayDecide` → the decider belongs to the owning organisation, or is platform staff. The
  submitter is already barred by the workflow and by the database CHECK.
- `onApproved` → apply the pending values.

A create or an edit records a `ChangeSet` and returns "waiting for approval" rather than a saved row.
`OtpRequest`, `OtpIssued`, `requestCode`, the `otp` and `challengeToken` fields and the OTP endpoints
go.

**The `ChangeSet` must carry the masked values, never the config.** A pending edit is readable by the
checker, and a queue that prints a connection password is worse than the OTP it replaced.

**And a behaviour change with teeth:** an organisation that could set up an account in one sitting now
needs a second person. That is what Maker/Checker is, and it is worth saying here rather than in
support.

### 2.7 Who collects

One configuration row, `payments.collection.scope`:

- `PLATFORM` — every payment goes to the platform's account. **The default**, and what happens today.
- `ORGANISATION` — an organisation with its own configured account collects to it; one without falls
  back to the platform's, so turning this on cannot leave anybody unable to take money.

`PaymentService` resolves the collecting account by that setting. `PaymentAccount.tenantId` /
`institutionId` — neither set meaning the platform's own — and `PaymentScope` already express
ownership and visibility; neither changes.

## 3. Order

1. Migration: account descriptors and `accountKey` for the three Co-op channels;
   `payment_accounts.config jsonb`; the unique index on `(account_no, payment_type_id)`; the
   `payments.collection.scope` row; the `PAYMENTS_ACCOUNT_APPROVE` permission. **Done.**
2. `PaymentAccountService`: validate from the descriptor, compose `account_no` from `accountKey`,
   encrypt the whole config on write, mask secrets on read. Delete the OTP path.
3. `PaymentAccountApprovalHandler`, and submit-for-approval on create and edit.
4. `PaymentService`: resolve the collecting account from the scope setting.
5. Frontend: the account form renders from the descriptor; the OTP step goes; the queue shows pending
   accounts.

Steps 1–2 are the ones that make the Co-op prompt stop asking for a paybill. 3–5 can follow.

---

## 4. What this does not do

- **No gateway.** Co-op is reached directly, as `V20260918100000` established. Nothing here calls out
  to a payments gateway and nothing should be added that does.
- **No change to the inbound path.** `coop_statements` and short-code routing are untouched.
- **No adapters yet.** §2.4 is the contract the Co-op flows will read through; wiring
  `CoopBillerService`'s equivalent to it is the next piece, not this one.
- **No field editor.** Descriptors are seeded by migration. A screen for editing them is a later
  question, and a bad one to answer early — a malformed descriptor breaks the form that repairs it.

---

## What was built, and what the database had to say about it

Steps 1, 2 and 5 of §3 are done, plus §2.7. Steps 3 (Maker/Checker) and 4 (which account collects at
payment time) are not — **the OTP stays** until the approval handler replaces it, because removing it
first would leave the highest-consequence write on the platform with no second control at all.

### The collection scope is applied at set-up, and only there

`payments.collection.scope` was a row nothing read. It now decides whether a non-platform owner may be
written: refused on create, on reactivation, and on platform staff attaching one *for* an organisation —
a control the operator can step around only documents an intention. `assignable` returns an empty list
rather than letting a form be filled and then refused, and the owner select does not render at all when
there is nothing to choose. Existing accounts are never stranded: withdrawing one is always allowed,
because money has already been routed through it and a setting cannot unwind a payment already taken.

**The default is `PLATFORM`, and that is a behaviour change**, not "what happens today" as the migration
claimed — organisations do attach accounts today, and eleven tests prove it. They now state the
precondition instead.

### Two constraints from 2026-09-08 refused correct rows

Found by writing the first real Co-op account, not by reading:

- `ck_payment_account_fields` demanded `account_name` on every non-manual account. **No Co-op channel has
  such a field.** Relaxed to demand the code alone — without that, no inbound notification can be
  attributed, which is the failure worth refusing over. A name is the bank's record, not this one's.
- `uk_payment_account_no` made the code unique platform-wide. One organisation legitimately holds
  several — an operator code on the prompt, an institution code and service name composed on the biller —
  so it is dropped in favour of the per-channel index. The platform-wide rule is not abandoned: the
  service still applies it to every channel with no descriptor. The database cannot make that
  distinction without joining `payment_types`, so the service makes it.

### Proven, not asserted

`PaymentTypeServiceIT`, 20 tests. The ones that matter here: a phone prompt stores an operator code and
two credentials and **no paybill and no name**; its secret is ciphertext on disk and a mask in the
response; a biller is keyed on `institutionCode + serviceName` composed, giving `210001918breezeestate`;
a missing field is refused **by label** before the code is spent; and one code may sit on two channels
but not twice on one. Whole suite 323 green, `vue-tsc` and `npm run build` clean.

### Frontend

`ChannelFieldSet.vue` renders any descriptor and is shared by both forms — the account's, and the
channel's own wiring behind **Configure** on the catalogue. Neither knows what a connection ID is.
A secret is pre-filled with the mask rather than left blank, because blank means *clear it*: a form that
silently wiped a consumer secret whenever somebody corrected a URL would break nothing at save time and
everything on the next call.
