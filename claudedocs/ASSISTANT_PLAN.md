# The assistant: where it stands and how to make it better

Status: Phase 1 approved 2026-10-11, with visitors included. No language model. Decisions are at the end.

## What exists today

- **Backend** `modules/assistant` (about 835 lines). `AssistantService` stores conversations and messages,
  `AssistantProvider` is the seam, and `RulesAssistantProvider` is the only implementation.
- **No language model.** Questions are read with keyword lists and two regexes, in a fixed order:
  hand-off, affordability, "my things", glossary, search, otherwise "I did not follow".
- **Six intents.** Search listings (bedrooms, budget, town), rough borrowing from take-home pay, a
  10-term hard-coded glossary, counts of the caller's open enquiries and upcoming viewings, hand-off,
  unknown.
- **Hand-off** raises an ordinary enquiry against a listing with the transcript quoted. It needs a
  listing, so a question about nothing in particular has nobody to go to.
- **Frontend** one page, `/account/assistant`, for signed-in buyers only. It is not on listing pages,
  the public site, or the workspace.

## Weaknesses found

1. **No memory between turns.** The provider receives only the latest message. "Three beds in Kilimani"
   then "what about under 15m?" loses Kilimani and the bedrooms.
2. **Brittle keyword routing.** "agent" routes to hand-off, so "what does the agent's commission cost
   me?" offers a person. "rent" counts as a property search on a platform that sells. Misspellings,
   Swahili and Sheng are not understood.
3. **Narrow reach.** It cannot answer about the things built since: bookings and what is still owed, the
   next instalment, booking terms and refund policy, lapsed bookings, valuations, notifications, units
   left in a development.
4. **Glossary is code.** Changing an answer needs a deploy. The notification catalogue already shows the
   pattern for a platform-editable catalogue.
5. **Hand-off goes only to a seller.** There is no route to platform support or to the buyer's bank.
6. **No feedback or measurement.** Nobody can see which questions fail. `intent` is stored per message
   but nothing reports on it.
7. **No tests.** There is no integration test under `src/test` for the assistant.

## Plan

Each phase ships on its own and keeps the existing rules as the fallback.

### Phase 1: better without a model

- Carry context. Pass the conversation's recent turns to the provider and keep the last search filters
  on the conversation, so follow-ups refine instead of restarting.
- Fix routing. Word-boundary matching, a hand-off phrase list instead of single words, and drop "rent".
- New intents over existing services, each scoped to the caller's own rows:
  - my bookings, what is owed and when the next instalment is due;
  - the terms and refund policy of one of my bookings;
  - my valuation's status;
  - units left in a development, by type;
  - book a site visit on a listing;
  - "tell me when" creates a saved search alert.
- Editable knowledge. Move the glossary into a platform-managed table with an editor under settings.
- Hand-off without a listing goes to a platform support queue.
- Thumbs up or down on each answer, and a staff report of unanswered and down-voted questions.
- Integration tests for every intent and the hand-off.

### Phase 2: a language model behind the existing seam (optional)

- `LlmAssistantProvider` implements `AssistantProvider`, switched on by platform configuration, with the
  rules provider as the fallback when it is off, failing, or over budget.
- The model never invents facts or figures. It gets tools that call the same services as Phase 1,
  always as the signed-in user, and every number in a reply comes from a tool result.
- Grounding: answers cite the listings, bookings or policy they came from as links.
- Safety and data handling:
  - personal data, account numbers and IDs are not sent to the provider;
  - listing descriptions are treated as untrusted text, against prompt injection;
  - full prompts and tool calls are logged for audit, with retention set by policy;
  - a per-user daily cap and a platform monthly spend cap.
- Suggested models: Claude Haiku 4.5 for routine turns, a larger model only where needed. Cost
  depends on traffic and is not estimated here.
- Data protection review: sending buyer messages to an external provider needs Legal or Compliance
  sign-off under Kenya's Data Protection Act before it is switched on.

### Phase 3: reach

- "Ask about this home" on every listing page, with the listing as context, so hand-off needs no
  question about which listing.
- An anonymous version on the public site that answers search and glossary questions only, and offers
  to capture a lead.
- Later, a workspace assistant for staff ("which bookings lapse this week"), scoped by permissions.
  This is a separate product and is listed only for completeness.

## Decisions (2026-10-11)

1. Phase 1 only. No language model for now, so Phase 2 is parked.
2. No external provider, so no data leaves the platform.
3. Where a general hand-off goes is a platform configuration.
4. Visitors get the assistant too. Anything that needs an identity, such as booking a viewing, setting an
   alert, reading your bookings or handing off, asks them to sign in and then carries on.

## Phase 1 design

### Visitors

- Public endpoints under `/api/v1/public/assistant`: ask, read a conversation, rate an answer.
- A visitor is a random token the browser keeps. The server stores only its SHA-256 hash, on the
  conversation, so the token reads nothing else.
- Signing in claims the visitor's conversations: `POST /me/assistant/claim` moves them to the user and
  clears the visitor hash, so the conversation carries on after sign-in.
- Questions that need an identity answer with a sign-in action that returns to the chat.
- A per-visitor rate limit stops the public endpoint being used to scrape listings.

### Memory between turns

- `assistant_conversations.context` (JSONB) keeps the last search filters and the last listings shown.
- A search that sets some filters keeps the others from the previous search, unless it says "anything",
  "start again" or "new search".
- "the first one", "that one" and "number 2" refer to the listings last shown, for viewings and hand-off.

### Routing fixes

- Whole-word and whole-phrase matching instead of substrings.
- Hand-off needs a phrase ("talk to a person", "speak to someone", "customer care"), not a lone "agent".
- "rent", "to let" and "rental" set the rental filter, since the marketplace lists homes to let as well.

### New answers

| Intent | Who | What it does |
|---|---|---|
| MY_BOOKINGS | signed in | each booking, its state, what is owed and the next instalment |
| BOOKING_TERMS | signed in, general for visitors | the refund and revival terms of my booking; for a visitor, the platform policy |
| UNITS_LEFT | anyone | units left in a development, by type, with prices |
| VIEWING | signed in to finish | a viewing action card for a listing, which opens the existing request form in the chat |
| ALERT | signed in to finish | an alert action card built from the search filters, created with one click |

Valuations were dropped from the list: buyers do not request them, so a buyer has none to ask about.

### Editable knowledge

- `assistant_knowledge`: a term, its alternative wordings, the answer, an optional link, active or not.
  It is seeded with the ten glossary entries.
- Managed under Platform settings, Assistant tab, with permission ASSISTANT_MANAGE.

### General hand-off

- Configuration `assistant.handoff.general`: PLATFORM, ORGANISATION or OFF.
  - PLATFORM raises an enquiry with no listing and no organisation. Platform staff see it in their
    enquiries.
  - ORGANISATION raises it to the organisation named in `assistant.handoff.organisation`.
  - OFF says hand-off is only for questions about a listing.
- `enquiry_tickets.property_id`, `property_reference` and `tenant_id` become nullable for this. The
  enquiry pages show "General question" where a listing would be.
- A hand-off about a listing still goes to that listing's seller, as before.

### Feedback and insight

- Each assistant answer can be rated up or down, by its own user or visitor.
- An insights view (ASSISTANT_VIEW) lists recent unanswered and down-rated questions with counts by
  intent, so the knowledge can be improved where it fails.

### Tests

`AssistantIT` covers every intent, follow-up memory, the visitor flow and claim, feedback, knowledge
editing, both hand-off routes and the insights view.

## Progress (2026-10-11)

- [x] Backend: migration `V20261011090000`, provider, service, three controllers, settings, permissions.
  `AssistantIT` has 11 tests; the full suite has 575, all passing.
- [x] Frontend: the "Ask Hodi" panel on every marketplace page, the account page rebuilt on the same chat,
  action cards for sign-in, viewing, alert and hand-off, and answer ratings.
- [x] Platform settings: an Assistant tab with hand-off routing, visitor limits, the knowledge editor and
  insights.
- [x] Browser walkthrough as a visitor: search, follow-up memory, viewing card, units left, sign-in card,
  hand-off card.
- [ ] Browser walkthrough signed in: claiming on sign-in, a viewing and an alert sent from the chat, a
  general hand-off, and the settings tab. Needs a sign-in.

### Noticed

- Highrise Apartments says 14 of 34 homes available, while its four unit types add up to 73 left. The
  assistant reads both from the public development service, so the development page has the same mismatch.
  The development's stored counts look stale.
