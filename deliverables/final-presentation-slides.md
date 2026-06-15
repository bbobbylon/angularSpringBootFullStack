---
marp: true
title: SecureCapita — Final Presentation
paginate: true
theme: default
---

<!--
This is a Marp deck. To export to PowerPoint or PDF:
  • VS Code: install the "Marp for VS Code" extension → Export Slide Deck → .pptx / .pdf
  • CLI: npx @marp-team/marp-cli final-presentation-slides.md -o final-presentation.pptx
Each "---" starts a new slide. Replace [bracketed] placeholders and speaker notes (HTML comments) as needed.
-->

# SecureCapita
## A Full-Stack CIAM Platform — Angular 21 + Spring Boot 4

**[Your Name]** · [Co-author]
[Course Code / Title] · [Institution]
[Date]

---

## The Problem

- Applications need **secure, self-service identity management** — but rolling your own auth is where most security bugs live.
- Users expect **modern MFA**, **single sign-on**, and **session control over their devices**.
- Organizations need **role-based administration** with least privilege and a full **audit trail**.

> SecureCapita is a reference implementation of an in-house, zero-trust CIAM core around a real business application.

---

## Objectives

1. A production-style **authentication core**: JWT, refresh-session rotation, MFA, federation.
2. **Role-based access control** with organization scoping.
3. A complete **administrative console** and **user self-service**.
4. A real **business domain** (customers & invoices) to secure.
5. **Auditability** of every security-relevant action.

---

## Background

- **CIAM** (Customer Identity & Access Management) vs. traditional IAM: external users, self-service, scale.
- **Zero-trust principle:** never trust, always verify — every request re-validated.
- **Stateless JWT** for scale vs. **stateful sessions** for revocability — SecureCapita uses a **hybrid**.
- *(Tie to your literature review here.)*

---

## Requirements (SRS highlights)

- Secure registration/login, email verification, password reset.
- MFA: authenticator app (TOTP) + SMS.
- Federated login (OAuth2/OIDC).
- Seven-role RBAC; organization-scoped admin.
- Audit logging; session/device management.
- *(Reference the full SRS document.)*

---

## Architecture — 4+1 at a glance

```
Angular 21 SPA  ──HTTPS/JSON──▶  Spring Boot 4 API  ──JDBC──▶  MySQL 8
 (:4200 dev)        JWT access +     (:8080)                    (db2)
                    refresh tokens      │
                    ├─ Spring Security 7 (JWT filter, RBAC)
                    ├─ JdbcTemplate (identity)  + JPA (business)
                    └─ OAuth2 client · TOTP · audit events
```

- **Logical · Process · Development · Physical** views + scenarios — see the Architecture document.

---

## Security model — the differentiator

- **Stateless access tokens** (30 min) — verified by signature, no DB hit.
- **Stateful refresh sessions** (5-day sliding) — **rotated** on every use.
- **Reuse detection:** replaying an old refresh token **revokes the whole session family**.
- **MFA:** challenge-bound TOTP; single-use recovery codes.
- **RBAC:** permission strings enforced at **URL and method** level.

---

## Technology stack

| Layer | Tech |
|-------|------|
| Frontend | Angular 21, Bootstrap 5, RxJS |
| Backend | Spring Boot 4, Java 21, Spring Security 7 |
| Data | MySQL 8 (JdbcTemplate + JPA) |
| Auth | JWT (HMAC-512), OAuth2, ZXing (TOTP QR) |
| Ops | Docker (multi-stage), Azure CI/CD |

---

## Implementation highlights

- Single token-issuance seam (`SessionService`) → every login is a revocable session.
- Event-driven audit pipeline (decoupled from the request path).
- Idempotent `schema.sql` (replaced a fragile migration tool).
- Standalone Angular components + HTTP interceptor with **single-flight token refresh**.

---

## Demo — Scenario 1: Login with MFA
<!-- Switch to the live app here. ~2-3 min. -->

- Log in → authenticator challenge → dashboard.
- Show the **Security Center**: enroll authenticator, view devices, revoke a session.

**[LIVE DEMO]**

---

## Demo — Scenario 2: Admin RBAC
<!-- Live app. ~2 min. -->

- Admin opens the **user directory**, reassigns a role.
- Change appears in the target user's **audit log**.
- Note **organization scoping** (org admins see only their org).

**[LIVE DEMO]**

---

## Results

- End-to-end working prototype: auth + MFA + RBAC + admin + business domain + audit.
- All primary UI screens implemented and responsive.
- Security model validated against the SRS requirements.

---

## Challenges & lessons

- Migration-tool baseline drift → switched to an idempotent schema script.
- ORM identifier-quoting pitfalls → explicit column mappings.
- Designing reuse detection to **commit-then-throw** (not roll back the revocation).

---

## Limitations & future work

- SMS 2FA stubbed; federation needs provider credentials.
- Sparse automated tests → build a real suite.
- Externalize the frontend API origin; cloud-ready image storage.
- Distributed rate limiting for horizontal scale.

---

## Conclusion

- SecureCapita demonstrates a **complete, modern CIAM core** on a real application.
- Hybrid stateless/stateful auth balances **scale and control**.
- Clean, layered, documented codebase ready to extend.

---

# Thank you
## Questions?

**[Your Name]** · [email]
Repository: [repo URL]
