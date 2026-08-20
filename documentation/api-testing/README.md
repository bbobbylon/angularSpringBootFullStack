# API testing suite

Three interchangeable ways to exercise TesseraApp's API surface, all covering the same ~40
requests across all 12 controllers, kept in sync with each other and with
[`../GUIDE.md` §8](../GUIDE.md#8-api-reference):

| Tool | File(s) | Best for |
|---|---|---|
| **cURL script** | `curl-smoke-test.sh` | zero install, CI-friendly, colored pass/fail output |
| **Postman** | `tesseraapp.postman_collection.json` + `tesseraapp.postman_environment.json` | GUI exploration, saved response history |
| **Bruno** | `bruno/` | GUI exploration, collection lives as plain text files (git-diffable, no cloud account) |

All three assume the app is already running locally (`./start.sh`) at `http://localhost:8080`
and that the demo seed data exists — `DemoDataSeeder` runs on every boot in non-prod profiles.

## Coverage boundary (verified 2026-08-19)

"All 12 controllers" means every controller is represented, **not** that every endpoint is. These
routes exist in `GUIDE.md` §8 but are deliberately **not** exercised by any of the three collections
yet — a smoke test is not an exhaustive suite, and several of these are destructive or single-use:

| Not covered | Why it was left out |
|---|---|
| `POST /user/verify/resend` | Needs an account with a genuinely *pending* code; against seeded demo users it correctly no-ops, so a green result would prove nothing |
| `POST /user/totp/recovery-codes/regenerate` | Requires a live TOTP or recovery code, which a script cannot produce without the shared secret |
| `DELETE /admin/user/{id}/totp` | Destructive on a seeded account, and self-targeting is refused — it needs a throwaway second user |
| `POST /user/sessions/providers/link/{provider}` | Mints a ticket whose only use is a browser redirect the script cannot follow |
| `GET` / `PATCH /admin/security/anomaly-settings` | The `PATCH` mutates platform-wide login behaviour; safe to add read-only, but the pair belongs together |
| `POST /admin/analytics/report/email` | Sends a real email, like `POST /contact/send` — would need its own opt-in flag |
| `GET /customer/invoice/search` | Straightforward to add; simply not written yet |
| `GET /customer/invoice/{id}/download/pdf` · `POST /customer/invoice/{id}/email` | The PDF needs a non-draft invoice with a customer attached; the email sends for real |

Everything else in §8 is covered. If you add a request, add it to **all three** collections in the
same change — the parity between them is the only reason having three is not a liability.

## Demo credentials

Every seeded account shares the password **`TesseraDemo@1`**:

| Email | Role |
|---|---|
| `eve.admin@tessera.dev` | `ROLE_ADMIN` — used as the default caller in all three artifacts |
| `dave.org@tessera.dev` | `ROLE_ORGANIZATION_ADMIN` |
| `carol.help@tessera.dev` | `ROLE_HELP_DESK_ADMIN` |
| `bob.mod@tessera.dev` | `ROLE_MODERATOR` |
| `frank.app@tessera.dev` | `ROLE_APPLICATION_ADMIN` |
| `alice.guest@tessera.dev` | `ROLE_GUEST` |

## What's *not* covered, and why

**WebAuthn/passkey enrollment and login are intentionally excluded from all three artifacts.**
Those endpoints (`/user/webauthn/**`, `/user/verify/webauthn*`) exchange a
`PublicKeyCredential.toJSON()` blob that only a real browser's platform authenticator can
produce — there is no way to script a valid credential from curl, Postman, or Bruno. To exercise
that flow, use the app's own UI (Security Center → Passkeys) in a browser.

**`POST /user/totp/enable`** needs a live 6-digit TOTP code generated from the secret returned by
`POST /user/totp/setup` — the request is present in all three artifacts as a template with a
`REPLACE_WITH_6_DIGIT_CODE` placeholder, not something that runs unattended.

## Mutating requests leave permanent data

There is no `DELETE` endpoint for customers or invoices (by design — see `GUIDE.md` §8.9), and
services are *retired*, never deleted (§8.8). That means:

- The cURL script defaults to **read-only** plus one harmless throwaway `POST /user/register`.
  Pass `--with-mutations` to also create a customer + invoice (**permanent**, no cleanup possible)
  and a service (auto-retired at the end of the run, so at least it won't show up as active).
- `POST /contact/send` sends a **real** notification through `NotificationService` — the cURL script
  gates it behind a *second*, separate flag (`--with-contact-email`) so it's never fired by
  accident. Postman's and Bruno's contact requests are present but you have to click Send yourself.
- Postman and Bruno don't have a "mutations off" switch — they're GUI tools, so the assumption is
  you're deliberately clicking each request. Point `serviceId`/`customerId`/`invoiceId` at
  throwaway values if you don't want to touch real-looking data.

If you're running any of this against a shared database (not your own local MySQL), point
`baseUrl`/`BASE_URL` at a throwaway environment first.

## Running the cURL script

```bash
./curl-smoke-test.sh                                   # safe: reads only
./curl-smoke-test.sh --with-mutations                   # also creates a customer/invoice/service
./curl-smoke-test.sh --with-mutations --with-contact-email
BASE_URL=http://localhost:9090 ./curl-smoke-test.sh      # point at a different port
./curl-smoke-test.sh --admin-email dave.org@tessera.dev --admin-password TesseraDemo@1
```

No `jq` dependency — field extraction is a small `sed` helper (`jget`), good enough for the flat
fields this script needs (`access_token`, `refresh_token`, `id`). Exits non-zero if any check
fails, so it's usable as a CI smoke gate.

It also doubles as a live check of the backend HTTP caching work
([`POST-SUBMISSION-UPGRADES.md`](../POST-SUBMISSION-UPGRADES.md) item #3): it captures an `ETag`
from `GET /customer/list`, replays the request with `If-None-Match` and asserts a `304`, and
checks `POST /user/sessions/logout` sends `Clear-Site-Data: "cache"`.

## Running the Postman collection

Import both `tesseraapp.postman_collection.json` and `tesseraapp.postman_environment.json`,
select the **TesseraApp - Local** environment, then run **Account → Login (No MFA)** first — its
test script captures `access_token`/`refresh_token`/the admin's `id` into collection variables
that every later request reuses. From there, requests can be run individually or via the
Collection Runner (skip the mutating ones on repeat runs, per the section above).

## Running the Bruno collection

Open Bruno → **Open Collection** → point it at `bruno/`. Select the **Local** environment (top
right), then run the numbered folders roughly in order — `01-account`'s "Login (No MFA)" first,
same reason as Postman. Folder numbers (`01-…` through `12-…`) are just filesystem ordering, not
a Bruno feature — they keep the sidebar in the same order as this README and the Postman
collection.

## Keeping these in sync

If you add or change an endpoint, update all three: the cURL script (one `call`/`call_headers`
line), the Postman collection (one JSON item), and the matching `.bru` file — plus
[`GUIDE.md` §8](../GUIDE.md#8-api-reference), which is the source of truth all three were built
from.
