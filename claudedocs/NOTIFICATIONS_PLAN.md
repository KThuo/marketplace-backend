# Notifications, reminders and newsletters: what exists, what does not, and the design

29 September 2026. Assessment and scoping — nothing built yet.

## 1. What exists today

**A consent store that is done properly.** `consent_preferences` holds, per person, a grant or refusal for
each of two channels (EMAIL, SMS) against three purposes (TRANSACTIONAL, PROPERTY_ALERTS, PROMOTIONAL),
with source, IP, user agent and time; every change is appended to a history and to the audit trail; the
transactional purpose cannot be refused (a CHECK enforces it); registration captures an opening position
with a single unticked box for alerts and never a marketing consent behind it. The buyer's portal has a
"What we send you" page with the grid and the history. Every sender asks `ConsentService.channelsFor`
before sending, and an empty answer is silence, never a fallback to email.

**A notify client that is careful.** `NotifyClient` sends SMS and email through one gateway, masks
recipients in logs, has a dry-run switch the test suite sets so no fixture messages a real phone, drops
sensitive sends (codes, resets) past the global on/off switches and non-sensitive ones behind them, and
returns a result rather than throwing. `MailTemplate` renders four shapes — a code, an action, a notice,
a digest — in the platform's own palette with contrast checked, escaping everything, with a footer
that names the reason and, on the one kind that can be switched off, the way to switch it off.

**Senders, one per module, each hand-written.** `LeadNotifier` (enquiries, viewings, offers, counters,
messages), `ValuationNotifier` (assignment, hand-back, report, review, lapse, overdue), `BookingNotifier`
(terms, the hold reminder), a receipt SMS in `PaymentService`, OTP and password reset in auth, and the
saved-search digest (`SearchAlertRunner`, instant / daily / weekly, with an unsubscribe footer). Each
writes its own sentences in Java.

**Reminders:** one — the hold-expiry reminder added with the booking terms. **Newsletters:** none.

## 2. Is it well implemented?

Partly. The foundations — consent, the client, the templates — are sound and better than most. What is
built on them is narrow, hard-coded and, in one respect, broken.

### 2.1 Staff are silently never told (a bug)

Only `BuyerRegistrationService` writes consent rows. A seller's owner onboarded by the platform, a
platform administrator, a valuer, an agent — none of them has a row, so `channelsFor` returns nothing
for them, and every `toSeller`, `toPlatform`, `toValuer` and `toRequester` sends nothing. The sellers'
"new enquiry" and "new offer" notices, the platform's "valuation to assign" and "report to review", the
valuer's "you have been assigned" — all of it is dropped without a log line above `debug`. (Verified in
code: no other caller of `captureAtRegistration`, and the repository query returns granted rows only.
The dev database could not be queried for a count from this session.)

### 2.2 Nothing is configurable

Which event tells whom, on which channels, in which words — all of it is Java. The bank cannot switch
off "new message on an offer" for its staff, a seller cannot choose SMS over email for enquiries, nobody
can edit a sentence without a deploy. The two global switches (`notify.sms.enabled`,
`notify.email.enabled`) are off by default and are the only knobs. There are no quiet hours, no daily
caps, no per-organisation sender id, no test send.

### 2.3 No newsletters, and the consent for them is collected for nothing

PROMOTIONAL consent is captured and shown on the preferences page, and nothing on the platform sends a
promotional message. There is no campaign, no audience, no approval, no unsubscribe token for someone
reading on a phone without signing in, no suppression list.

### 2.4 Reminders barely exist

The hold-expiry reminder is the only one. Missing: an instalment due in N days; an instalment overdue
(and again every N days); a viewing tomorrow; a KYC or compliance document expiring; a pending approval
sitting with a checker; an unanswered enquiry. The valuation sweep has two more, hard-coded.

### 2.5 Nothing is recorded, retried or shown

A send is a log line. There is no `notification_log` — no way to answer "did the buyer get the receipt",
no delivery status from the gateway, no retry of a failed send, no bounce handling. There is no in-app
notification: a seller who has email switched off has no bell to look at.

### 2.6 Wording lives in code

The email shapes are fine; the sentences inside them are string concatenation in six classes, and the
SMS texts likewise. The booking terms template is the one piece of customer wording the platform can
edit itself.

## 3. The design

### 3.1 Every person has a position on consent

Every onboarding path writes the opening consent rows (`ConsentService.captureAtRegistration`, with
alerts false and no marketing) — tenant owner, platform user, valuer, agent — and a migration backfills
every live user without rows, TRANSACTIONAL granted on both channels, the rest refused. The preferences
page is reachable from the staff layout too. `channelsFor` warns, once per user, when it finds no rows.

### 3.2 A catalogue of notifications, configurable

```
notification_events        one row per thing the platform can say
  code                     ENQUIRY_RECEIVED, OFFER_DECIDED, VALUATION_ASSIGNED, BOOKING_TERMS_PRESENTED,
                           PAYMENT_RECEIVED, INSTALMENT_DUE, INSTALMENT_OVERDUE, VIEWING_TOMORROW, …
  audience                 BUYER | SELLER_STAFF | PLATFORM_STAFF | VALUER | AGENT
  purpose                  TRANSACTIONAL | PROPERTY_ALERTS | PROMOTIONAL   (what consent it is under)
  enabled, channels        the platform's default: EMAIL, SMS, IN_APP, any subset — IN_APP on for every event
  permission               for staff audiences: who holds it is who is told (as today)
  subject, body_email,     the wording, with placeholders, versioned like the booking terms
  body_sms
notification_overrides     per organisation (tenant / institution): enabled, channels, wording
```

One `NotificationService.send(code, recipient, model)` replaces the hand-written notifiers: it looks up
the event, applies the organisation's override, asks consent for the event's purpose, renders the
wording, sends on each channel, and writes the log. The three notifiers become thin: they name the
event and hand over the model. A Settings → Notifications page lists the catalogue, lets the platform
edit wording and defaults, and lets an organisation's administrator switch events on or off and choose
channels for their own staff — and reword them, when the platform's `notify.organisation.wording.enabled`
says organisations may.

### 3.3 In-app as a third channel

`notifications` (user, event, title, line, link, read_at) with a bell in both layouts and a page;
written whenever IN_APP is among the channels, which it is for every event and every user by default.
The seller who turned email off still sees the enquiry; the buyer sees their receipt without opening
mail. IN_APP is the third column of the consent grid, granted by default, and never refusable for
TRANSACTIONAL.

### 3.4 A log, delivery status, retry

`notification_log` (event, recipient, channel, address masked, status QUEUED | SENT | DELIVERED |
FAILED | BOUNCED, provider reference, error, attempts, sent_at). Sends go through the log: written
QUEUED, sent, updated; a sweep retries FAILED with backoff up to a cap; the gateway's delivery callback,
where it offers one, updates DELIVERED / BOUNCED. Two bounces on an address suppress it and say so on
the person's profile. A platform page shows the log with filters, and each booking's, offer's and
valuation's page shows what was sent about it.

### 3.5 Reminders as rules

```
reminder_rules
  code            INSTALMENT_DUE, INSTALMENT_OVERDUE, VIEWING_TOMORROW, DOCUMENT_EXPIRING,
                  APPROVAL_WAITING, ENQUIRY_UNANSWERED, HOLD_EXPIRING (moved here), VALUER_LAPSE (moved)
  days_before / days_after / repeat_every_days, enabled, event code to send
  platform default, overridable per organisation
```

One daily sweep (plus the hourly hold one) evaluates every enabled rule and sends through §3.2, with a
"said once" column per subject so nothing repeats unless the rule says to.

### 3.6 Newsletters and campaigns

```
campaigns        title, audience (BUYERS | SELLER_STAFF | …, with filters: county, saved-search
                 interest, development, registered since), channel(s), subject, body (the digest
                 shape, with cards), state DRAFT | SUBMITTED | APPROVED | SENDING | SENT | CANCELLED,
                 scheduled_for, sent counts
campaign_sends   one row per recipient, into notification_log
```

Under PROMOTIONAL consent only, through the approval engine (a campaign is a maker-checker action),
sent in batches under a per-minute throttle and a daily cap, inside a sending window (no marketing at
02:00). Every promotional message carries a one-click unsubscribe link with a signed token that works
without signing in and writes the refusal to the consent store as `source = UNSUBSCRIBE_LINK`; SMS
carries "Reply STOP" where the gateway supports it, and a plain "change what we send you" link
otherwise. A test send to the author before approval. Audience counts shown before sending.

### 3.7 Channel configuration

Per organisation: SMS sender id (where the gateway allows one per client), reply-to address, footer
lines (physical address, registration number — the Kenya Data Protection Act expects a sender to be
identifiable). Platform-wide: quiet hours for non-transactional messages, daily caps per channel, and a
"send a test to me" on every template.

## 4. Build order

1. **Consent for everyone** — §3.1. A bug fix; ships alone; restores every staff notice that is
   silently dropped today.
2. **The log and in-app** — §3.4 and §3.3, wrapping the existing notifiers so nothing changes what is
   said, only what is recorded and where it is seen.
3. **The catalogue** — §3.2: the events table with the wording moved out of Java, the Settings page,
   organisation overrides; the notifiers thinned to event names.
4. **Reminders** — §3.5.
5. **Campaigns** — §3.6 and §3.7.

## 5. Decisions taken (29 September 2026)

1. **In-app is a channel for every user**, staff and buyers alike: each person sees their own messages
   in the bell and on the page. It joins EMAIL and SMS in the consent grid as a third channel, granted by
   default for every purpose — it is a page the person opens, not a message pushed at them — and it
   cannot be refused for TRANSACTIONAL, like the other two.
2. **Sellers may reword** the messages their own staff and buyers receive — behind a platform setting
   (`notify.organisation.wording.enabled`, off by default), so the platform decides whether to allow it
   at all. The platform's wording is the fallback for every event an organisation has not reworded.
3. **No WhatsApp** for now.
4. **The gateway reports no delivery or bounces.** The log stops at SENT / FAILED; retry is on FAILED
   only; suppression comes from a person's refusal, not from bounces. The DELIVERED / BOUNCED states
   stay in the design for the day the gateway offers a callback, and nothing depends on them.

5. **Campaigns are the platform's**, through its maker-checker; a seller may run one to its own buyers
   with its own checker only behind the same organisation setting as wording.
6. **Non-transactional sending happens 08:00–20:00 East Africa Time** with a daily cap per channel,
   both platform settings.

Nothing left to confirm before phase 1.

## 6. Progress

### Phase 1 — consent for everyone — done (29 September 2026)

- `V20260929130000__everyone_has_a_position_on_consent_and_in_app_is_a_channel.sql`: IN_APP joins the
  channel CHECK; every live user without rows gets the opening position (transactional and in-app
  granted, the rest refused, source BACKFILL), which restores every staff notice that was silently
  dropped.
- Every onboarding path — a seller's owner, a platform user, a valuer, an agent, a vendor, a seller
  applicant, the seeded administrator — records the opening position through
  `ConsentService.captureAtOnboarding` (source ONBOARDING, idempotent). `channelsFor`, the question every
  sender asks, records the opening position and warns when it finds a person nobody ever asked, instead
  of returning silence.
- In-app is the third column of the consent grid: granted by default for every purpose, refusable for
  alerts and marketing, never for transactional. The "What we send you" page shows it and is now in the
  staff menu under Mine, at `/app/notifications`, as well as in the buyer's account.
- Tests: `ConsentForEveryoneIT` (3): the opening position from onboarding; a sender finding nothing
  records the position rather than dropping the notice; the grid with in-app on, refusable, and refused
  for transactional.

### Phase 2 — the log and in-app — done (29 September 2026)

- `V20260929150000__every_message_is_recorded_and_the_person_has_an_inbox.sql`: `notification_log`
  (user or contact, event, purpose, channel, recipient kept for the retry and masked for the eye,
  subject, the body as composed, status QUEUED | SENT | FAILED | SKIPPED, the gateway's reference or
  error, attempts, next attempt, what it was about) and `notifications` (a person's inbox: title, line,
  link, what it is about, read or not).
- **One door** — `NotificationService`: a notice for a user asks consent for its purpose, writes an
  inbox line when in-app is granted, and a log row plus a send on each other granted channel; a notice
  for a contact with no account goes by email and SMS, logged without a user. A module that composes
  its own message (the digest, the receipt) records it through the same door. The gateway's "skipped"
  is SKIPPED and left alone; its "failed" is FAILED and tried again by `NotificationRetrySweep` every
  minute with backoff — 2, 4, 8, 16, 32 minutes — up to `notify.retry.max.attempts` (new setting,
  default 5). The three notifiers are thin now: they name who is told and hand over the words; the
  offer, booking and valuation notices say what they are about, so each thing's page shows what was
  sent about it. A buyer's booking link is encoded with the buyer's own salt, since hashed ids are
  per user — the old link would have decoded to nothing in their hands.
- **The inbox** — the bell in both layouts (unread count, the latest eight, mark all read, polled once
  a minute while the tab is visible), a page at `/app/inbox` and `/account/inbox`, and read-on-open.
- **The record** — Settings → Sent messages (readers of the audit trail): every message with channel,
  masked recipient, subject, what it was about and the outcome, filtered by channel and outcome, with
  Retry on a failed one; and a "Messages sent" card on the booking, offer and valuation pages.
- Not moved: the one-time codes and password resets in auth, which stay on the sensitive path and are
  not logged with a body.
- Tests: `NotificationsIT` (3): a notice to a user is a row per channel and an inbox line, and says what
  it is about; a contact is recorded without a user and masked; the retry's arithmetic — due, not yet,
  capped, and skipped never.

### Phase 3 — the catalogue — done (29 September 2026)

- `V20260929170000__what_the_platform_says_is_a_catalogue_the_platform_can_edit.sql`:
  `notification_events` — 34 rows, one per notice a module sends (enquiries, viewings, offers, booking
  terms, the hold reminder, the receipt, every valuation notice, the saved-search digest) with audience,
  purpose, on/off, default channels, subject and line with placeholders, and the list of placeholders
  the event fills; and `notification_event_overrides` — an organisation's own answer per event (off,
  narrower channels, its own words), null meaning "as the platform says".
- **The layering** — `NotificationCatalogue.apply`: the platform's row, then the organisation's
  override (it may switch off and narrow, never switch on what the platform switched off, and reword
  only when `notify.organisation.wording.enabled` — new setting, off by default — says so), then the
  person's consent intersected. An event nobody catalogued is not sent, with a warning. A wording that
  names a placeholder the event does not fill is refused with the list it does.
- **The wording is out of Java.** The three notifiers now name an event and hand over the figures;
  the notification service resolves it for the organisation whose business it is — the seller on an
  offer, the booking's owner, the valuation's requester — fills it, and delivers as before. Nothing
  that is said changed. One-time codes, resets and the panel welcome stay on the sensitive path.
- **Settings → Notifications**, for two readers. The platform sees every event grouped by audience,
  switches it on or off, sets default channels, and rewords it with a preview and the placeholders
  listed. An organisation's administrator (`APP_SETTINGS_OVERRIDE`) sees the events that concern it —
  what its buyers and its own staff receive — with the platform's answer beside its own.
- Tests: `NotificationCatalogueIT` (4): the seed is complete and every placeholder a wording asks for is
  one the event fills; the layering as a pure function; a filled event delivered, then switched off,
  narrowed and reworded by the platform, a wording with a hole refused, an unknown code silent; an
  organisation's override applying to its own events and refused for the platform's.

### Phase 4 — reminders as rules — done (29 September 2026)

- `V20260929190000__reminders_are_rules_the_platform_sets_and_an_organisation_may_tune.sql`:
  `reminder_rules` (eight: an instalment due, money overdue, a hold about to lapse, a viewing coming up,
  an enquiry unanswered, a decision waiting, a document expiring, a valuer's lapse — each with days,
  whether it repeats and every how many, on/off), `reminder_rule_overrides` (an organisation's own
  days or off), `reminder_sent` (said once per rule, subject and key, with when and how many times),
  and nine catalogued events the rules send, reworded like the rest.
- **The sweep** — `ReminderSweep`, daily at 07:05 under an advisory lock, one pass per rule: an
  instalment falling due in the rule's days and not yet covered by what was paid (through the balance
  view); money overdue, said the rule's days after the earliest uncovered due date and again as the
  rule repeats; a confirmed viewing the rule's days away, to the buyer and to the seller's staff; an
  open enquiry whose last word was the buyer's, keyed on that message so a new one starts the count
  again; a pending approval, to the organisation's or the platform's holders of the deciding
  permission; a vault document expiring within the rule's days, to the organisation or the person.
  Every rule is resolved for the organisation whose thing it is, so a seller's own days apply to its
  own bookings and enquiries.
- The hold-expiry reminder and the valuer's lapse warning now read their days from the same rules;
  their two private settings are gone.
- **Settings → Notifications** gains a Reminders card at the top: the platform sets days and repeat and
  switches rules off; an organisation sees the platform's answer beside its own and tunes its own.
- Tests: `ReminderSweepIT` (4): an instalment due said once and money overdue said again a week
  later; an organisation moving the days and switching a rule off for its own bookings; an enquiry
  unanswered said, said again as the rule repeats, and silent once answered; the layering and the
  "said once / again every N days" arithmetic as pure functions.

### Phase 5 — campaigns — done (29 September 2026)

- `V20260929210000__a_campaign_is_written_checked_and_sent_inside_the_window.sql`: `campaigns` (owner —
  the platform, or an organisation — title, audience filters by county and joining date, channels,
  subject, body, a button, state DRAFT | SUBMITTED | APPROVED | SENDING | SENT | CANCELLED, when to
  send, the counts), `campaign_sends` (a row per recipient, fixed at approval), and a CAMPAIGN event in
  the catalogue that decides on/off and the channels a campaign may use.
- **Whose campaign**: the platform's go to every live buyer with promotional consent on at least one of
  the chosen channels, narrowed by county (their saved searches or enquiries) or by when they joined. An
  organisation's go only to its own buyers — the people who enquired about, offered on or booked its
  homes — and only behind `notify.organisation.wording.enabled` (decision 5). New module CAMPAIGNS with
  `CAMPAIGNS_VIEW`, `CAMPAIGNS_MANAGE`, `CAMPAIGNS_APPROVE`; the seller owner template carries all three.
- **Checked before it goes**: submitting is an approval (`CampaignApprovalHandler`); the checker sees
  the words, the audience count and the channels, and the author cannot approve their own. At approval
  the audience is fixed as a row per recipient, so the number the checker saw is the number it goes to.
  A cancellation before the end stops what has not gone.
- **Sent inside the window**: `CampaignSender` every minute, under an advisory lock, between
  `notify.campaign.window.start` and `.end` (08:00–20:00 East Africa Time by default), at
  `notify.campaign.batch.size` a minute and no more than `notify.campaign.daily.cap` a day across every
  campaign. Each recipient goes through the notification service: consent asked again at the moment of
  sending, the campaign's channels within the catalogue's, a log row per channel, an inbox line when
  in-app is on. A "send me a test" goes to the author alone, now.
- **The way out**: every promotional email and text carries a link with a signed token
  (`UnsubscribeService`, HMAC over the user and the purpose with the platform's secret) that lands on a
  public page, records a refusal of the purpose on every channel with source UNSUBSCRIBE_LINK, and
  signs nobody in. A forged or transactional token is refused.
- Screens: Campaigns under Platform (and for an organisation's administrator): list with state,
  audience and outcome; the editor with the audience count as you write; one campaign's page with the
  words as the checker reads them, submit, approve or send back, cancel, and test; the approvals queue
  labels it; the public unsubscribe page.
- Tests: `CampaignsIT` (3): the audience is who agreed and the refuser is not in it; the author submits
  and cannot approve; a second person approves and the audience is fixed; a buyer who changed their
  mind between approval and their turn is sent to on the channel they still allow; the window's
  arithmetic; the email carries the way out. The unsubscribe token round trip, its idempotence, a forged
  token and a transactional one refused. An organisation refused until the platform opens the door.

The plan is complete. Not walked in the browser: the session was expired throughout; every screen was
built against the endpoints the tests exercise.

