# Software Requirements Specification
## Hybrid Identity and Access Management System

**Authors:** Robert Oliver Jr., Travis L. Lester, Larry Motuzis
**Course:** [Course Number / Title]
**Document version:** 0.3 (draft)
**Date:** June 17, 2026
**Status:** Draft for review

> Prepared in accordance with the structure of IEEE Std 830-1998, *Recommended Practice for Software Requirements Specifications.*

---

## Table of Contents

1. Introduction
2. Overall Description
3. External Interface Requirements
4. System Features (Functional Requirements)
5. Logical Database Requirements
6. Design Constraints
7. Standards Compliance
8. Software System Attributes (Nonfunctional Requirements)
9. Other Requirements
- Appendix A: Glossary
- Appendix B: Requirements Traceability Summary

---

## 1. Introduction

### 1.1 Purpose
This document specifies the software requirements for the **Hybrid Identity and Access Management System** (hereafter "the system" or "TesseraApp"). The system is a hybrid Customer Identity and Access Management (CIAM) platform that combines an in-house authentication core with federated identity through third-party OAuth2 / OpenID Connect providers. This SRS describes what the product is expected to do from the perspective of its users and operators. It is the authoritative reference for design, implementation, verification, and acceptance.

This revision (0.3) covers the complete intended system. Where a requirement describes capability that is planned but not yet implemented in the current codebase, it is marked **(planned)**; all other requirements describe capability that exists today or is in active development. Relative to revision 0.1, this document claims federated authentication (§4.3), organization-scoped administration (FR-ORG), authenticator-app MFA (FR-MFA-4), and refresh-token rotation with session management (§4.4, §4.11) as implemented. Revision 0.3 corrects the schema-management description throughout: the project no longer uses Flyway migrations (deliberately removed because their baseline bookkeeping kept desynchronizing from the live database) and instead defines its schema in a single, version-controlled, idempotent `schema.sql` script (see DB-14, NFR-MAINT-3).

### 1.2 Document Conventions
- The key word **shall** denotes a mandatory requirement. **Should** denotes a recommendation. **May** denotes an optional capability.
- Each requirement carries a unique identifier (for example, `FR-AUTH-3`) so it can be traced through design, code, and test.
- "In-house authentication" refers to email-and-password credentials held by the system. "Federated authentication" refers to sign-in delegated to an external identity provider.
- Token lifetimes, role names, and permission strings reflect the current system configuration and are stated as defaults; values marked configurable may be changed without altering a requirement's intent.
- Each major section closes with an *Implementation status* line summarizing what the current codebase provides for that section; **Appendix C** consolidates these into a status table with code evidence and lists the known spec-versus-code discrepancies.

### 1.3 Intended Audience and Reading Suggestions
The intended audience is the course instructor and grader, the project team (developers and testers), and any future maintainer. Readers seeking a high-level understanding should read Sections 1 and 2. Developers and testers should focus on Sections 3 through 8. Readers concerned with data design should read Section 5; readers concerned with compliance should read Section 7.

### 1.4 Product Scope
The system provides centralized control over authentication policy, authorization (role and permission management), and audit logging, while allowing users to authenticate either with in-house credentials or through a trusted external identity provider (Google, GitHub, or Microsoft). The two paths converge at a single token-exchange point: after any successful authentication, the system issues its own JSON Web Token (JWT), so that role-based access control, multi-factor policy, and audit logging are applied uniformly regardless of how the user signed in.

The goal is to demonstrate a practical middle path between full vendor dependency and complete self-management: external providers are used only to verify *who* a user is, after which the system retains full internal authority over *what* that user is permitted to do.

In scope: registration, in-house and federated login, JWT issuance and refresh with server-tracked refresh-token rotation, multi-factor authentication (SMS and authenticator-app TOTP), session and device management, role-based and organization-scoped authorization, password lifecycle, authentication event logging, and an administrative dashboard for user, role, and account management.

Out of scope for this revision: machine-to-machine (client-credentials) authorization, SCIM provisioning, and SAML federation. AI-based anomaly detection and a login-analytics reporting dashboard are described as **(planned, time-permitting)** capabilities.

### 1.5 References
- IEEE Std 830-1998, *Recommended Practice for Software Requirements Specifications.*
- IETF RFC 6749, *The OAuth 2.0 Authorization Framework.*
- IETF RFC 7519, *JSON Web Token (JWT).*
- IETF RFC 6238, *TOTP: Time-Based One-Time Password Algorithm* (and RFC 4226, *HOTP*).
- OpenID Connect Core 1.0.
- OWASP Application Security Verification Standard (ASVS) v4.0.3; OWASP Top 10 (2021); OWASP Automated Threat OAT-007 (Credential Cracking) / enumeration guidance.
- Project document: *Hybrid Identity and Access Management System — Project Proposal* (2026-05-23).
- Project document: *Build or Buy? A Literature Review of Customer Identity and Access Management (CIAM) Systems.*

---

## 2. Overall Description

### 2.1 Product Perspective
The system is a self-contained, three-tier web application built new for this project; it is not a component of a larger system. It consists of:

- a **frontend single-page application** (Angular) served to the user's browser,
- a **backend REST API and identity server** (Spring Boot with Spring Security), which is the centralized handler for every authentication and authorization decision, and
- a **centralized relational database** (MySQL) that stores accounts, credentials, roles and permissions, organization membership, federated identity links, verification tokens, and audit events.

External identity providers (Google, GitHub, Microsoft), an SMS gateway (Twilio), and an SMTP email service are integrated dependencies, not parts of the product.

### 2.2 Product Functions (summary)
At a high level the system shall allow users to:
- create an account and verify it by email;
- sign in with in-house credentials or a federated provider;
- complete a second authentication factor when enabled (SMS code or authenticator app);
- receive and silently refresh JWT access tokens, with refresh tokens rotated on every use;
- enroll, confirm, and remove an authenticator app, including single-use recovery codes;
- view and revoke their active sessions (signed-in devices), individually or all at once;
- reset a forgotten password and change a known one;
- view their own authentication activity history.

It shall allow administrators to:
- view and search the user directory within their authority scope;
- reassign roles and adjust account state (enable, disable, lock) for other users;
- review authentication and account events across users in scope.

### 2.3 User Classes and Characteristics
The system defines seven roles, each mapped to a set of fine-grained permission strings that Spring Security enforces at the URL and method level.

| Role | Class | Typical permissions | Notes |
|------|-------|---------------------|-------|
| `ROLE_USER` | End user | `READ:USER`, `READ:CUSTOMER` | Default role for new and federated users |
| `ROLE_GUEST` | End user | minimal / read-only | Lowest privilege |
| `ROLE_MODERATOR` | Staff | content moderation + read | |
| `ROLE_HELP_DESK_ADMIN` | Staff | support-focused user reads/updates | |
| `ROLE_ORGANIZATION_ADMIN` | Administrator | manage users **within own organization** | Subject to org-scope checks |
| `ROLE_ADMIN` | Administrator | full user/role management | |
| `ROLE_APPLICATION_ADMIN` | Super administrator | full system access; bypasses org scope | |

End users are assumed to be non-technical and to access the system from a current desktop or mobile browser. Administrators are assumed to understand roles and permissions but not the system internals.

### 2.4 Operating Environment
- **Client:** current evergreen browsers (Chrome, Edge, Firefox, Safari) on desktop and mobile; JavaScript enabled.
- **Server runtime:** Java 21 on Spring Boot 4 / Spring Security 7; packaged as an executable JAR and as a multi-stage Docker image running on a minimal non-root JRE base.
- **Database:** MySQL 8.4, accessed through JDBC repositories; the schema is defined by a single, version-controlled, idempotent SQL script (`schema.sql`) applied identically to every environment. Deployable locally, on Aiven (managed cloud MySQL), or on AWS RDS.
- **External services:** Twilio (SMS), an SMTP provider such as Gmail or SendGrid (email), and the OAuth2 providers Google, GitHub, and Microsoft.

### 2.5 Design and Implementation Constraints
See Section 6 for the full list. In summary, the frontend shall be Angular 21 with standalone components and Bootstrap 5; the backend shall be Java 21 / Spring Boot 4 / Spring Security 7 built with Maven; request authentication shall be stateless (JWT only, no server-side HTTP session), with the deliberate exception of a server-side refresh-session store that exists solely to make refresh tokens rotatable and revocable (see §4.4 and CON-3); and all credential storage shall use BCrypt.

### 2.6 User Documentation
The system shall ship with a project `README` describing build, configuration, and run procedures (local, Docker, and cloud database profiles), and with in-application guidance: contextual labels, validation messages, and an administrative help surface describing roles and permissions.

### 2.7 Assumptions and Dependencies
- Users have access to the email address used at registration and, when MFA is enabled, to the registered mobile phone (SMS) or the enrolled authenticator app (TOTP).
- The external identity providers, SMS gateway, and SMTP service are available and correctly configured with valid credentials.
- The deployment environment terminates TLS so that all client/server traffic is encrypted in transit.
- Server clocks are synchronized within a small tolerance, since JWT expiry and time-limited tokens depend on consistent time.

---

## 3. External Interface Requirements

### 3.1 User Interfaces
- **EIR-UI-1:** The frontend shall be a single-page Angular application presenting, at minimum: registration, in-house login, federated login entry points, email/account verification, two-factor code entry, password reset request and completion, a user profile with authentication activity history, an account security center (second-factor enrollment and session/device management), and an administrative user-management dashboard.
- **EIR-UI-2:** The interface shall support a dark and a light color theme, selectable by the user and persisted across sessions, and shall default to the operating-system preference on first visit.
- **EIR-UI-3:** All forms shall perform client-side validation and shall display server-returned error messages in a consistent, dismissible alert region.
- **EIR-UI-4:** The interface shall be responsive across desktop and mobile viewport widths and shall meet WCAG 2.1 AA contrast and keyboard-navigation expectations for primary flows.

### 3.2 Hardware Interfaces
- **EIR-HW-1:** The system has no direct hardware interface beyond the standard client device (keyboard, pointer, touch) and the server host. It does not require specialized hardware such as biometric readers or hardware security modules in this revision.

### 3.3 Software Interfaces
- **EIR-SW-1 (Identity providers):** The backend shall integrate with Google, GitHub, and Microsoft using the OAuth2 Authorization Code flow with OpenID Connect, via the Spring Security OAuth2 Client module. Providers are registered conditionally from environment configuration, so an environment exposes only the providers it holds credentials for.
- **EIR-SW-2 (SMS):** The backend shall send one-time MFA codes through the Twilio SMS API.
- **EIR-SW-3 (Email):** The backend shall send account-verification and password-reset messages through an SMTP provider using the JavaMail API.
- **EIR-SW-4 (Database):** The backend shall persist all state to MySQL 8.4 through JDBC repositories. The schema shall be defined by a single, version-controlled, idempotent SQL script (`schema.sql`) so that every environment converges to the same structure.
- **EIR-SW-5:** All external-service credentials and endpoints shall be supplied through environment configuration, never hard-coded in source.

### 3.4 Communications Interfaces
- **EIR-COMM-1:** All client/server communication shall use HTTPS. The backend shall set HTTP Strict-Transport-Security with a one-year max-age and `includeSubDomains`.
- **EIR-COMM-2:** The API shall be RESTful and shall exchange JSON. Every response shall use a consistent envelope containing a timestamp, HTTP status, status code, message, and an optional data map.
- **EIR-COMM-3:** Authenticated requests shall carry the access token in the `Authorization: Bearer <token>` header. The backend shall expose and accept the headers required for token transport via a restrictive CORS policy that whitelists only the configured frontend origins.
- **EIR-COMM-4:** The backend shall send the security response headers `X-Frame-Options: DENY` and `X-Content-Type-Options: nosniff` on all responses.

*Implementation status: **IMPLEMENTED** — UI (incl. the Security Center, EIR-UI-1), email (JavaMail), REST envelope, CORS, HSTS and anti-clickjacking/MIME headers built; OAuth2 providers registered env-conditionally (`OAuth2ClientConfig`, EIR-SW-1); the schema is defined by the idempotent `schema.sql` (EIR-SW-4). One dev stub: the Twilio send in EIR-SW-2 is logged, not dispatched, until credentials are set.*

---

## 4. System Features (Functional Requirements)

### 4.1 User Registration and Account Verification
**Description.** A visitor creates an in-house account, which is inactive until verified by email.
- **FR-REG-1:** The system shall accept a registration payload (first name, last name, email, password) and shall validate all fields server-side.
- **FR-REG-2:** The system shall reject registration when the email is already in use, without revealing to the requester whether the email exists (see `NFR-SEC-7`).
- **FR-REG-3:** On registration the system shall store the password only as a BCrypt hash and shall create the account in a disabled (unverified) state.
- **FR-REG-4:** The system shall generate a single-use account-verification key, persist it, and email the user a link containing that key.
- **FR-REG-5:** The system shall activate the account when the verification link is followed, and shall report clearly whether the account was newly verified or already verified.

*Implementation status: **IMPLEMENTED** — `UserController.saveUser` + `verifyAccount`; BCrypt hashing; account created disabled until the emailed key is followed.*

### 4.2 In-House Authentication
**Description.** A registered user signs in with email and password.
- **FR-AUTH-1:** The system shall authenticate credentials using Spring Security's `AuthenticationManager` with a BCrypt password encoder.
- **FR-AUTH-2:** On success for an account without MFA, the system shall issue an access token and a refresh token (see 4.4).
- **FR-AUTH-3:** On success for an account with MFA enabled, the system shall not issue tokens until the second factor is verified (see 4.5); it shall instead signal that a verification code has been sent.
- **FR-AUTH-4:** On failure, the system shall return a generic authentication-failure message that does not disclose whether the email exists or whether the password was the incorrect field.
- **FR-AUTH-5:** The system shall reject authentication for accounts that are disabled or locked.

*Implementation status: **IMPLEMENTED** — `login()` authenticates through `AuthenticationManager` + BCrypt; generic failure message; login events fire only for known emails.*

### 4.3 Federated Authentication (OAuth2 / OpenID Connect)
**Description.** A user signs in through Google, GitHub, or Microsoft instead of in-house credentials.
- **FR-FED-1:** The system shall initiate the OAuth2 Authorization Code flow for the selected provider and shall handle the provider redirect and authorization-code exchange.
- **FR-FED-2:** On the provider callback, the system shall verify the identity token and extract a stable provider subject identifier and the user's email.
- **FR-FED-3:** The system shall find or create a local user record linked to that provider identity (a "find-or-create" on first federated login), assigning `ROLE_USER` by default.
- **FR-FED-4:** After the local record is resolved, the system shall issue its own application JWT, so that RBAC enforcement, MFA policy, and audit logging apply identically to federated and in-house sessions.
- **FR-FED-5:** The system shall record the authentication method (in-house vs federated, and which provider) on every resulting audit event.
- **FR-FED-6:** The system shall not store any third-party password or long-lived provider credential; it shall persist only the provider name and the provider subject identifier needed to re-link the account.

*Implementation status: **IMPLEMENTED** — `spring-security-oauth2-client` handles the protocol (CON-5); `OAuth2LoginSuccessHandler` is the token-exchange point (find-or-create keyed on provider+subject, FR-FED-3/6); the `oauthproviderlinks` table; disable/lock and MFA policy apply to federated logins (FR-FED-4).*

### 4.4 JWT Token Issuance, Validation, and Refresh
**Description.** All authenticated access is mediated by signed JWTs.
- **FR-JWT-1:** The system shall issue a short-lived **access token** (default 30 minutes) and a longer-lived **refresh token** (default 5 days), both signed with HMAC-SHA-512.
- **FR-JWT-2:** Access tokens shall embed the user's granted authorities so that authorization can be enforced without a database lookup on every request.
- **FR-JWT-3:** A stateless JWT filter shall validate the token on every protected request and shall populate the security context with the authenticated principal before authorization rules run.
- **FR-JWT-4:** The system shall expose a refresh endpoint that exchanges a valid refresh token for a new access token **and a rotated refresh token**; the presented refresh token shall be unusable from that moment.
- **FR-JWT-5:** The frontend shall attach the access token to every outbound API call through an HTTP interceptor and shall transparently refresh an expired access token, using a locking mechanism that prevents concurrent refresh requests ("refresh storms"), storing the rotated token pair on every refresh.
- **FR-JWT-6:** A password change shall invalidate every token issued before the change, enforced by comparing the token's issue time against a stored `passwordChangedAt` timestamp, and shall additionally revoke all of the user's server-tracked sessions.
- **FR-JWT-7:** Every issued refresh token shall be recorded as a server-side session: a stable session *family* (one per device login) containing the current token's unique identifier (`jti`), with device description, IP address, creation, last-use, and expiry timestamps.
- **FR-JWT-8 (reuse detection):** Presenting a refresh token that was already rotated or revoked shall be treated as evidence of token theft: the system shall revoke the entire session family, record a `TOKEN_REUSE_DETECTED` audit event, and force re-authentication.

*Implementation status: **IMPLEMENTED** — `TokenProvider` signs with HMAC-512; 30-min access / 5-day refresh (`Constants`); `passwordChangedAt` invalidation (FR-JWT-6). Rotation with reuse detection (FR-JWT-7/8) via `SessionService` + the `refreshsessions` table: every refresh rotates the token, and replaying a retired token revokes its whole session family.*

### 4.5 Multi-Factor Authentication
**Description.** An optional second factor delivered by SMS or generated by an authenticator app.
- **FR-MFA-1:** A user shall be able to enable or disable MFA from the account security settings; enabling SMS MFA shall require a phone number on the account.
- **FR-MFA-2:** When MFA is enabled, after a successful first factor (in-house or federated) the system shall withhold tokens until the second factor is verified — delivering a time-limited one-time code by SMS through Twilio, or requesting a code from the enrolled authenticator app.
- **FR-MFA-3:** The system shall issue the access and refresh tokens only upon successful verification of the one-time code.
- **FR-MFA-4:** Authenticator-app (TOTP, RFC 6238) second factors shall be offered as an alternative to SMS, taking precedence over SMS when both are enabled. Enrollment shall present the shared secret as a QR-encoded provisioning URI and shall activate only after the user confirms with a valid code; activation shall issue single-use recovery codes stored only as hashes and displayed exactly once.
- **FR-MFA-5:** Login-time TOTP verification shall be bound to a short-lived, server-side challenge minted only after a successful first factor, so possession of an authenticator code alone can never complete a login; disabling the authenticator shall likewise require a valid current code or recovery code.

*Implementation status: **IMPLEMENTED** — Authenticator-app TOTP (FR-MFA-4/5) via `TotpService`: RFC 6238 implemented in-house (`TotpUtils`), QR enrollment confirmed by a first code, hashed single-use recovery codes, and a server-side login challenge binding verification to a completed first factor. SMS path (FR-MFA-1..3) built; Twilio dispatch is a dev stub (logged).*

### 4.6 Authorization: Role-Based and Organization-Scoped Access Control
**Description.** Authorization is permission-based and, for organization administrators, scoped to an organization.
- **FR-RBAC-1:** The system shall associate every user with exactly one role, and every role with a set of fine-grained permission strings (for example `READ:USER`, `UPDATE:USER`, `UPDATE:ROLE`, `DELETE:USER`).
- **FR-RBAC-2:** The system shall convert a role's permission string into Spring Security authorities and shall enforce them at both the URL level and the method level.
- **FR-RBAC-3:** The system shall deny any request whose principal lacks the required authority, returning HTTP 403 through a custom access-denied handler, and shall return HTTP 401 for missing or invalid tokens through a custom entry point.
- **FR-RBAC-4:** A user shall not be able to elevate their own role. Role reassignment shall require an administrative authority and shall be performed only through the administrative endpoints (see 4.9). *(This closes the gap in which `PATCH` self-update routes were not authority-gated.)*
- **FR-ORG-1:** The system shall associate users with one or more organizations and shall scope `ROLE_ORGANIZATION_ADMIN` authority to the administrator's own organization.
- **FR-ORG-2:** An organization administrator shall be able to view and manage only users who share an active organization with them; an attempt to act on a user outside that scope shall return HTTP 403.
- **FR-ORG-3:** `ROLE_APPLICATION_ADMIN` shall bypass the organization-scope check and may act on any user.

*Implementation status: **IMPLEMENTED** — FR-RBAC-1..3: permission authorities, URL + method security, custom 401/403 handlers. FR-RBAC-4 closed: the self-service role endpoint was removed; role changes require `UPDATE:ROLE` via `AdminUserController`, which refuses self-targeting. FR-ORG-1..3: `organizations` / `userorganizations` with `OrganizationServiceImpl`'s shared-active-membership check; out-of-scope access returns 403.*

### 4.7 Password Management
**Description.** Forgotten-password reset and known-password change.
- **FR-PWD-1:** A user shall be able to request a password reset by supplying their email; the system shall always respond with the same neutral message whether or not the email is registered (see `NFR-SEC-7`).
- **FR-PWD-2:** For a registered email, the system shall generate a single-use, time-limited reset key, persist it, and email a reset link.
- **FR-PWD-3:** The system shall validate the reset key and allow the user to set a new password through that link, without the key or the new password appearing in a query string.
- **FR-PWD-4:** A signed-in user shall be able to change their password by supplying the current password and a new password with confirmation; on success the system shall reissue tokens (see `FR-JWT-6`).
- **FR-PWD-5:** All new passwords shall be stored only as BCrypt hashes; identical plaintext passwords shall produce distinct hashes (per-password salt).

*Implementation status: **IMPLEMENTED** — `resetPassword` / `verifyPasswordURL` / `setNewPassword` / `updatePassword`; BCrypt storage; tokens reissued after a change.*

### 4.8 Authentication Event Logging (Audit)
**Description.** A complete internal record of authentication and account events across both authentication paths.
- **FR-AUDIT-1:** The system shall record an event for each of: login attempt, login success, login failure, federated login, password update, profile update, role change, account-settings change, MFA enrollment change, and profile-image change.
- **FR-AUDIT-2:** Each event shall capture event type, authentication method, timestamp, originating IP address, and parsed device information.
- **FR-AUDIT-3:** The system shall record login-attempt and failure events only for known accounts, so that the audit log does not itself become a means of user enumeration.
- **FR-AUDIT-4:** A user shall be able to retrieve their own events in reverse-chronological, paginated order.
- **FR-AUDIT-5:** Audit events shall be retained durably and shall not be editable through the application.

*Implementation status: **IMPLEMENTED** — `EventService` + `UserEvent` capture type, method, timestamp, IP and parsed device (yauaa); events are enumeration-safe and paginated. New event types cover federated login, TOTP lifecycle, session revocation, and token reuse.*

### 4.9 Administrative User Management
**Description.** An administrative dashboard for managing users within the administrator's authority scope.
- **FR-ADMIN-1:** The system shall provide a paginated, searchable list of users visible within the administrator's scope (all users for `ROLE_ADMIN` / `ROLE_APPLICATION_ADMIN`; same-organization users for `ROLE_ORGANIZATION_ADMIN`).
- **FR-ADMIN-2:** The system shall provide a single-user detail view showing profile fields, current role, account state, and that user's authentication event history.
- **FR-ADMIN-3:** An administrator shall be able to reassign another user's role, subject to scope checks, and the change shall be recorded as an audit event.
- **FR-ADMIN-4:** An administrator shall be able to change another user's account state (enable, disable, lock, unlock), subject to scope checks, recorded as an audit event.
- **FR-ADMIN-5:** Every administrative endpoint shall be protected by an explicit administrative authority; the administrative dashboard routes in the frontend shall be guarded so that non-administrative users cannot reach them.

*Implementation status: **IMPLEMENTED** — `AdminUserController` (`/admin/user/**`) with `@PreAuthorize` + URL rules; paginated, searchable `/users` dashboard with detail view, role reassignment, account-state changes, and audit history; `adminGuard` gates the frontend routes (FR-ADMIN-5).*

### 4.10 Risk-Based Controls and Analytics (Partial)
- **FR-EXT-1 (partial):** Brute-force rate limiting is implemented at the login endpoint: if five or more `LOGIN_ATTEMPT_FAILURE` events are recorded for an account within a 15-minute sliding window, further login attempts are rejected with a generic message until the window passes. This is enforced in `UserController.authenticate()` via `EventService.countRecentFailuresByEmail`. New-device / unusual-location step-up re-verification remains **(planned)**.
- **FR-EXT-2 (planned):** A login-analytics and reporting dashboard summarizing authentication trends is out of scope for this revision.

*Implementation status: **PARTIAL** — login rate-limiting (FR-EXT-1) is implemented in `UserController.authenticate()` via `EventService.countRecentFailuresByEmail`; new-device step-up re-verification and the analytics dashboard (FR-EXT-2) remain planned.*

### 4.11 Session and Device Management
**Description.** The user-visible surface of the server-tracked refresh sessions (see FR-JWT-7/8): an account security center listing every signed-in device.
- **FR-SES-1:** A user shall be able to view their active sessions, each showing the device description, IP address, sign-in time, last activity, and expiry, with the current session clearly indicated.
- **FR-SES-2:** A user shall be able to revoke any single session; a revoked session shall be unable to refresh, and its outstanding access token shall expire naturally within the access-token lifetime.
- **FR-SES-3:** A user shall be able to revoke all sessions other than the current one ("log out everywhere else") in a single action.
- **FR-SES-4:** Session revocations shall be recorded as audit events visible in the user's activity history.

*Implementation status: **IMPLEMENTED** — `SessionController` + the Account Security Center: per-device session list (device, IP, sign-in, last-active, expiry) with the current session badged, single-session revoke, and log-out-everywhere-else; revocations are audited (FR-SES-1..4).*

---

## 5. Logical Database Requirements
The system shall persist the following logical entities in a centralized relational database. Field lists are representative, not exhaustive; physical column naming uses explicit mappings to avoid identifier-quoting pitfalls in the data layer.

- **DB-1 Users** — id (PK), first name, last name, email (unique), password (BCrypt hash), phone, address, title, bio, `enabled`, `non_locked`, `using_mfa`, image URL, created-at.
- **DB-2 Roles** — id (PK), name (for example `ROLE_ADMIN`), permission (space- or comma-delimited authority string).
- **DB-3 User–Role assignment** — association of a user to a role (one active role per user in this revision).
- **DB-4 Organizations** — id (PK), name, status; required for organization-scoped administration.
- **DB-5 User–Organization membership** — association of users to organizations with active/inactive state, used by the organization-scope check.
- **DB-6 OAuth provider links** — user id (FK), provider name, provider subject identifier; uniquely links a federated identity to a local user.
- **DB-7 Account verifications** — user id (FK), single-use key; supports email activation of new accounts.
- **DB-8 Password-reset verifications** — user id (FK), single-use key, expiration timestamp.
- **DB-9 Two-factor verifications** — user id (FK), one-time code, expiration timestamp.
- **DB-10 Events** — id (PK), type, description; the catalog of event kinds.
- **DB-11 User events** — association of a user to an event occurrence with timestamp, device, and IP address; the audit trail.

Entities added by the authenticator-MFA and session-management features (§4.5, §4.11):

- **DB-15 TOTP credentials** — user id (unique FK), Base32 shared secret, confirmed flag with confirmation timestamp; a secret is inert until confirmed.
- **DB-16 TOTP recovery codes** — user id (FK), SHA-256 hash of a single-use code, used-at timestamp; plaintext is never persisted.
- **DB-17 MFA challenges** — user id (unique FK), opaque challenge, expiration; the server-side proof that a first factor succeeded, consumed by TOTP login verification.
- **DB-18 Refresh sessions** — user id (FK), session family, current token id (`jti`, unique), device, IP address, created/last-used/expires timestamps, revoked and superseded flags; retired rows are retained until expiry because reuse detection depends on recognizing them.

**DB-12:** Referential integrity shall be enforced by foreign keys. **DB-13:** All single-use verification keys shall be unique and, where applicable, shall carry an expiration that the system checks before honoring them. **DB-14:** Schema changes shall be made through a single, version-controlled, idempotent SQL script (`schema.sql`, using `CREATE TABLE IF NOT EXISTS` and equivalent guards) rather than ad-hoc manual edits, so that applying the script to any environment converges that environment to the same schema.

*Implementation status: **IMPLEMENTED** — All entities exist: DB-1..3 and DB-7..11 from the baseline, DB-4..6 and DB-15..18 (TOTP credentials, recovery codes, MFA challenges, refresh sessions) all defined in the idempotent `schema.sql`. Schema evolution is the version-controlled `schema.sql` script (DB-14).*

---

## 6. Design Constraints
- **CON-1:** The frontend shall be Angular 21 using standalone components, the new control-flow syntax, signal-based state where appropriate, and Bootstrap 5 for layout.
- **CON-2:** The backend shall be Java 21 on Spring Boot 4 with Spring Security 7, built with Maven (via the Maven wrapper), using the Jakarta (not `javax`) namespaces.
- **CON-3:** Request authentication shall be stateless (`SessionCreationPolicy.STATELESS`): no server-side HTTP session and no per-request database lookup for authorization. The one deliberate piece of server-held session state is the refresh-session store (DB-18), consulted only at refresh time to provide rotation and revocation (§4.4) — never on ordinary request authorization. (The OAuth2 handshake additionally uses a transient container session to hold the protocol `state` parameter mid-redirect; no security context is ever stored in it.)
- **CON-4:** Passwords shall be hashed with BCrypt; no reversible credential storage is permitted.
- **CON-5:** OAuth2 protocol handling shall use the Spring Security OAuth2 Client starter rather than a hand-rolled implementation, so that the bespoke work is limited to the token-exchange handler.
- **CON-6:** The application shall be containerizable as a multi-stage Docker image that runs as a non-root user on a minimal JRE base.
- **CON-7:** Secrets and environment-specific configuration shall be injected through environment variables and shall not be committed to source control.
- **CON-8:** Data-access shall use JDBC repositories with explicit SQL and explicit column mapping.

*Implementation status: **IMPLEMENTED** — Angular 21 standalone, Java 21 / Spring Boot 4 / Spring Security 7, stateless request auth with the deliberate refresh-session store (CON-3 as amended), BCrypt, OAuth2 client starter (CON-5), multi-stage non-root Docker, env-injected secrets, JDBC repositories.*

---

## 7. Standards Compliance
- **STD-1:** Federated authentication shall conform to the OAuth 2.0 Authorization Code flow (RFC 6749) and OpenID Connect Core 1.0.
- **STD-2:** Tokens shall conform to the JWT specification (RFC 7519) and shall be signed (HMAC-SHA-512).
- **STD-3:** The system shall follow OWASP guidance: protection against the OWASP Top 10, alignment with the ASVS for authentication and session management, and explicit defense against automated enumeration (OWASP OAT-007) by never disclosing account existence through error messages or timing-obvious branches.
- **STD-4:** The system shall be designed to support data-protection obligations (for example GDPR and CCPA) by maintaining a complete, durable audit trail of access-control decisions and by minimizing the personal data collected from federated providers.
- **STD-5:** This requirements document follows the structure of IEEE Std 830-1998.
- **STD-6:** Build dependencies shall be scanned for known vulnerabilities (OWASP Dependency-Check and/or Snyk) as part of the build pipeline.

*Implementation status: **IMPLEMENTED** — OAuth2/OIDC Authorization Code flow via the framework client (STD-1), JWT/HMAC-512 (STD-2), OWASP anti-enumeration (STD-3), OWASP Dependency-Check plugin (STD-6), IEEE-830 structure (STD-5).*

---

## 8. Software System Attributes (Nonfunctional Requirements)

### 8.1 Reliability
- **NFR-REL-1:** Authentication and authorization decisions shall be deterministic for a given account state and token; identical inputs shall yield identical decisions.
- **NFR-REL-2:** A failure of an optional external service (SMS or email) shall be reported to the user with an actionable message and shall not corrupt account state or leave the user in an indeterminate session.
- **NFR-REL-3:** Database writes that span related rows (for example creating a user and its role assignment) shall be performed so that a partial failure does not leave an account in an unusable state.

### 8.2 Availability
- **NFR-AVAIL-1:** The system shall be deployable in a configuration with no server-side session affinity, so that any instance can serve any request and instances can be added or replaced without disrupting signed-in users.
- **NFR-AVAIL-2:** The container image shall expose a health check so that an orchestrator can detect and replace an unhealthy instance.
- **NFR-AVAIL-3:** The system shall depend on no in-memory session state for correctness, allowing restarts without forcing re-authentication of users holding valid tokens.

### 8.3 Security
- **NFR-SEC-1:** All traffic shall be encrypted in transit (HTTPS); HSTS shall be enforced.
- **NFR-SEC-2:** Credentials shall be stored only as BCrypt hashes with per-password salt.
- **NFR-SEC-3:** Access tokens shall be short-lived (default 30 minutes) to bound the window of a leaked token; refresh tokens shall be server-tracked, rotated on every use with reuse detection, and revocable individually, in bulk ("log out everywhere else"), and through password change.
- **NFR-SEC-4:** Authorization shall be enforced server-side on every protected request; the frontend's route guards are a usability aid and shall not be relied upon for security.
- **NFR-SEC-5:** CSRF protection is not required for the stateless token API, and HTTP Basic shall be disabled; CORS shall whitelist only configured origins.
- **NFR-SEC-6:** The system shall send anti-clickjacking and anti-MIME-sniffing headers on all responses.
- **NFR-SEC-7:** No error message, response code, or response-timing difference shall reveal whether a given email or identifier is registered (anti-enumeration).
- **NFR-SEC-8:** Privilege escalation shall be impossible through self-service; role changes shall require an administrative authority and shall be audited.
- **NFR-SEC-9:** Federated sign-in shall persist only the minimum provider identity data required to re-link an account.

### 8.4 Maintainability
- **NFR-MAINT-1:** The in-house authentication core, federated login, RBAC enforcement, and audit logging shall be developed as independent modules that can be tested in isolation before end-to-end integration.
- **NFR-MAINT-2:** The codebase shall use current framework idioms (standalone Angular components and signals; constructor injection and records for DTOs on the backend) so that it remains aligned with supported versions.
- **NFR-MAINT-3:** Schema evolution shall be captured in a single, version-controlled, idempotent schema script (`schema.sql`) rather than ad-hoc manual database edits, so that the schema definition is reviewable in source control and reproducible across environments.
- **NFR-MAINT-4:** Public types and non-trivial methods shall carry documentation explaining their role within the wider system, not merely restating the signature.

### 8.5 Portability
- **NFR-PORT-1:** The system shall run unchanged against a local MySQL instance, a managed Aiven MySQL instance, or AWS RDS, selected by configuration.
- **NFR-PORT-2:** The system shall build and run on any platform providing Java 21 and a container runtime; no operating-system-specific facilities shall be required at runtime.
- **NFR-PORT-3:** All environment-specific values shall be externalized to configuration so that promoting a build between environments requires no code change.

### 8.6 Usability
- **NFR-USE-1:** Primary flows (register, verify, log in, reset password) shall each be completable without reference to external documentation.
- **NFR-USE-2:** Token refresh shall be transparent: a user with a valid refresh token shall not be interrupted when an access token expires mid-session.
- **NFR-USE-3:** Federated sign-in shall let a user authenticate without creating or remembering a separate password for the system.

### 8.7 Performance
- **NFR-PERF-1:** Under nominal single-instance load, an authentication request (excluding external provider or SMS latency) shall complete server-side within 500 ms at the 95th percentile.
- **NFR-PERF-2:** Authorization on a protected request shall not require a database lookup in the common case, because authorities are carried in the validated token.
- **NFR-PERF-3:** Listing and audit-log views shall be paginated (default page size 10) so that response size and query cost remain bounded as data grows.

*Implementation status: **IMPLEMENTED** — Stateless/portable/secure attributes hold by design; the version-controlled `schema.sql` satisfies NFR-MAINT-3. Automated test coverage remains the thinnest attribute — see Appendix C.*

---

## 9. Other Requirements
- **OTH-1:** The system shall ship with seed data sufficient to demonstrate every role and the administrative dashboard (representative users across roles, with audit history).
- **OTH-2:** Development-only conveniences (for example simulated latency on selected endpoints and local-filesystem image storage) shall be clearly marked and shall be replaced or disabled before any production deployment.
- **OTH-3:** Time-limited tokens (account verification, password reset, MFA code) shall have configurable lifetimes with secure defaults.
- **OTH-4:** The build pipeline shall produce both a runnable JAR and a Docker image from the same source without manual steps.

*The instructor's rubric lists "Other requirements" as open-ended; this section will be extended as additional expectations are identified during development.*

---

## Appendix A: Glossary
- **CIAM** — Customer Identity and Access Management: identity management for the customers/users a product serves, as distinct from workforce identity for employees.
- **JWT** — JSON Web Token: a signed, self-contained token carrying identity and authority claims.
- **HMAC-SHA-512** — the keyed hash algorithm used to sign tokens.
- **BCrypt** — an adaptive, salted password-hashing function.
- **OAuth 2.0 / OIDC** — the delegated-authorization framework and the identity layer built on top of it, used for federated sign-in.
- **RBAC** — Role-Based Access Control: authorization driven by a user's role and its permissions.
- **Federated identity** — an identity asserted by an external provider (Google, GitHub, Microsoft) and trusted by the system.
- **Token exchange point** — the single place where any successful authentication, in-house or federated, results in the system issuing its own JWT.
- **Enumeration** — discovering which accounts exist by observing differences in system responses; the system is designed to prevent it.

## Appendix B: Requirements Traceability Summary
Each requirement identifier (FR-*, NFR-*, EIR-*, DB-*, CON-*, STD-*, OTH-*) is intended to be traced forward to a design element, a code module, and a test case. A traceability matrix will be maintained alongside this document as implementation proceeds; the proposal's four development phases map onto the functional groups as follows: Phase 1 → 4.1, 4.2, 4.4; Phase 2 → 4.6, 4.7, 4.1; Phase 3 → 4.3, 4.5, 4.4; Phase 4 → 4.8, 4.9.

---

## Appendix C: Implementation Status Summary

This appendix was produced by a source-level review of the repository (Spring Boot backend and Angular frontend) as of the document date. It records, for each requirement group, whether the capability is *implemented*, *partially implemented*, or *planned*, with the code evidence behind each verdict. Where it disagrees with a "shall" statement in the body, the discrepancy is listed at the end of this appendix.

| **Requirement Area** | **Status** | **Evidence / Notes** |
|----|----|----|
| Sec. 3 — External Interfaces | **IMPLEMENTED** | UI incl. Security Center, email, REST envelope, CORS, HSTS, security headers; env-conditional OAuth2 providers (EIR-SW-1); schema defined by the idempotent `schema.sql` (EIR-SW-4). SMS dispatch (EIR-SW-2) is a dev stub — see C.1. |
| Sec. 4.1 — Registration & Verification | **IMPLEMENTED** | `UserController.saveUser` / `verifyAccount`; BCrypt; disabled until verified. |
| Sec. 4.2 — In-house Authentication | **IMPLEMENTED** | `login()` via `AuthenticationManager` + BCrypt; generic failure; enumeration-safe events. |
| Sec. 4.3 — Federated (OAuth2 / OIDC) | **IMPLEMENTED** | `spring-security-oauth2-client` (CON-5); `OAuth2LoginSuccessHandler` token-exchange point; `oauthproviderlinks` table; account-state + MFA policy parity for federated logins. |
| Sec. 4.4 — JWT Issuance / Refresh / Rotation | **IMPLEMENTED** | `TokenProvider` HMAC-512; 30-min/5-day; `passwordChangedAt` invalidation; `SessionService` rotation with family-wide reuse revocation (FR-JWT-7/8) over the `refreshsessions` table; frontend refresh-lock interceptor stores the rotated pair. |
| Sec. 4.5 — Multi-Factor Authentication | **IMPLEMENTED** | TOTP (RFC 6238, in-house `TotpUtils`): QR enrollment + confirm, hashed recovery codes, challenge-bound login verification (FR-MFA-4/5). SMS path built; Twilio dispatch stubbed in dev. |
| Sec. 4.6 — RBAC (FR-RBAC-1..4) | **IMPLEMENTED** | Permission authorities, URL + method security, custom 401/403 handlers; FR-RBAC-4 closed (self-service role endpoint removed; admin-only reassignment refuses self-targeting). |
| Sec. 4.6 — Organization scope (FR-ORG-1..3) | **IMPLEMENTED** | `organizations` / `userorganizations`; `OrganizationServiceImpl` shared-active-membership predicate; out-of-scope access → 403; `APPLICATION_ADMIN` bypasses scope. |
| Sec. 4.7 — Password Management | **IMPLEMENTED** | reset / verify / setNew / updatePassword; BCrypt; token reissue; all sessions revoked on change. |
| Sec. 4.8 — Audit Logging | **IMPLEMENTED** | `EventService` / `UserEvent`; type, method, timestamp, IP, device (yauaa); enumeration-safe; paginated; federated/TOTP/session/reuse event types. |
| Sec. 4.9 — Administrative User Management | **IMPLEMENTED** | `AdminUserController` (`/admin/user/**`) + `/users` dashboard (list, search, detail, role + state changes, audit history); `adminGuard` on routes. |
| Sec. 4.10 — Risk-Based Controls / Analytics | **PARTIAL** | Login rate-limiting (FR-EXT-1) implemented in `UserController.authenticate()` via `EventService.countRecentFailuresByEmail`; new-device step-up and the analytics dashboard (FR-EXT-2) remain planned. |
| Sec. 4.11 — Session & Device Management | **IMPLEMENTED** | `SessionController` + Security Center: device list with current-session badge, single revoke, log-out-everywhere-else; audited (FR-SES-1..4). |
| Sec. 5 — Logical Database | **IMPLEMENTED** | DB-1..11 plus DB-15..18 (TOTP credentials/recovery codes, MFA challenges, refresh sessions) all present, defined in the idempotent `schema.sql` (DB-14). |
| Sec. 6 — Design Constraints | **IMPLEMENTED** | Angular 21, Java 21 / SB 4 / SS 7, stateless request auth + deliberate refresh-session store (CON-3 as amended), BCrypt, OAuth2 starter (CON-5), non-root Docker, env config, JDBC. |
| Sec. 7 — Standards Compliance | **IMPLEMENTED** | OAuth2/OIDC via framework client (STD-1), JWT/HMAC-512 (STD-2), anti-enumeration (STD-3), Dependency-Check (STD-6), IEEE-830 (STD-5). |
| Sec. 8 — Software System Attributes | **IMPLEMENTED** | Stateless/portable/secure by design; NFR-MAINT-3 satisfied by the idempotent `schema.sql`. Automated test coverage is the remaining soft spot — see C.1. |

### C.1 Notable Discrepancies Between Spec and Code

These are points where the requirement text currently overstates what the code does. None blocks the document from being used for review; each is a small wording or scoping fix.

- **SMS delivery is a development stub (EIR-SW-2, FR-MFA-2).** The Twilio API call is commented out to avoid charges during development; the one-time code is written to the application log instead, so the SMS flow is demonstrable but not production-wired. The authenticator-app TOTP factor (FR-MFA-4) is fully operational and is the production-grade second factor; restoring live SMS requires only Twilio credentials and re-enabling the call in `SMSUtils`.
- **Federated provider name is not on the audit row (FR-FED-5).** Federated sign-ins are recorded with the distinct `FEDERATED_LOGIN` event type, satisfying the method-distinction requirement, but which provider (Google vs GitHub vs Microsoft) is captured in server logs only — the `userevents` table has no detail column. A small future change adding one column would complete the requirement.
- **Frontend API base URL is hardcoded (NFR-PORT-3).** The backend externalizes all environment-specific values, but the Angular `UserService` pins the API origin to `http://localhost:8080`. Promoting a build between environments currently requires a frontend change; an environment-driven base URL is the known fix.
- **Test traceability is unproven (Appendix B, NFR-MAINT-1).** Requirements trace cleanly to design elements and code modules, but the test-case leg of the traceability matrix is future work — the repository carries a single context-load test and no frontend specs. The security-critical paths (rotation/reuse, TOTP challenge binding, org scoping) are the priority candidates for coverage.

---

*End of document (draft 0.3).*
