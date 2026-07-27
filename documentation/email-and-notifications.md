# Email & Notifications Guide

How TesseraApp talks to its users out-of-band and in-band: the two notification channels (email — wired; SMS — stubbed), the account-verification and password-reset email flows, where the message templates and strings live, how the clickable verification link is built from a bare-UUID key, the roles of `UI_APP_URL` and `VERIFY_EMAIL_HOST`, and the Angular toast service that surfaces feedback in the SPA.

> **Audience:** anyone touching registration, password reset, MFA dispatch, or in-app feedback.
> **Key source files:** `service/serviceimpl/NotificationServiceImpl.java` · `service/serviceimpl/EmailServiceImpl.java` · `repo/repoimpl/UserRepoImpl.java` (`getVerificationURL`) · `utils/SMSUtils.java` · `tesseraapp/src/app/service/notifications-service.ts`
> **See also:** [configuration.md](configuration.md) (env vars / `application.yml`) · [security.md](security.md) (token/auth internals) · [api-reference.md](api-reference.md) (the endpoints that trigger these sends) · [flows/01-register-and-verify.md](flows/01-register-and-verify.md) (click-to-DB) · [flows/03-password-reset.md](flows/03-password-reset.md).

> **Code wins over docs.** Every claim below carries a `file:line` citation. If a description here and the code ever disagree, **the code wins** and this doc should be fixed.

---

## Table of contents

1. [Notification channels at a glance](#1-notification-channels-at-a-glance)
2. [The verification-link model](#2-the-verification-link-model)
3. [`UI_APP_URL` (active) vs `VERIFY_EMAIL_HOST` (reserved)](#3-ui_app_url-active-vs-verify_email_host-reserved)
4. [Where the templates and strings live](#4-where-the-templates-and-strings-live)
5. [`EmailServiceImpl` — composing and sending](#5-emailserviceimpl--composing-and-sending)
6. [`NotificationServiceImpl` — async fan-out](#6-notificationserviceimpl--async-fan-out)
7. [SMS and two-factor dispatch](#7-sms-and-two-factor-dispatch)
8. [Configuration reference](#8-configuration-reference)
9. [The frontend `notifications-service` (toasts)](#9-the-frontend-notifications-service-toasts)
10. [Known limitations, gotchas and history](#10-known-limitations-gotchas-and-history)

---

## 1. Notification channels at a glance

The backend has exactly one collaborator the data layer talks to whenever it needs to "tell the user something": `NotificationService` (`service/NotificationService.java:14`). It fans dispatch out to channel-specific senders so callers stay agnostic of SMTP vs Twilio and of the async policy.

| Channel | Use case | Sender | Status |
| --- | --- | --- | --- |
| Email | Account activation | `EmailService.sendVerificationEmail(..., ACCOUNT)` (`EmailServiceImpl.java:54`) | ✅ Wired (Gmail SMTP via `JavaMailSender`) |
| Email | Password reset | `EmailService.sendVerificationEmail(..., PASSWORD)` (`EmailServiceImpl.java:54`) | ✅ Wired |
| SMS | Login 2FA code | `NotificationServiceImpl.sendTwoFactorCode` (`NotificationServiceImpl.java:52`) | ⚠️ Stubbed — log-only; the `SMSUtils.sendSMS` call is commented out (`NotificationServiceImpl.java:55-56`) |
| In-app toast | Success / error / info / warning feedback | `NotificationsService` (frontend, `notifications-service.ts:11`) | ✅ Wired (ngx-toastr) |

**Two email flows, one sender.** Both email use cases funnel through the *same* `EmailServiceImpl.sendVerificationEmail`; the `VerificationType` enum (`enumeration/VerificationType.java:12`) — `ACCOUNT` or `PASSWORD` — is the only thing that diverges the subject line and body template.

```
register / forgot-password (HTTP thread)
        │
        ▼
UserRepoImpl.create() :179   /  UserRepoImpl.resetPassword() :519
   ├─ mint bare UUID key (UUID.randomUUID)         :197 / :526
   ├─ persist key in DB `url` column (NOT the link) :199 / :529
   ├─ build clickable link = getVerificationURL()   :198 / :527
   └─ NotificationService.send…Verification(...)     :200 / :531
                 │  (returns immediately — link already built)
                 ▼
NotificationServiceImpl :40/:46  ── CompletableFuture.runAsync ──▶ ForkJoinPool worker
                                                                      │
                                                                      ▼
                                                  EmailServiceImpl.sendVerificationEmail :54
                                                                      │
                                                                      ▼
                                                  JavaMailSender → Gmail SMTP → recipient
```

> **Note on the async boundary.** Only the *send* is async. The verification URL is built and the DB row written **synchronously, before** the future is scheduled (`UserRepoImpl.java:197-200`, `:526-531`) and inside the `@Transactional` boundary, so a rolled-back registration never produces a usable link.

---

## 2. The verification-link model

Both email flows persist a **bare UUID key**, not a URL, and attach the host only when composing the email body. This is the project's signature decision for verification links.

**What is stored vs what is sent:**

| Artefact | Value | Where |
| --- | --- | --- |
| DB lookup key (`url` column) | bare UUID, e.g. `abc-12ef-uuid` | `INSERT_ACCOUNT_VERIFICATION_URL_QUERY` / `INSERT_PASSWORD_VERIFICATION_QUERY` (`UserRepoImpl.java:199`, `:529`) |
| Clickable link in the email | `{UI_APP_URL}/user/verify/{type}/{key}` | `getVerificationURL(key, type)` (`UserRepoImpl.java:259-263`) |

`getVerificationURL` is now the **only** consumer of the link's host (`UserRepoImpl.java:259-263`): it trims a trailing slash off `uiAppUrl` and appends `/user/verify/{type}/{key}`, producing e.g. `http://localhost:4200/user/verify/account/<uuid>`. The `{type}` segment comes from `VerificationType.getType()`, which lowercases the enum name (`VerificationType.java:42-43`) → `account` or `password`.

**Why the host is kept out of the stored key.** Because the persisted key is host-independent, changing `UI_APP_URL` re-points all *future* emails **without invalidating any pending verification rows** — the later lookup matches on the UUID alone (`SELECT … WHERE url = :url`). This replaced an earlier `ServletUriComponentsBuilder.fromCurrentContextPath()` approach that derived the host from the inbound request and therefore emitted *this backend's* origin instead of the SPA's (`UserRepoImpl.java:249-252`, comment).

**Why the link must point at the SPA, not the backend.** The link resolves to the Angular `VerifyComponent` (routes `user/verify/account/:key` and `user/verify/password/:key`, `app.routes.ts`). The component reads `:key` back off the path and calls the backend (`GET /user/verify/{type}/{key}`) itself. If the link pointed at the backend's REST endpoint, the user would land on raw JSON (`HttpResponse`) and skip the verify UI entirely (`UserRepoImpl.java:115-120`).

**The two verify routes are public — and must stay so in lockstep.** Both `/user/verify/account/**` and `/user/verify/password/**` appear in `Constants.PUBLIC_URLS` (the filter-chain `permitAll` set, `constants/Constants.java:18-26`) **and** in `Constants.PUBLIC_ROUTES` (the `CustomAuthFilter` skip list, `:60-65`). A route public in one list but not the other breaks on a stale `Authorization` header — see [security.md](security.md).

> **There is a known data-model wart.** The column is named `url` but stores a bare key. A `TODO` in `UserRepoImpl.java:195-196` proposes renaming it to `verification_key` (would need a guarded rename in `schema.sql`, applied to the local and Aiven databases). Documented here so the name does not mislead.

---

## 3. `UI_APP_URL` (active) vs `VERIFY_EMAIL_HOST` (reserved)

Two host variables exist; only one feeds the verification link today. **Do not treat `VERIFY_EMAIL_HOST` as dead — it is reserved for future use.**

| Variable | Bound to | Read by | Role | Status |
| --- | --- | --- | --- | --- |
| `UI_APP_URL` | `ui.app.url` (`application.yml:90-92`) | `@Value("${ui.app.url:http://localhost:4200}")` → `UserRepoImpl.uiAppUrl` (`UserRepoImpl.java:129`) | The SPA origin used as the **base of every email verification link** (and the CORS allowed origin) | ✅ Active |
| `VERIFY_EMAIL_HOST` | `spring.mail.verify.host` (`application.yml:81-82`) | *no `@Value` consumer in `src/`* | Reserved for a future backend-hosted verification host (e.g. a server-side landing/redirect). Bound into a property today but not yet consumed by code | 🔒 Reserved — do **not** remove |

The `.env.example` comments state this intent verbatim:

```bash
VERIFY_EMAIL_HOST=http://localhost:8080   # reserved for future use; links currently use UI_APP_URL   (.env.example:45)
UI_APP_URL=http://localhost:4200          # used for CORS and as the base for email verification links (.env.example:48)
```

> **Gotcha:** `VERIFY_EMAIL_HOST` is still *required to be present* because `application.yml:82` resolves `${VERIFY_EMAIL_HOST}` with **no default**. The `dev` profile supplies one (`application-dev.yml:22`), and `.env` ships a value (`.env:36`), so the property binds even though nothing reads it yet. Leave it set.

---

## 4. Where the templates and strings live

There is **no template engine** (no Thymeleaf/FreeMarker, no `.html`/`.ftl` resources). Every user-facing email string is an inline plain-text literal in `EmailServiceImpl`:

| String | Value | Location |
| --- | --- | --- |
| `From:` address | `bobsangularemail@gmail.com` (hardcoded) | `EmailServiceImpl.java:57` |
| Subject (account) | `TesseraApp - Account Verification Email` | `EmailServiceImpl.java:59` |
| Subject (password) | `TesseraApp - Password Verification Email` | `EmailServiceImpl.java:59` |
| Body (account) | `Hello {firstName} … Welcome to TesseraApp! Please click the link to activate your account. {url} …` | `EmailServiceImpl.java:86-88` |
| Body (password) | `Hello {firstName} … Reset Password Request. Please click the link … {url} … If you did not request a password reset, please ignore this email.` | `EmailServiceImpl.java:83-85` |

The subject is `String.format("TesseraApp - %s Verification Email", StringUtils.capitalize(type.name().toLowerCase()))` (`EmailServiceImpl.java:59`), so `ACCOUNT` → `Account`, `PASSWORD` → `Password`.

The body is selected by a `switch (verificationType)` in the private `getEmailMessage` (`EmailServiceImpl.java:81-92`). **Localising template changes to one method is deliberate:** when a new `VerificationType` is added (e.g. an email-change confirmation), the new `case` branch is the only place to touch besides the enum. The `default` branch throws `ApiException("Unable to send email …")` (`EmailServiceImpl.java:89`) — a fail-closed guard against a new enum value shipping without a template.

> **In-app strings live elsewhere.** The success/error messages the user sees as toasts come from the controller `HttpResponse.message` (e.g. "Email sent to reset password…") or the SPA, not from this email layer. See the response-envelope contract in [api-reference.md](api-reference.md).

---

## 5. `EmailServiceImpl` — composing and sending

`EmailServiceImpl` (`service/serviceimpl/EmailServiceImpl.java:31`) is a thin, **synchronous, exception-transparent** wrapper over Spring's `JavaMailSender`.

- **Composition.** Builds a `SimpleMailMessage` — `setTo(email)`, `setFrom(...)`, `setText(getEmailMessage(...))`, `setSubject(...)` — then `mailSender.send(msg)` and a single info log (`EmailServiceImpl.java:54-61`). Plain text only (`SimpleMailMessage`), no HTML/multipart.
- **`JavaMailSender` wiring.** Auto-configured by `MailSenderAutoConfiguration` from the `spring.mail.*` properties (`application.yml:63-82`): Gmail SMTP with STARTTLS required and 5 s connect/read/write timeouts; credentials from `MAIL_USERNAME` / `MAIL_PASSWORD` (`EmailServiceImpl.java:14-20`).
- **Exceptions are not caught here — on purpose.** Any `MailException` (parse/connect/auth failure) propagates to the caller so `NotificationServiceImpl`'s `.exceptionally(...)` logs one accurate error. Catching here would force the caller to log a misleading success (`EmailServiceImpl.java:37-44`).

| Concern | Behaviour | Source |
| --- | --- | --- |
| Threading | Synchronous; the async boundary is the caller's | `EmailServiceImpl.java:24-26` |
| Format | Plain text (`SimpleMailMessage`) | `EmailServiceImpl.java:55` |
| Failure handling | Propagates (transparent) | `EmailServiceImpl.java:60` |
| Unknown `VerificationType` | Throws `ApiException` from `getEmailMessage` | `EmailServiceImpl.java:89` |

---

## 6. `NotificationServiceImpl` — async fan-out

`NotificationServiceImpl` (`service/serviceimpl/NotificationServiceImpl.java:34`) is the dispatch policy layer. It depends only on `EmailService` (`:36`) and exposes three use-case methods:

| Method | Delegates to | Async? | Source |
| --- | --- | --- | --- |
| `sendAccountVerification` | `dispatchVerificationEmail(..., ACCOUNT)` | ✅ | `:40-42` |
| `sendPasswordResetVerification` | `dispatchVerificationEmail(..., PASSWORD)` | ✅ | `:46-48` |
| `sendTwoFactorCode` | (inline; SMS stubbed) | ✅ | `:52-64` |

**The shared async wrapper** (`dispatchVerificationEmail`, `:78-86`) runs the email send on `CompletableFuture.runAsync(...)`'s common `ForkJoinPool`, so the HTTP thread that triggered registration / password reset returns to the client without waiting on the SMTP round-trip. Any failure is funneled through `.exceptionally(throwable -> …)` into a single SLF4J error carrying the verification type and recipient (`:81-85`) — instead of being lost to the `ForkJoinPool`'s default uncaught-exception handler on `stderr`.

> **Why fan dispatch through this layer at all?** It keeps callers (the JDBC repo) agnostic of *which* channel and *what* async policy is used — the repo just says "send the account verification," and the dispatch/threading/error-logging concern lives in one place (`NotificationService.java:5-13`).

---

## 7. SMS and two-factor dispatch

The login 2FA path persists a 7-character code and calls `NotificationServiceImpl.sendTwoFactorCode(firstName, phone, code)` (invoked from `UserRepoImpl.java:443`). What happens next is honestly partial:

- **In the wired path, the SMS is not actually sent.** `sendTwoFactorCode` runs on the `ForkJoinPool` but only **logs** the code; the real `SMSUtils.sendSMS(...)` call is commented out to avoid Twilio charges in development (`NotificationServiceImpl.java:54-58`). ⚠️ So in this build the second-factor code reaches the developer via the log, not the user's phone.
- **`SMSUtils` itself is production-safe — if wired.** `utils/SMSUtils.java` imports and calls `Twilio.init` + `Message.creator(...)` and **sends a real text when all three** `TWILIO_FROM_NUMBER` / `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` are set; when unconfigured it degrades gracefully — `log.warn("Twilio is not configured; SMS not sent …")` and returns rather than throwing (`SMSUtils.java:44-58`, `isConfigured()` `:61-65`). Phone numbers are supplied without a country code; `+1` (US) is prepended (`SMSUtils.java:52-53`).

| Layer | Behaviour | Status | Source |
| --- | --- | --- | --- |
| `NotificationServiceImpl.sendTwoFactorCode` | Logs the code; `SMSUtils.sendSMS` call commented out | ⚠️ Stubbed | `NotificationServiceImpl.java:55-57` |
| `SMSUtils.sendSMS` | Live Twilio send gated on credentials; WARN-logs when unconfigured | ✅ Built (not invoked) | `SMSUtils.java:44-58` |

> **To enable real 2FA texts:** uncomment the `SMSUtils.sendSMS(...)` line in `NotificationServiceImpl.java:55-56` and set the three `TWILIO_*` env vars. No other code change is required — `SMSUtils` already handles the configured/unconfigured split. Note authenticator-app **TOTP** is the fully-implemented MFA path; see [flows/11-totp-enrollment.md](flows/11-totp-enrollment.md) and [security.md](security.md).

---

## 8. Configuration reference

All values are environment-variable driven (12-factor); see [configuration.md](configuration.md) for precedence and the `.env` mechanics.

| Variable | Purpose | Dev default |
| --- | --- | --- |
| `MAIL_HOST` | SMTP host (`spring.mail.host`) | `smtp.gmail.com` (`application-dev.yml`) |
| `MAIL_PORT` | SMTP port | `587` |
| `MAIL_USERNAME` | SMTP auth username | from `.env` |
| `MAIL_PASSWORD` | SMTP auth password (Gmail app password) | from `.env` |
| `UI_APP_URL` | **Base of every email verification link** + CORS origin | `http://localhost:4200` (`application.yml:92`) |
| `VERIFY_EMAIL_HOST` | Reserved (bound to `spring.mail.verify.host`, no consumer yet) — keep set | `http://localhost:8080` (`application-dev.yml:22`) |
| `TWILIO_FROM_NUMBER` | Twilio sender number (SMS) | unset → SMS logs only |
| `TWILIO_ACCOUNT_SID` | Twilio account SID | unset → SMS logs only |
| `TWILIO_AUTH_TOKEN` | Twilio auth token | unset → SMS logs only |

`application.yml` mail block: `spring.mail.*` at `:63-82` (STARTTLS required, 5 s timeouts); `ui.app.url` at `:90-92`. **Never hardcode the Gmail app password** in `application.yml` — it would land in git history (`application.yml:66-68`).

---

## 9. The frontend `notifications-service` (toasts)

In-app feedback is a separate, in-band channel: `NotificationsService` (`tesseraapp/src/app/service/notifications-service.ts:11`), a thin façade over ngx-toastr's `ToastrService`.

| Method | Delegates to | Source |
| --- | --- | --- |
| `onSuccess(message)` | `toastr.success(message)` | `notifications-service.ts:14-16` |
| `onError(message)` | `toastr.error(message)` | `:18-20` |
| `onInfo(message)` | `toastr.info(message)` | `:22-24` |
| `onWarning(message)` | `toastr.warning(message)` | `:26-28` |

**Why a façade.** All components inject `NotificationsService` rather than `ToastrService` directly, so the toast library can be swapped in one place without touching every call site (`notifications-service.ts:4-9`).

**How components use it.** It is the error sink of the canonical `DataState` async pattern: inside the `catchError` branch a component calls `notification.onError(error)` before collapsing the stream into `{ dataState: ERROR }`, so the stream never dies and the user still gets a toast. ngx-toastr is registered once in `app.config.ts` via `provideToastr({ timeOut: 4000, positionClass: 'toast-bottom-right', preventDuplicates: true })`.

> **Note:** these toasts are decoupled from the backend email/SMS layer entirely — they surface the `HttpResponse.message` (or a normalized client error) for the request the user just made; they do not observe whether an email was actually delivered.

---

## 10. Known limitations, gotchas and history

Listed openly per house rule — debt is named, not hidden. Status legend: ✅ done · ⚠️ built-but-not-fully-wired · 🔒 reserved.

| Item | Detail | Status |
| --- | --- | --- |
| Plain-text emails, no templates | Bodies are inline string literals in `EmailServiceImpl.getEmailMessage` (`:81-92`); no HTML, no template engine | ⚠️ |
| Hardcoded `From:` address | `bobsangularemail@gmail.com` (`EmailServiceImpl.java:57`) differs from `MAIL_USERNAME`; Gmail typically rewrites it to the authenticated account anyway | ⚠️ |
| 2FA SMS not actually sent | `SMSUtils.sendSMS` call commented out in `NotificationServiceImpl.java:55-56`; code is log-only despite `SMSUtils` being production-ready | ⚠️ |
| `url` column stores a key, not a URL | Misleading name; rename TODO at `UserRepoImpl.java:195-196` | ⚠️ |
| `VERIFY_EMAIL_HOST` unused today | Bound to `spring.mail.verify.host` with no `@Value` consumer; **reserved for future use**, must stay set (no default at `application.yml:82`) | 🔒 |
| Verification keys never expire (account) | Account-verification rows have no expiry column; only password-reset rows carry `expiration_date` (24 h, `UserRepoImpl.java:523`) | ⚠️ |
| Enumeration on password-reset unknown email | `resetPassword` throws `ApiException("Email not found …")` for an unknown address (`UserRepoImpl.java:520-521`), which can reveal account existence; the *controller* message is generic. Project policy is enumeration-safe (see [security.md](security.md)) — make the service return the same generic success to fully close it | ⚠️ |

**History.** The verification-link host was previously derived from the inbound request via `ServletUriComponentsBuilder.fromCurrentContextPath()`, which emitted the backend's own origin; it was replaced by `getVerificationURL` building from `UI_APP_URL` so links point at the SPA and the stored key stays host-independent (`UserRepoImpl.java:249-252`).

---

## Cross-links

- The full register → activate click-to-DB trace (Mermaid + SQL) → [flows/01-register-and-verify.md](flows/01-register-and-verify.md)
- The forgot-password → reset-link → set-new-password trace → [flows/03-password-reset.md](flows/03-password-reset.md)
- Token/auth internals, the `PUBLIC_URLS ↔ PUBLIC_ROUTES` lockstep, MFA → [security.md](security.md)
- Every env var, profiles, annotated `application.yml` → [configuration.md](configuration.md)
- The endpoints that trigger these sends, with payloads → [api-reference.md](api-reference.md)
