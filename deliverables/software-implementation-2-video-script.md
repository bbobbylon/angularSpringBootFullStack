# Implementation 2 — Code Walkthrough Video Script & Guide
### SecureCapita — explaining the software implementation

| | |
|---|---|
| **Presenter** | [Your Name] |
| **Course** | [Course Code / Title] |
| **Goal** | Explain the code and how it works (architecture → key modules → live behaviour) |
| **Suggested length** | 15–20 minutes |
| **Companion report** | [`implementation-2-report.md`](implementation-2-report.md) |

> This video is about **the code**, not just the running app. Show the IDE, walk through the important files, and connect each to what the user sees. Have the app running so you can tie code to behaviour.

---

## Before you record

- Open the project in your IDE with these files bookmarked: `SecurityConfig`, `CustomAuthFilter`, `TokenProvider`, `SessionServiceImpl`, `UserController`, `schema.sql`, and on the frontend `app.config.ts`, `token.interceptor.ts`, `app.routes.ts`.
- Increase editor font size. Have the running app in a second window.
- Record at 1080p with a clear mic.

---

## Segment plan

| Time | Segment | What to show |
|------|---------|--------------|
| 0:00–1:30 | Intro & big picture | The 3 tiers + the hybrid auth idea (use the architecture diagram) |
| 1:30–3:30 | Project structure tour | Backend package layout + frontend folder layout |
| 3:30–9:30 | Backend code walkthrough | The request lifecycle through the layers + the security seams |
| 9:30–13:00 | Data + frontend walkthrough | `schema.sql` / JdbcTemplate; interceptor + guards |
| 13:00–15:30 | Build/deploy + wrap-up | Dockerfile, CI/CD, limitations, conclusion |

---

## 0:00–1:30 — Intro & big picture
- "This video explains how SecureCapita is implemented."
- Show the architecture diagram: Angular SPA → Spring Boot API → MySQL; **stateless access tokens + stateful rotating refresh sessions.**

## 1:30–3:30 — Project structure tour
- Backend: walk `controller → service/serviceimpl → repo/repoimpl`, then point out `tokenprovider`, `filter`, `configuration`, `event/listener`.
- Frontend: `features/`, `service/`, `guard/`, `interceptor/`, `app.config.ts`, `app.routes.ts`.
- One sentence: "Identity uses JdbcTemplate; the business domain uses JPA."

## 3:30–9:30 — Backend walkthrough (the core)
Trace one authenticated request and the security seams:
1. **`SecurityConfig`** — show the ordered authority rules, STATELESS sessions, CSRF off, and that `CustomAuthFilter` is registered before the username/password filter.
2. **`CustomAuthFilter`** — `shouldNotFilter` (public routes) and `doFilterInternal`; emphasise that it authenticates **only** when the token has authorities.
3. **`TokenProvider`** — `createAccessToken` vs `createRefreshToken` (claims: `authorities`, `sid`, `jti`); `isTokenValid` (expiry + `passwordChangedAt`).
4. **`SessionServiceImpl`** — `issueTokenPair` and `rotate`; explain **reuse detection** and why `rotate` is intentionally not `@Transactional`.
5. **`UserController.login`** — the MFA branch (TOTP vs SMS vs direct) and the `HttpResponse` envelope.

> Tie each to behaviour: after explaining the filter, switch to the app and show a 401 → silent refresh in the browser dev tools network tab.

## 9:30–13:00 — Data + frontend walkthrough
- **`schema.sql`** — idempotent CREATE/INSERT; the role + event catalogs. Mention Flyway was removed.
- A repo (e.g. `UserRepoImpl`) + its `query` + `rowmapper` to show the JDBC pattern.
- **Frontend:** `app.config.ts` (interceptor registration order), `token.interceptor.ts` (attach token + single-flight refresh), `authentication.guard.ts`.

## 13:00–15:30 — Build/deploy + wrap-up
- **`Dockerfile`** — the three stages (Angular build → JAR with Angular baked in → JRE runtime).
- **`azure-pipelines.yml`** — build/push to ACR → deploy to App Service.
- Recap the architecture in one breath; state limitations (sparse tests, fixed API origin) and future work; thank the viewer.

---

## Delivery tips
- **Explain *why*, not just *what*** — e.g. why stateless access + stateful refresh, why reuse detection commits-then-throws.
- Keep each file on screen only as long as needed; don't scroll aimlessly.
- Connect code → behaviour at least twice (login, and a 401 refresh) so it's clearly a working system, not just source.
