# TesseraApp

A full-stack **zero-trust CIAM platform** built on **Angular 21** and **Spring Boot 4**: JWT
authentication with rotating refresh sessions and replay detection, authenticator-app MFA, passkeys
(WebAuthn), federated sign-in, login-anomaly detection with step-up verification, permission-based
RBAC with organization scoping, an administrative security dashboard, and a customer / invoicing /
services domain on top — in six languages.

---

![TesseraApp architecture](documentation/architectLayout.png)

*Angular client · Spring Boot server · MySQL, containerized with Docker. Full breakdown in
[the Guide](documentation/GUIDE.md#1-architecture).*

## What it does

**Identity & access**
- Registration with email verification, password reset, and per-account brute-force lockout
- **Password complexity** (8+ chars, mixed case, digit) enforced identically on all three doors a
  password can enter through — register, change, reset — and **phone-number shape validation** for
  SMS 2FA opt-in, both backend-enforced with a matching frontend mirror for UX
- **Rotating refresh sessions** — every refresh mints a new token and retires the old one; replaying
  a retired token revokes the whole session family (theft detection)
- **Authenticator-app MFA (TOTP)** with single-use recovery codes, bound to a server-side challenge
  so a code alone can never skip the password step
- **Passkeys (WebAuthn)** — usernameless sign-in via `navigator.credentials`, phishing-resistant by
  construction; admins can view and revoke a user's registered devices (never "reset" one — the
  private key never leaves the authenticator)
- **Federated sign-in** via Google / GitHub / Microsoft, converging on one local identity; connected
  accounts are manageable from the Security Center
- **Login-anomaly detection** — an unrecognised device or network escalates to step-up verification
  instead of being waved through
- **Permission-based RBAC** (`READ:USER`, `UPDATE:CUSTOMER`, …) enforced at both the URL and method
  level, with **organization scoping** so a tenant admin sees only their own users

**Administration**
- User directory with role reassignment and account-state control
- **Security dashboard** — anomalous sign-ins, authentication trends, restricted accounts, MFA
  adoption, live sessions
- **Roles × permissions matrix**, services-catalog CRUD, billing and analytics hubs

**Business domain**
- Customers and invoices (create, edit, export to XLSX), a services catalog, dashboard analytics

**Experience**
- Six languages with instant switching, dark/light theming, a ⌘/Ctrl+K command palette, and
  capability-level UI gating so controls you cannot use are disabled with an explanation rather than
  failing on submit

---

## Tech stack

| Layer | Technology |
|---|---|
| Frontend | Angular 21 (standalone, zoneless, signals), Bootstrap 5, ngx-toastr, Transloco |
| Backend | Spring Boot 4, Spring Security 7, Hibernate 7 |
| Database | MySQL 8.4 |
| Auth | JWT access + rotating refresh sessions (replay detection), authenticator-app TOTP, passkeys (webauthn4j), OAuth2/OIDC federation, SMS 2FA (Twilio) |
| i18n | Transloco — runtime switching, 6 locales (en · es · fr · de · pt · zh) |
| Runtime | Java 21, Node 22 |
| Container | Docker (multi-stage build) |

---

## Documentation

Four documents cover the project, plus two deep-reference sets.

| Document | What it covers |
|---|---|
| **[GUIDE.md](documentation/GUIDE.md)** | Everything operational: architecture, setup, configuration, the development loop, backend and frontend internals, the security model, the full API reference, the database, testing, and deployment. **Start here.** |
| **[FEATURE-INVENTORY.md](documentation/FEATURE-INVENTORY.md)** | The exhaustive, verifiable "everything that's actually built" checklist — every library, every security control, every feature, each pointing at real code. Built to be checked against deliverables line-by-line. |
| **[IMPLEMENTATION-HISTORY.md](documentation/IMPLEMENTATION-HISTORY.md)** | What was built over time, and — more usefully — the problems hit along the way and how each was diagnosed and solved. |
| **[FUTURE-ENHANCEMENTS.md](documentation/FUTURE-ENHANCEMENTS.md)** | The backlog, the open defects, and what it would take to run this as a real product for a small business. |
| **[flows/](documentation/flows/README.md)** | Click-to-database walkthroughs of every major flow — sequence diagrams, JWT/header state, request/response JSON, and the real SQL. |
| **[aws/RUNBOOK.md](aws/RUNBOOK.md)** | The linear, assumes-nothing AWS deploy procedure, written so a teammate can deploy without asking anyone. |

The frontend workspace has its own short README at [`tesseraapp/README.md`](tesseraapp/README.md).

---

## Quick start

**Prerequisites:** Java 21+, Maven 3.8+ (or the bundled `./mvnw`), Node 22 LTS, MySQL 8 *or* Docker,
and Bash (Git Bash or WSL on Windows).

```bash
git clone <repo-url>
cd angularSpringBootFullStack

cp .env.example .env        # PowerShell: Copy-Item .env.example .env
```

Set at minimum in `.env`:

```dotenv
MYSQL_DATABASE=db2
MYSQL_USERNAME=root
MYSQL_PASSWORD=your-db-password
JWT_SECRET=<a-long-random-string>
```

Generate a secret with `openssl rand -base64 48`. Then create and seed the database:

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS db2;"
mysql -u root -p db2 < src/main/resources/schema.sql
```

Open `start.sh`, check the two switches at the top (`ENV=local`, `DB=native`), and run it:

```bash
chmod +x start.sh
./start.sh
```

➡ **http://localhost:4200**

On the `dev` profile a seeder creates one demo user per role, all with the password
**`TesseraDemo@1`** — sign in as `eve.admin@tessera.dev` for the admin surface, or
`alice.guest@tessera.dev` to see the read-only view.

Full setup, every environment variable, Docker mode, and the first-run troubleshooting table are in
[GUIDE.md §2–§3](documentation/GUIDE.md#2-getting-started).

---

## Project structure

```
angularSpringBootFullStack/
├── src/main/
│   ├── java/com/bob/angularspringbootfullstack/   # controller → service → repo, + query/rowmapper/…
│   └── resources/
│       ├── application.yml                        # base config (reads ${ENV_VARS})
│       ├── application-dev.yml                    # dev profile — literal local defaults
│       ├── application-prod.yml                   # prod profile — no fallbacks, fails fast
│       └── schema.sql                             # idempotent identity/auth schema (applied by hand)
├── tesseraapp/                                    # Angular 21 workspace
│   └── src/app/{features,service,interceptor,guard,directive,shared}/
├── documentation/                                 # the four docs + flows/
├── aws/ · gcp/                                    # cloud bootstrap kits + the AWS runbook
├── Dockerfile                                     # multi-stage: Node 22 → Maven 21 → JRE 21 Alpine
├── docker-compose.yml
├── start.sh                                       # the single dev entry point
└── .env.example                                   # committed template — copy to .env
```

---

## Verifying a change

```bash
./mvnw clean test                      # backend: 126 tests
cd tesseraapp && npm test              # frontend: 87 specs
cd tesseraapp && npm run lint          # gates in CI
cd tesseraapp && npm run build         # production build
```

Then run the real app with `./start.sh` and check the actual screen — the test suites deliberately
avoid the filter chain and the browser, so they cannot tell you the whole system still works. See
[GUIDE.md §10](documentation/GUIDE.md#10-testing).

---

## License

Released under the [MIT License](LICENSE) © 2026 Bobby Oliver.

## Disclaimer

Images used in this project are from Unsplash.com. This project is for educational purposes and is
not intended for commercial use. It began as a follow-along of the "Full Stack Spring Boot API with
Angular (ADVANCED)" course on Udemy by Junior (GetArrays); see
[IMPLEMENTATION-HISTORY.md](documentation/IMPLEMENTATION-HISTORY.md) for how far it has moved since.
