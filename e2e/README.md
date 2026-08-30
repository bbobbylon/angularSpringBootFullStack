# End-to-end suite

Playwright tests that drive a real browser against the **real deployed artifact** — the
`docker compose` stack, with the Angular bundle baked into the Spring Boot jar exactly as it ships.

Closes the "No end-to-end coverage" item in
[../documentation/FUTURE-ENHANCEMENTS.md](../documentation/FUTURE-ENHANCEMENTS.md) §5.

---

## Why this exists

The repository already has two large test suites, and both deliberately mock the boundary this one
refuses to mock:

| Suite | Count | What it mocks |
|---|---|---|
| Backend JUnit | ~584 | No browser; `HttpClient` never involved |
| Frontend Vitest | ~174 | `HttpClient` stubbed; no backend |
| **This suite** | **16** | **Nothing — real browser, real filter chain, real database** |

A seam break is a bug where *both sides are individually correct and they disagree with each other*:
an interceptor that sends `bearer` where the filter strips `Bearer `, a response envelope the SPA
parses differently than the controller writes it, a public-route list that drifts out of lockstep.
Neither existing suite can see those. This one can.

Building it immediately surfaced several such issues — see **Traps** below. None was visible from
either half alone.

**Status: green.** `16 passed (3.0m)` as of 2026-08-30. Getting there took seven runs, and the
failures along the way were almost entirely harness and environment faults rather than application
bugs — three of them (traps 6, 8 and 9) produced failures that pointed convincingly at correct
production code. The one genuine defect found was the mail health indicator in trap 7. Read the
traps before debugging a red run; the odds are good it is one of them again.

---

## Running it

```bash
cp .env.e2e.example .env.e2e     # once; Copy-Item on PowerShell
npm install                      # once (repo root, not tesseraapp/)
npm run e2e:install              # once — downloads the Chromium build Playwright drives

npm run e2e                      # brings the stack up, runs the suite
```

Requires a running **Docker daemon**. `npm run e2e` starts the compose stack itself via Playwright's
`webServer` and waits on `/actuator/health`; the first run builds both Docker stages and takes
several minutes.

| Script | What it does |
|---|---|
| `npm run e2e` | Full run (starts the stack if it isn't up) |
| `npm run e2e:ui` | Interactive UI mode — best for writing new specs |
| `npm run e2e:headed` | Watch it drive a visible browser |
| `npm run e2e:report` | Open the HTML report from the last run |
| `npm run e2e:up` / `:down` | Manage the stack by hand (`:down` also removes volumes) |
| `npm run e2e:logs` | Tail the app container |
| `npm run typecheck` | Typecheck the specs without running them |

**A failing run can leave the database dirty.** `npm run e2e:down` removes the containers *and*
volumes for a genuinely clean slate — reach for it first when a previously-passing suite starts
failing oddly.

---

## Traps this suite had to work around

Each of these is real application behaviour, correct on its own terms, that makes the naive version
of an E2E test impossible. They are documented here because every one of them cost real
investigation, and the next person should not have to repeat it.

### 1. The default compose stack has no login accounts

`DemoDataSeeder` is `@Profile("dev")`, but `docker-compose.yml` defaults to
`SPRING_ACTIVE_PROFILES=prod`. On the default stack the seeder bean is never instantiated, so there
is nothing to sign in as. `docker-compose.e2e.yml` forces `dev`.

### 2. Risk-based step-up makes login unsatisfiable (the big one)

With `ANOMALY_DETECTION_ENABLED` at its default `true`, an E2E login can never complete:

1. `DemoDataSeeder` writes `LOGIN_ATTEMPT_SUCCESS` rows for each seeded account with a fixed device
   (`"Chrome on macOS"`) and IP (`10.0.1.x`). So no seeded account is a "no history" account, and
   `LoginRiskServiceImpl`'s never-flag-the-first-login escape hatch does not apply.
2. A Playwright browser matches neither the stored device nor the stored network prefix, so
   **both** `NEW_DEVICE` and `NEW_NETWORK` fire and the sign-in escalates to email step-up.
3. Step-up mails the one-time code to a `@tessera.dev` address — the exact domain
   `EmailServiceImpl#isSuppressedRecipient` silently drops.

Net effect: the login sits on the MFA screen forever, with no error and no timeout. The suite sets
`ANOMALY_DETECTION_ENABLED=false`, which `application.yml`'s own comment on the flag anticipates
("useful when demoing the app from a fresh network where every sign-in would legitimately look
new"). Only the risk-based escalation is disabled — enrolled 2FA, password hashing, JWT validation
and every authorization rule still run, so the suite still exercises the real security path.

### 3. Signing in is rationed

`RateLimitFilter` puts `/user/login` in its auth tier at **10 requests per minute per client IP**,
and every request in the suite comes from the same IP. A suite that logged in inside each test would
begin throttling *itself* around the tenth test and fail with 429s that look nothing like the bug
they'd be blamed on.

So `auth.setup.ts` signs in **once** and caches the browser state; every other spec inherits it for
free. Specs that are *about* signing in opt out with
`test.use({ storageState: { cookies: [], origins: [] } })` and each spend one request from the
budget. Current cost is ~7 login requests per run, inside the limit with headroom.

### 4. A fresh browser lands on `/welcome-passkey`, not `/`

`shouldPromptForPasskey` returns true when the browser supports WebAuthn, the account has no
passkey, and the dismissal flag is unset. Headless Chromium *does* expose `PublicKeyCredential` and
every seeded account is password-only, so a fresh context hits all three — meaning the obvious
assertion "after login the URL is `/`" fails on entirely correct behaviour.

`suppressPasskeyPrompt()` sets the same flag the real "Maybe later" button sets. The interstitial
itself is covered explicitly, on an un-suppressed context, in `auth.spec.ts`.

### 5. `env_file` merges by appending, not replacing

`docker-compose.e2e.yml` uses `env_file: !override`. Without the tag, Compose appends to the base
file's list and yields `[.env, .env.e2e]` — loading the developer's **real** OAuth client secrets and
Aiven database password into the throwaway stack, and breaking it outright (`.env`'s
`SPRING_DATASOURCE_PASSWORD` wins over the E2E MySQL credentials, so the app can't authenticate to
its own container). Verified with `docker compose config`. **Do not drop that tag.**

### 6. The compose project name is load-bearing

`docker-compose.e2e.yml` sets `name: tessera-e2e`. Without it the stack inherits the base project
name and therefore **shares the developer's `mysql-data` and `app-images` volumes**. Two
consequences, both observed:

1. It doesn't work — `MYSQL_ROOT_PASSWORD` is honoured only when MySQL initialises an *empty* data
   directory, so on an existing volume the old password stands and the app dies with
   `Access denied for user 'root'`.
2. Far worse, had it connected, the suite would have been reading and writing the **real development
   database** — seeding it, revoking its sessions, leaving test rows behind.

The dedicated name is also what makes `npm run e2e`'s `down -v` safe: it can only ever destroy E2E
data.

### 7. A mail health indicator can hold the whole stack DOWN

`spring-boot-starter-mail` plus a configured `spring.mail.host` makes Spring Boot auto-register a
`MailHealthIndicator` that opens a real SMTP connection on **every** `/actuator/health` probe. The
E2E stack points `MAIL_HOST` at nothing, so health reported DOWN forever — while the app itself was
completely fine and served `/` with a 200. Docker's healthcheck and Playwright's `webServer` gate
both read that endpoint, so the suite never started.

Fixed in `application.yml` with `management.health.mail.enabled: false`, and that is the right
setting in production too: `/actuator/health` is what the ALB target group and the ECS container
check poll, so a Gmail hiccup would have drained the targets and had ECS replace a task that could
still serve every request except sending an email.

### 8. `reuseExistingServer` silently tests a stale image

Playwright's default is `!process.env.CI` — reuse locally, rebuild in CI. That is right for a dev
server that picks up source changes, and **wrong here**, because the compose command builds an image
with the compiled jar and the built Angular bundle baked in. A leftover container is a frozen
snapshot of older source.

With reuse on, Playwright just saw port 8090 answering and skipped the build entirely. The result
was a run that "failed" on rate limits already raised — reporting red against code that was never
deployed, with nothing in the output hinting the image was stale. The config now pins
`reuseExistingServer: false`, and `pree2e` tears the stack down first so a leftover stack is not a
hard error.

### 9. `browser.newContext()` inherits `storageState` — so it is *not* a fresh session

This one cost the most, and it is worth reading before writing any multi-session test.

`browser.newContext()` inherits the project's `use` options. Because the `chromium` project sets
`storageState` to the signed-in state `auth.setup.ts` cached, the default for a manually created
context is not "a clean browser" — it is **the same session again**.

The session-revocation test opens a second context precisely so there are two independent sessions
to revoke between. Silently getting one session back defeated it completely:

- Context B came up already authenticated, so the SPA's guard bounced it off `/login` to the
  dashboard, `#email` never rendered, and the `fill` retried until the test timed out.
- Every symptom pointed at the application. The trace showed all actions completing in milliseconds
  and then one hanging forever with no error; the failure screenshot showed a perfectly healthy
  dashboard.
- And the hang was the *lucky* outcome. Had it got further, A and B would have shared one token and
  one session family, so "revoke every family except mine" would have spared A's token — and the
  test would have failed reporting that **access-token revocation was broken**, pointing squarely at
  correct production code.

Pass `storageState: { cookies: [], origins: [] }` — the same explicit-empty idiom `auth.spec.ts`
uses. Clearing it is not a tidy-up; it is the difference between two sessions and one.

### 10. Animations can make `click()` hang rather than fail

Not what broke the revocation test — that was #9 — but real, and worth knowing because the failure
mode is identical and indistinguishable in a trace.

Playwright will not dispatch a click until the element is *stable*: same bounding box across two
consecutive animation frames. An element still animating never satisfies that, and `click()` does
not fail fast — it retries silently until the **test** timeout fires, so the trace shows a click
that simply never returns and no error explaining why.

`use.reducedMotion: 'reduce'` removes the whole class of problem, and legitimately: the app honours
the query in `styles.css`, `command-palette.component.css` and `app.component.ts`, so this switches
on the app's own reduced-motion path rather than fighting the animations from outside. Worth keeping
regardless of what it did or did not fix, especially now that route transitions and button-press
feedback are animated.

---

## Layout

```
e2e/
├── auth.setup.ts       Signs in once; caches storage state for every other spec
├── auth.spec.ts        Sign-in path (runs unauthenticated)
├── seam.spec.ts        Interceptor ↔ filter chain; token revocation
├── headers.spec.ts     CSP / security headers; cache revalidation + ETags
├── rate-limit.spec.ts  The 429 tier itself — runs LAST, in its own project
├── support/app.ts      Seeded accounts, storage keys, navigation helpers
└── .auth/              Cached session state (gitignored, regenerated per run)
```

`rate-limit.spec.ts` gets its own Playwright project depending on `chromium`, so it always runs
last. It is the only spec that deliberately exhausts a resource every other test shares — the
limiter buckets per client IP, and inside Docker the whole host is one IP. Left in the main project
it ran between `auth.spec.ts` and `seam.spec.ts` and broke seam: the second session couldn't sign
in, and because the login form maps every non-200 to the same generic message (the very
no-enumeration property `auth.spec.ts` asserts) a 429 was indistinguishable from a wrong password.
Naming the file to sort last would have worked and been invisible; a project dependency states the
constraint outright.

### Conventions

- **Mirror values are annotated with their source of truth.** `support/app.ts` duplicates the demo
  password, the demo email domain and the `Key` enum's literal string values. A drifted mirror is
  worse than no mirror, so each one names the file it must track.
- **Sign in as the least-privileged account that can exercise the behaviour.** A test that signs in
  as the application admin proves nothing about whether the authority rules work.
- **Assert properties, not copy.** The user-enumeration test asserts the two failure messages equal
  *each other*, never a hardcoded string — so it survives rewording and translation while still
  pinning the security property.

---

## Known gaps

- **No federated-login round-trip.** The OAuth2 redirect chain hands the window to Google/GitHub/
  Microsoft, which needs real credentials and a real consent screen. The E2E environment
  deliberately configures no OAuth clients, so the provider buttons don't render. Covering this
  needs either a mock OIDC provider (e.g. a Dex container) in the compose stack, or recorded
  fixtures.
- **No SAML round-trip**, for the same reason — Stage 3 shipped with code and unit tests but has
  never been exercised against a live IdP.
- **No email-dependent flows** (password reset, step-up codes, verification links). Every seeded
  account is `@tessera.dev`, which `EmailServiceImpl` suppresses before dispatch. Testing these
  needs a mail catcher (e.g. MailHog) added to the compose stack and `MAIL_HOST` pointed at it.
