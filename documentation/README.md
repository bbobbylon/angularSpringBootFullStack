# TesseraApp Documentation

The full documentation set for TesseraApp — an Angular 21 + Spring Boot 4 user-management & CIAM reference app. Start at the repo root [`README.md`](../README.md) for the project overview; this folder holds the in-depth guides.

---

## Start here

| If you want to… | Read |
|-----------------|------|
| **Get it running** | [getting-started.md](getting-started.md) — setup → run → first login |
| **Understand the whole system** | [developer-guide.md](developer-guide.md) — the in-depth, end-to-end walkthrough |
| **Trace one feature end-to-end** | [flows/](flows/README.md) — every use case from button click → JWT/headers → controller → DB → UI change |
| **Work in the codebase day-to-day** | [development-workflow.md](development-workflow.md) — IDE setup, the `start.sh` loop, debugging, branch workflow |
| **See project status & what's planned** | [project-status-and-roadmap.md](project-status-and-roadmap.md) — proposed-vs-actual, known gaps, prioritized backlog |
| **Look back at how it was built** | [history/PROJECT-HISTORY.md](history/PROJECT-HISTORY.md) — evolution narrative, M0–M7 milestones, retired-doc registry, branch snapshot |

## Reference guides

| Guide | Covers |
|-------|--------|
| [architecture.md](architecture.md) | Tiers, layered backend, request lifecycle, frontend, directory map, design trade-offs |
| [flows/](flows/README.md) | **End-to-end flow docs** — click-to-database sequence diagrams for every use case (auth, MFA, federation, sessions, admin, CRUD) with JWT/header state, request/response JSON, and real SQL. The *dynamic* complement to `architecture.md`'s static view |
| [api-reference.md](api-reference.md) | Every REST endpoint, grouped by controller, with auth + payloads |
| [security.md](security.md) | JWT, refresh-session rotation & reuse detection, TOTP/SMS MFA, federation, RBAC, 401 vs 403 |
| [database.md](database.md) | Persistence model, every table, relationships, role + event reference data |
| [configuration.md](configuration.md) | Environment variables, Spring profiles, annotated `application.yml`, gotchas |
| [deployment.md](deployment.md) | Docker image, Compose, Azure CI/CD, cloud platforms |
| [frontend-guide.md](frontend-guide.md) | Angular internals — routes→components, the six services, guards, interceptors, `DataState`/signals state pattern |
| [email-and-notifications.md](email-and-notifications.md) | Email (verification/reset) + SMS-stub + Angular toast notifications; the verification-link model |
| [testing.md](testing.md) | Test inventory, how to run, how to write backend/frontend tests, the coverage roadmap |
| [../tesseraapp/README.md](../tesseraapp/README.md) | Frontend (Angular) specifics |

## Suggested reading paths

- **New contributor:** getting-started → architecture → developer-guide → (the topic guide for your task)
- **Tracing a feature or bug:** [flows/](flows/README.md) → `00-anatomy-of-a-request.md` (the shared spine) → the specific flow doc for your screen
- **Reviewing security:** security.md → `SecurityConfig.java` / `TokenProvider.java` / `SessionServiceImpl.java`
- **Integrating a client:** api-reference.md → security.md (§3 tokens)
- **Deploying:** configuration.md → deployment.md → database.md (apply `schema.sql`)

---

## Other assets in this folder

- **`architectLayout.png`** — architecture diagram image.
- **`APIs.postman_collection`** — Postman collection for exercising the API.
- Project license — moved to the repo root: [`../LICENSE`](../LICENSE).
