# Themed email templates

**Scope:** one shared HTML shell and four content templates for every email `hodimp-b` sends, with the palette
resolved from the platform's theme configuration at render time.
**Touches:** `infra/notify/` (new), and the four senders that currently build their own HTML.
**Related:** §27 of `QUANTUMNEX_IMPLEMENTATION_PLAN.md` (M14). This is the presentation layer; the outbound
row and the dispatcher are still that section's business.

## 1. Where the emails stand today

Five emails, four hand-rolled bodies, no shell:

| Sender | Body today |
|---|---|
| `OtpChallengeService.issueEmailVerification` | one sentence with the code in `<strong>` |
| `PasswordResetService` | three bare `<p>`s, the reset link as an inline `<a>` |
| `LeadNotifier.body()` | a `<div>` with a hand-written palette |
| `SearchAlertRunner.emailBody()` | the same palette, plus listing rows |

The two that carry a palette carry **`#12211c`, `#e2e8e5`, `#5c6b66`** — none of which is a brand colour.
`theme.primary` is `#0B2545` and `theme.accent` is `#1B7F79`, so every styled email the platform has sent has
been a slightly different product from the one it links to. The two auth emails have no styling at all: the
messages that matter most, sent when somebody is locked out and anxious about it, are the least like us.

That is the real reason to do this, and it is worth stating before the colour question: the templates are the
deliverable, and dynamic colour is what stops them from drifting from the app again.

## 2. What "dynamic colours" can mean in an email

**Not CSS custom properties.** Outlook on Windows renders through the Word engine, which has no `var()` and
no `:root`; Gmail strips much of what survives that. A shell built on tokens would work in the preview and
fail in the clients most recipients read mail in.

So the colour is dynamic at the **server**, not in the message: `MailPalette` reads the theme keys per render
and the values are interpolated into inline `style` attributes. The email that arrives is a fixed document;
what makes it dynamic is that an operator changing `theme.accent` in configuration changes the next email
without a deploy — the same property the workspace already has.

The keys are the ones `ThemeService` serves the frontend, so the two cannot disagree:

| Key | Use in the email |
|---|---|
| `theme.primary` | header band |
| `theme.accent` | buttons, links, the code pill |
| `theme.accent.light` | header gradient terminus |
| `theme.ink` | body text |
| `theme.logo.url` | header lockup, when set — otherwise the platform name as text |
| `theme.dark.enabled` | whether the dark-mode block is emitted at all (§5) |
| `company.name`, `company.email`, `company.phone`, `company.address` | footer |
| `platform.public.url` | every link |

**It reads those keys directly rather than calling `ThemeService.theme()`**, for two reasons. That map is
shaped for the client and carries `mapsApiKey`; an email template is the last place to hold a structure with
a credential in it, one careless loop away from rendering it. And the email needs values the client does not:
a validated hex, and a text colour computed against the accent (§4).

`THEME_*` keys are currently platform-only — "one marketplace, one brand", per the plan's §7 note. Emails
therefore carry the platform brand even when a seller triggered them, which is correct today. When those keys
flip to overridable alongside vanity seller hosts, the emails follow with no change here, because
`ConfigurationService` already resolves *tenant override ?? global* underneath this.

## 3. Structure

```
MailPalette   record. Validated colours, derived text colours, company details, base URL.
MailTemplate  @Component. shell(...) plus block builders, and two convenience wrappers.
```

The shell is a 600px table — `<table>`, not flexbox, because Outlook supports one of the two — with a header
band, a white card, and a footer. Block builders (`paragraph`, `heading`, `codePill`, `button`, `card`,
`divider`, `muted`) let a sender compose without `MailTemplate` needing to know what a property is;
`SearchAlertRunner` keeps its listing rows and stops owning the frame around them.

Four templates over that shell:

1. **`code`** — a verification code, shown as a letter-spaced pill. Email verification.
2. **`action`** — a sentence, a button, and an expiry note. Password reset.
3. **`notice`** — a line and a link. Lead and enquiry notifications.
4. **`digest`** — an intro, caller-supplied cards, a link to see the rest. Search alerts.

Every template ends with a footer note saying why the recipient got it. Transactional mail says it cannot be
switched off, which is already what `LeadNotifier` tells people; the digest points at the alert's own settings.

## 4. Two things that have to be right

**Escaping, and the style attribute in particular.** Names, saved-search names and listing titles are other
people's input, and a mail client renders whatever arrives. Every interpolated value is escaped, as
`LeadNotifier` already does. Colours get more than escaping: a configured value goes into a `style` attribute,
where `#000" onclick="` would break out of it, so `MailPalette` accepts `#rgb` and `#rrggbb` only and falls
back to the built-in default for anything else. Configuration is written by platform staff rather than the
public, so this is defence in depth — but the whole point of the feature is that these values are editable at
runtime, and the reviewer should not have to reason about who can reach the form.

**Contrast, because the accent is editable.** White on `#1B7F79` is fine; white on a pale accent somebody
sets next year is not, and §25 spent a phase on exactly this. `MailPalette` computes the relative luminance
of the accent and picks white or near-black text for anything sitting on it — the server-side twin of the
frontend's `--brand-contrast`.

## 5. Dark mode

Inline styles are the light rendering and always present. When `theme.dark.enabled` is true, the shell also
emits a `@media (prefers-color-scheme: dark)` block that repaints the page background, card, text and border
— the neutral surfaces only. The brand colours stay as configured, because an accent that shifts by client is
no longer the brand.

The overrides need `!important` to beat the inline styles, and clients that ignore `<style>` simply keep the
light version, which is the reason the inline styles remain the baseline rather than the fallback. Tying this
to `theme.dark.enabled` means the platform's existing "offer light/dark" switch governs the emails too, which
is what an operator would expect it to mean.

## 6. Migration and verification

Four senders move to the templates; no signatures change and no behaviour outside the body changes. In
particular `sendSensitiveEmail` stays exactly where it is for the code and the reset link.

Verified by: a unit test over the palette (hex validation, injection attempt, contrast choice) and the shell
(escaping, no unresolved placeholders); and by rendering all four templates to files and opening them in a
browser at desktop and mobile widths — the same "open it rather than read it" that found the two defects in
§26. A mail-client matrix (Outlook, Gmail, Apple Mail) is not something this repository can test, and the
structural choices above are what stand in for it.

## 7. Deliberately not in this change

- **Database-backed templates.** Axis stores copy in `notification_templates` with `{{variable}}`
  interpolation, and §27.4 records that as the model to copy when M14 proceeds. Doing it now would mean a
  table, an admin screen and a migration for four bodies whose text is not in question. The shell is code
  either way; when the copy moves, these templates become its defaults.
- **Localisation.** Axis's templates carry a `locale`. Nothing in Hodi is translated yet, and one language
  with a locale column is a column nobody sets.
- **A preview endpoint.** Useful, and it belongs with the admin screen that would edit the copy, not ahead
  of it.

---

## 8. As built

`MailPalette` and `MailTemplate` in `infra/notify/`, and the four senders moved onto them. `LeadNotifier` and
`SearchAlertRunner` each lost their private `escape` — the template escapes everything it is handed, and two
copies of that method were two places for one of them to be forgotten.

`MailTemplate.More` was not in §3. The digest's "and 3 more" line was plain text in the first version, which
quietly dropped the *"See the full search"* link the old hand-rolled body had: a message that says somebody is
missing three listings and gives them no way to see them is worse than one that says nothing. It is a record
rather than two more string parameters because the note is escaped, the href is not that kind of value, and
neither means anything without the other.

### What the preview caught that the markup did not

Rendering the templates and opening them found one real fault, and it was not in the code that was written for
contrast — it was in the code that assumed it did not need any.

`link()` coloured every anchor `theme.accent`. On the configured teal that is fine. Rendered with a
deliberately pale accent to check the button's text switch, the button behaved exactly as designed — dark label
on a light fill — and the **footer's contact address turned into pale-on-white and vanished**. The accent works
as a fill long before it works as text, which is the whole reason the frontend keeps `--brand` and
`--brand-text` as separate tokens; the email had collapsed them back into one.

`MailPalette.accentText` now walks the accent towards the far end of the range until it clears 4.5:1 against
the surface it will be read on, and returns it untouched when it already does — the configured `#1B7F79` comes
back as `#1B7F79`, so nothing about the brand moved. The dark block computes its own link colour against the
dark card rather than reusing the light one, because a teal darkened until it could be read on white is the
wrong direction on `#0e2137`.

The lesson is the §26 one again: the fault was in the assumption nobody had written down, and it was invisible
in the markup. Only a colour nobody would choose exposed it.

### Verified

- `MailTemplateTest`, 8 cases: hex validation and a `style`-attribute breakout attempt, the contrast choice
  against four accents, a `<script>` in a listing title, all four templates as whole documents, the dark block
  following `theme.dark.enabled`, and a missing first name reading as "Hello there,".
- All four rendered and opened at 820px. The pale-accent render was a throwaway and is not in the repository;
  what it proved is held by the two contrast tests.
- **Not verified:** how any of this renders in Outlook, Gmail or Apple Mail. Nothing in this repository can
  test that, and §2's structural choices are what stand in for it.
