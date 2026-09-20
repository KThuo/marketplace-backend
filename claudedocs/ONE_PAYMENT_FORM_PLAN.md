# One payment form, and who may see what

> **Superseded, 20 September 2026.** Written when payments went through the pesi gateway. The names,
> the shape and the sequence below no longer describe the code. The current plan and progress is
> [PAYMENTS_REBUILD_GAP_ANALYSIS_AND_PLAN.md](PAYMENTS_REBUILD_GAP_ANALYSIS_AND_PLAN.md). Kept for the
> reasoning that still holds.


**18 September 2026.** Seven corrections from using it, which reduce to three ideas.

---

## 1. One form, and it changes with the method

There are two forms today — "Record a payment" and "Ask for payment by phone" — and they ask the same
first question. Hodi's invoice payment is the model: **one dialogue, a row of methods across the top,
and the form underneath decided by the method's own `renderAs`** rather than by an id. A switch on
catalogue primary keys breaks the day a channel is added and cannot express one it has not met.

**A method appears only if somebody can actually start it here.**

| Method | In the form? | Why |
|---|---|---|
| Co-op STK | **yes** | the platform initiates it |
| Co-op Account (IPN) | no | the payer starts it at their own bank; we find out afterwards |
| Co-op Biller | no | same |
| Co-op PesaLink | no | **it debits**. A form for taking money must not offer a way of sending it |
| Cash, cheque | **conditionally** — see §2 | the money is in front of somebody |

The rule is not a list: it is *"can this be started from this screen, and does it credit"*. Enquiries
are already excluded by `kind`; this adds inbound and outbound.

## 2. Who may record cash

Cash and cheque have no gateway behind them — they are somebody asserting money arrived. So:

- they must be **configured** (an account exists on the channel), as any other method must be;
- they are offered **only to platform staff**;
- and **only where the listing is not the caller's own**.

The last is the one worth writing down. A seller recording cash against their own unit is marking
their own money received with nothing behind it, and the platform has no way to disagree. Requiring a
second party is the whole control, and it is the same reasoning as Maker/Checker on a payment account.

## 3. Slip validation, once there is something to validate against

An inbound payment that never matched sits in the unmatched queue. Today the only way out is a person
reading it and there is no screen. **Where a biller or an inbound account is configured, the form
gains a "validate a slip" method**: somebody enters the reference the payer quoted, the platform finds
the statement that arrived, shows what it says, and attaches it to this booking.

This is the answer to "the money arrived and nothing happened" — which, with matching as strict as it
is deliberately, will happen.

## 4. What a payer sees, and what an administrator sees

- **Payment requests (intents) are administrative.** A prompt that failed, its status queries and the
  reason it is stuck are operational detail. Only platform staff see them.
- **Everybody else sees payments that succeeded**, which is what a receipt is.
- Both are **tables**, not stacked lists. A list of one payment reads fine and a list of nine does
  not, and these are rows with the same columns every time.

## 5. Not in this piece

- **The listing detail page.** Agreed that it is cluttered, and it is a separate piece of work — it is
  not about payments and folding it in would make both changes harder to judge.

## 6. Order

1. Server: one `offered` answer carrying the methods this caller may start against this booking,
   with the rules above applied there rather than in the browser.
2. The one form, rendering by `renderAs`, replacing both existing modals.
3. Tables, and the intents list behind a platform-staff check.
4. Slip validation as a method within the same form.
