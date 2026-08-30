# Flow diagrams

Sequence diagrams for TesseraApp's authentication, authorization and session flows.

**Open [`index.html`](index.html) in a browser** for the full gallery — all nine diagrams, a light/dark
toggle, and download links for every format.

---

## What's here

| Diagram | Covers |
|---|---|
| `01-password-reset` | Forgot-password → emailed link → new password, including the no-enumeration property |
| `02-login-step-up` | Password sign-in and its three endings: tokens, enrolled 2FA, or anomaly-driven step-up |
| `03-token-rotation-reuse` | Refresh rotation, and what happens when a spent token is replayed |
| `04-request-authorization` | The full request path, and the three checks inside `isTokenValid` |
| `05-federated-login` | Google / GitHub / Microsoft OAuth2, auto-join, and minting our own JWTs |
| `06-org-sso` | Per-organization enterprise SSO — email-domain discovery, then OIDC or SAML |
| `07-passkey-webauthn` | WebAuthn enrolment and passwordless sign-in |
| `08-registration` | Sign-up → disabled account → email verification → enabled |
| `09-session-revocation` | Listing sessions, the four revoke paths, and immediate access-token invalidation |

Every diagram exists in four forms, plus one combined document:

```
svg/<name>-dark.svg    svg/<name>-light.svg    png/<name>.png    pdf/<name>.pdf

pdf/tesseraapp-flow-diagrams.pdf     ← all nine, cover page + one per page
```

Which one to reach for:

| Want | Use |
|---|---|
| Hand the whole set to someone | `pdf/tesseraapp-flow-diagrams.pdf` |
| Read one flow at full size, or zoom in | `pdf/<name>.pdf` — vector, so text stays sharp and selectable |
| Drop into a slide, a chat, a GitHub comment | `png/<name>.png` |
| Embed in a light-background document or print | `svg/<name>-light.svg` |
| Browse them all with a theme toggle | `index.html` |

The PDFs come in two shapes on purpose. A PDF has one page size for the whole document, so the
combined deck has to scale each diagram to fit a uniform A4 landscape page — that uniformity is what
makes it feel like a document rather than a pile of exports. The per-diagram PDFs skip that: each
page is sized to its own diagram, so nothing is scaled down and a dense sequence stays readable at
100%.

Both are dark, matching the PNG and the app itself. The light SVG is the one to use when a diagram
has to sit on white.

---

## Editing

The `.mmd` files in [`src/`](src/) are the **source of truth**. Everything in `svg/`, `png/` and
`index.html` is generated — edit a source, then regenerate:

```bash
npm run diagrams        # from the repository root
```

Rendering uses the Chromium that Playwright already installed for the E2E suite, driving Mermaid's
UMD bundle from `node_modules`. No extra browser download, and no `mermaid-cli` dependency. If
`npm run diagrams` complains it can't find Chromium, run `npm run e2e:install` once.

### Mermaid gotchas that will bite you

- **No semicolons in message or note text.** Mermaid treats `;` as a statement separator, so
  `note over A, B: tokens live 30 minutes; refresh 5 days` is a parse error. Use a comma or a dash.
  This also rules out HTML entities — `&lt;` and `&gt;` contain semicolons, so
  `ResponseEntity&lt;HttpResponse&gt;` fails to parse. Write it in words.
- **`<br/>` is the only line break** that works inside labels.
- **`rect rgba(...)` blocks are the phase bands.** They nest with `alt`/`opt`, but an unclosed
  `rect` swallows the rest of the diagram with a confusing error far from the real line.

---

## Why the diagrams look the way they do

Three deliberate choices, each fixing a problem the previous single `auth-password-reset.svg` had:

**They carry their own opaque background.** That earlier export was `background-color: transparent`
with default dark text, so it rendered as dark-on-dark — effectively unreadable — in any dark editor,
viewer or GitHub theme. Each diagram here paints a background rect as its first child and declares
concrete pixel dimensions, so it looks deliberate wherever it's dropped: a PR, a slide, a PDF, an
IDE preview.

**They're generated from tracked sources.** The old file had no `.mmd` behind it, which made it
un-editable and un-regenerable — and left it saying "SecureCapita" long after the rebrand.

**They carry the reasoning, not just the call sequence.** The side notes are the point. A diagram
showing that login calls `LoginRiskService` is mildly useful; one explaining *why* the risk check
compares an account only against its own history — so a risk verdict can't become an enumeration
oracle — is the thing worth keeping. Where a design was chosen over an alternative, the note says
which alternative and why.

Colours mirror the app's own design tokens (`tesseraapp/src/styles.css`): indigo `#6b5bff` for
structure, and green / amber / red / cyan for outcome bands, so a diagram sits next to a screenshot
without looking foreign.

---

## Superseded

The repository root still contains `auth-password-reset.svg`, the original standalone export.
`01-password-reset` replaces it with a corrected, regenerable, readable version. That file is safe
to delete whenever you're happy with the replacement — it's left in place rather than removed
unilaterally.
