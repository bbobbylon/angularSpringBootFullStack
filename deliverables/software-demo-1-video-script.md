# Software Demo 1 — Video Script & Recording Guide (≈15 minutes)
### SecureCapita — Phase 1 Prototype Demonstration

| | |
|---|---|
| **Presenter** | [Your Name] |
| **Course** | [Course Code / Title] |
| **Target length** | 15 minutes |
| **Delivery** | Record screen + voice → upload to YouTube → paste link in the submission system |

> This is a **template/script**, not the video. Follow the segments and timings; adapt the talking points to your voice.

---

## Before you record

- **Resolution:** record at 1080p; increase editor/browser font size so code and UI are legible.
- **Audio:** use a headset mic; record in a quiet room; do a 10-second test.
- **Prep the app:** start it (`./start.sh`), confirm the DB is seeded, and log out so you begin clean. Have an authenticator app (e.g. Google Authenticator) ready on your phone or a desktop TOTP app.
- **Have open in tabs:** the running app (`http://localhost:4200`), the architecture diagram (from the System Architecture doc), and your IDE (for the brief code peek).
- **Pace:** rehearse once; 15 minutes goes fast. Aim to *show*, not read.

---

## Segment plan

| Time | Segment | Goal |
|------|---------|------|
| 0:00–1:00 | Introduction | Who you are, what SecureCapita is |
| 1:00–3:00 | Architecture & tech overview | The big picture in 2 minutes |
| 3:00–13:00 | Live demo | Prove it works, feature by feature |
| 13:00–15:00 | Wrap-up | What's done, what's next |

---

## 0:00–1:00 — Introduction
- "Hi, I'm [name]. This is the Phase 1 demo of **SecureCapita**, a full-stack user-management and identity platform."
- One sentence on the problem: *secure, self-service identity management with modern MFA and role-based administration.*
- One sentence on the stack: *Angular 21 frontend, Spring Boot 4 API, MySQL, with an in-house zero-trust auth core.*

## 1:00–3:00 — Architecture & technology overview
- Show the architecture diagram. Walk it in ~90 seconds:
  - Three tiers: Angular SPA → Spring Boot REST API → MySQL.
  - Auth model: **stateless JWT access tokens + server-tracked, rotating refresh sessions.**
  - Permission-based RBAC; event-driven audit.
- "Now let's see it working."

## 3:00–13:00 — Live demo (the core 10 minutes)

**A. Registration & verification (≈1.5 min)**
- Register a new account; show the success message.
- Note that the account starts disabled and is activated via an email verification link (in dev, shown in the server console).

**B. Login + MFA (≈2 min)**
- Log in as `eve.admin@tessera.dev` / `TesseraDemo@1`.
- If TOTP is enrolled, show the authenticator prompt and enter a live code. Mention the brute-force throttle and that TOTP supersedes SMS.

**C. Dashboard (≈1 min)**
- Show the KPI stat cards. Point out the role-aware navbar (admin links visible for this user).

**D. Account Security Center (≈2.5 min)**
- Enroll an authenticator: scan the QR, confirm a code, show the one-time recovery codes.
- Open the **sessions/devices** panel: point out "this device", revoke another session, and "log out everywhere else." Explain reuse detection in one line ("replaying an old refresh token revokes the whole session").

**E. Administration (≈2 min)**
- Open **Users**: search the directory.
- Open a user, change their role, and show the change appears in *their* audit history.
- Mention organization scoping (an org admin only sees users in their org).

**F. Business domain (≈1 min)**
- Create a customer; create/attach an invoice; click **Export** to download an XLSX report.

## 13:00–15:00 — Wrap-up
- Recap what Phase 1 delivered (auth + MFA + RBAC + admin + business domain + audit, all working).
- Be honest about limitations (SMS stubbed, tests sparse) and state the Phase 2 plan.
- "Thanks for watching — the code and full documentation are in the repository."

---

## Timing safety net
If you're running long, trim segment **F** (business domain) and the registration detail in **A**; the security features (B, D, E) are the differentiators and should not be cut.
