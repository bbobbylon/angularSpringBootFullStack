# Phase 2 Proposals

**Version:** 1.0
**Last Updated:** 2026-07-22
**Author:** Bobby Oliver
**Status:** Draft — proposals only (nothing here is implemented)

## Overview

This document captures enhancements deliberately deferred beyond the current revision of the
[Software Requirements Specification](assignments/software_requirements_specification.md). Each
proposal states the motivation, a concrete design sketch, the code/schema it would touch, and the
open questions to resolve before implementation. Nothing here is built yet — these are candidates for
a future SRS revision (they would slot alongside the existing **(planned)** items FR-EXT-1 step-up
and FR-EXT-2 analytics).

## Table of Contents
- [P2-1: User Type Classification](#p2-1-user-type-classification)
- [P2-2: Batch Upload for Customers & Invoices](#p2-2-batch-upload-for-customers--invoices)
- [P2-3: Machine-to-Machine Admin API Access](#p2-3-machine-to-machine-admin-api-access)
- [Sequencing & Dependencies](#sequencing--dependencies)

---

## P2-1: User Type Classification

**Motivation.** Administrators currently see a user's role and account state, but not *where the
identity comes from*. In a real deployment an admin needs to distinguish an internal employee from a
self-registered external user, a federated (social/enterprise SSO) account, or an Azure AD **B2B
guest**. That distinction drives support decisions, security posture, and reporting.

**Proposed model.** Introduce a `UserType` enum and classify every account:

| Value | Meaning |
|-------|---------|
| `INTERNAL` | Belongs to the operating organization — determined by a **configurable email-domain allowlist**, not hard-coded. |
| `EXTERNAL` | Self-registered in-house account that is not internal. |
| `FEDERATED` | Created via an OAuth2/OIDC provider find-or-create (Google/GitHub/Microsoft consumer). |
| `AZURE_B2B` | An Azure AD B2B **guest** identity (distinct from a consumer Microsoft login). |

**The key requirement — "internal" must be reconfigurable over time.** Today `@yahoo.com` might be
internal; next quarter `@outlook.com` might be. So internal membership must NOT be baked into code or
frozen at signup. Two design options:

- **Option A — derive on read (recommended).** Store only the *origin* facts we know for certain at
  creation (`FEDERATED` / `AZURE_B2B` / self-registered), and compute `INTERNAL` vs `EXTERNAL` at
  read time by matching the user's email domain against a live allowlist
  (`app.internal-domains`, an env-driven, comma-separated list). Changing the allowlist instantly
  re-classifies everyone with no data migration. Trade-off: a tiny per-read computation, and
  "internal-ness" is not queryable in raw SQL without joining the config.
- **Option B — store a column, recompute on change.** Persist `user_type` in `users` and re-run a
  reclassification job whenever the allowlist changes. Trade-off: the stored value can drift from the
  config; needs a batch reclassifier and an admin trigger.

Recommendation: **Option A** for `INTERNAL/EXTERNAL` (config-driven, always fresh), combined with a
stored, immutable `origin` fact for `FEDERATED/AZURE_B2B` (which never changes for a given account).
`getUserType()` then returns the federated/B2B origin if present, else INTERNAL/EXTERNAL by domain.

**Touch points.**
- New `enumeration/UserType.java`.
- `application*.yml` / `.env`: `app.internal-domains=company.com,contoso.com` (env `INTERNAL_DOMAINS`).
- `User`/`UserDTO`: add an `origin` field (nullable; set by the OAuth2 find-or-create path and any
  future B2B path) + a derived `userType` exposed on the DTO.
- `OAuth2LoginSuccessHandler` / `FederatedIdentityService`: stamp `FEDERATED` (and detect
  `AZURE_B2B` from the Azure token — B2B guests carry distinguishing `idp`/`tid` claims).
- Admin dashboard: surface the classification as a badge on the user list and detail views.
- `schema.sql`: add nullable `users.origin` (idempotent guarded ALTER, same pattern as
  `userevents.detail`).

**Open questions.** How is `AZURE_B2B` reliably detected from the token (guest `tid` ≠ home `tid`,
or the `acct`/`idp` claim)? Should `INTERNAL` grant any implicit authority, or is it purely
informational? Do we ever want to *filter* the admin directory by type (argues for a queryable
stored column, i.e. Option B for that field)?

---

## P2-2: Batch Upload for Customers & Invoices

**Motivation.** Onboarding a real client means importing an existing book of customers and invoices.
Hand-entering them one at a time through the UI does not scale; a CSV/Excel import is the expected
"nice touch."

**Proposed design.**
- New endpoints, e.g. `POST /customer/import` and `POST /customer/{id}/invoices/import`, accepting
  `multipart/form-data` with a CSV (and optionally `.xlsx`).
- A downloadable **template** (`GET /customer/import/template`) so users start from the exact column
  shape the parser expects.
- **Per-row validation with a partial-success report**: parse every row, apply `@Valid`-equivalent
  checks, insert the valid ones, and return a structured result — `{ imported: N, failed: [{ row,
  reason }] }` — so one bad row never aborts the whole file. This mirrors the existing "vague to the
  client, precise in the report" philosophy.
- **Transaction posture:** per-row (or per-batch chunk) commits, not one giant transaction, so a
  failure at row 4,000 doesn't roll back the first 3,999. Idempotency via a dedupe key (customer
  email / invoice number) to make re-uploads safe.
- **Large files:** for anything beyond a few thousand rows, process asynchronously (return a job id;
  poll for status) so the request doesn't block. Streaming parse (Apache Commons CSV / a SAX-style
  `.xlsx` reader) to avoid loading the whole file into memory.

**Touch points.** New import controller(s) + an `ImportService`; a CSV/Excel parsing dependency
(Apache Commons CSV; Apache POI only if `.xlsx` is required); reuse of the existing
`CustomerService`/`InvoiceService` create paths for per-row persistence; a new
`import_jobs` table if async status tracking is added. Authority: gate behind
`UPDATE:CUSTOMER` (and `UPDATE:INVOICE` where applicable).

**Open questions.** CSV only, or Excel too (POI is a heavy dependency)? Synchronous cap (e.g. ≤2,000
rows inline, larger → async)? How aggressively to dedupe (skip / update / error on an existing
customer email)?

---

## P2-3: Machine-to-Machine Admin API Access

**Motivation.** Today every protected call needs a JWT minted by an **interactive** login (password
or federated). That blocks legitimate non-interactive callers: an admin running a script, a CI/CD
pipeline seeding or verifying an environment, monitoring, or another backend service. They should be
able to authenticate as a **service principal** without a browser login.

**Proposed design (two complementary options).**
- **Option A — API keys.** Issue long-lived, revocable API keys scoped to a service account and a set
  of authorities. A new filter (ahead of / alongside `CustomAuthFilter`) recognizes an
  `X-API-Key` header, looks up the hashed key, and installs an `Authentication` with the key's
  authorities — reusing the *exact* authority-string model the JWT path already uses, so every
  existing `hasAnyAuthority(...)` / `@PreAuthorize` rule applies unchanged. Store only key **hashes**
  (like the TOTP recovery codes), support rotation and revocation, and record usage as audit events.
- **Option B — OAuth2 client-credentials grant.** Add a `POST /oauth/token` (grant_type=
  `client_credentials`) that exchanges a client id/secret for a short-lived access token carrying the
  service account's authorities. More standards-aligned and better for CI/CD secret managers; slightly
  more machinery (a token endpoint + client registry).

Both converge on the same downstream contract: a request arrives already carrying authorities, and
the existing authorization layer decides. That is what makes this tractable rather than a rewrite —
**the RBAC core does not change; only a new *authentication* front door is added.**

**Why it's deferred.** It requires deeper Spring Security work (a second authentication mechanism
coexisting cleanly with the stateless JWT filter, without weakening the `PUBLIC_URLS` ↔
`PUBLIC_ROUTES` lockstep), a service-account/credential store, and careful secret handling in CI/CD
(keys in a secret manager, never in the repo). It also interacts with rate limiting and audit.

**Touch points.** New `filter/ApiKeyAuthFilter` (or a client-credentials token endpoint);
`service_accounts` + `api_keys` (hashed) tables; `SecurityConfig` wiring so the new filter feeds the
same `SecurityContext` the authorization rules read; audit event types for key
issued/used/revoked; CI/CD secret wiring (`.env` / GitHub Actions secrets / AWS Secrets Manager,
consistent with the existing deploy setup).

**Open questions.** API keys vs client-credentials (or both — keys for humans/scripts,
client-credentials for services)? Per-key authority scoping granularity? Token/key TTL and rotation
policy? How do these principals appear in the audit trail and the Security Center?

---

## Sequencing & Dependencies

| Proposal | Rough effort | Risk | Depends on |
|----------|-------------|------|------------|
| P2-1 User Type | Small–Medium | Low | none (additive column + config) |
| P2-2 Batch Upload | Medium | Medium (parsing, partial-failure UX) | none |
| P2-3 M2M API Access | Large | Higher (security-sensitive, new auth path) | benefits from mature audit + rate limiting (already present) |

Suggested order: **P2-1 → P2-2 → P2-3.** P2-1 is the smallest and lowest-risk (pure additive
classification). P2-2 is self-contained product value. P2-3 is the deepest and most security-
sensitive, best done last with dedicated review — and it is the one most tightly coupled to the CI/CD
maturation already underway.

## Related Documents
- [Software Requirements Specification](assignments/software_requirements_specification.md) — the current, implemented requirements baseline (FR-EXT-1/2 planned items live in §4.10).
- [Project Instructions](CLAUDE.md) — backend blueprint and conventions any Phase 2 work must follow.
