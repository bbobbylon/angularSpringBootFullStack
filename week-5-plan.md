# Week 5 — Execution Plan (week of 2026-06-18)

> **Theme: integrate, verify, and harden the security-critical paths — then one high-payoff
> demo feature.** This is the near-term execution slice off the canonical roadmap in
> [`plan.md`](plan.md) (M0–M7). Where this file and `plan.md`'s "known debt" list disagree,
> *this file is the more recent ground truth* (verified against the code 2026-06-18).

---

## 0. Where we actually are (post prod-readiness push)

Last push (`8c42c3e`, branch `MastersProjectSRSImpl`, ahead of `master`) landed:
- Circular-placeholder config bug fixed (dev → literal defaults, prod → direct env reads / fail-fast); 4 docs synced.
- Prod JPA hardening: `ddl-auto: validate` + `show-sql: false`; the four JPA tables added to `schema.sql` with Hibernate-generated DDL; `JpaSchemaSyncTest` drift guard.
- Login anti-enumeration regression test; SRS bumped to rev 0.3.
- Test suite is now **6 suites / 14 tests, all green**; `contextLoads` boots the full context end-to-end.

**Corrections to `plan.md`'s debt list (verified done, do NOT re-do):**
- ✅ `@Valid` is on `register`, `createCustomer`, `createInvoice`, `login`, password/settings/TOTP forms, etc.
- ✅ Frontend API base is environment-driven (`environment.apiUrl`; prod = relative `''`). Not hardcoded.

**Still genuinely open (the inputs to this week):**
- The work above is on a feature branch, **not merged to `master`**.
- A real **prod-profile boot with `validate` against a live MySQL** was never exercised (it was blocked by the now-fixed circular bug). This is our one outstanding verification gap.
- **Security-critical paths still lack dedicated tests**: refresh rotation + reuse-detection, TOTP challenge binding, org-scoped access.
- Roadmap: **M2 (security dashboard) not started**; **M1 polish** (route transitions, skeleton loaders) unfinished; **M6 (risk step-up)** partial.
- Minor debt: SMS dispatch stubbed (acceptable — TOTP is the real factor); two JWT libs on classpath (`jjwt` + `java-jwt`).

---

## 1. Priorities this week

| # | Item | Why now | Maps to | Done when |
|---|------|---------|---------|-----------|
| **P0** | **Open PR `MastersProjectSRSImpl` → `master` and merge** | The prod-readiness work is finished, verified, and pushed; it should stop living on a branch. | — | PR opened, green build, merged; `master` contains `8c42c3e`. |
| **P0** | **Real prod-profile boot with `validate`** | Closes the single verification we explicitly could not do. Proves `schema.sql` actually satisfies Hibernate `validate` against MySQL, not just the offline DDL match. | Prod hardening | App starts under `SPRING_ACTIVE_PROFILES=prod` against a MySQL seeded only from `schema.sql`, with no `validate` errors. |
| **P1** | **Tests for the 3 security-critical paths** | Stated project priority; we now have the harness (`standaloneSetup` + Mockito, `JpaSchemaSyncTest` tooling) to do it cheaply. | M5/M6, FR-ORG | Each path has a hermetic test (see §2). Suite stays green. |
| **P1** | **M2 — Security / activity dashboard (first cut)** | Highest demo payoff for the presentation, and runs mostly on `UserEvent` data we already collect — minimal backend. | M2 | Home shows ≥3 live tiles (logins, active sessions, MFA %) + an audit feed, all token-themed. |
| **P2** | **Finish M1 polish** | Highest-traffic surface; cheap once tokens exist. | M1 | Route transitions via `@angular/animations`; skeleton loaders replace the blank `LOADING` state. |
| **P2 / track** | **Consolidate JWT libraries; confirm SMS stub posture** | Reduce classpath ambiguity; document SMS as a deliberate stub, not a bug. | Debt | Decision recorded (keep one lib or document why both); SMS stub noted in SRS/README. |

---

## 2. Detail — the P1 test set (security-critical)

Reuse the established hermetic style (no Spring context / DB) wherever possible; reserve a real
DB only where the behavior is inherently stateful.

1. **Refresh-token rotation + reuse-detection** (`SessionService` — the single token-issuance seam).
   - A normal refresh rotates the token and keeps the family valid.
   - **Replaying a consumed refresh token invalidates the whole family** (the zero-trust payoff).
   - This is the highest-value test: it's the core argument of the "hybrid" model in `plan.md` §2.
2. **TOTP challenge binding** (`TotpService` / `TotpController`).
   - A valid RFC-6238 code only verifies **against the challenge it was issued for** — a code valid in the abstract must not satisfy a different/forged challenge.
3. **Org-scoped access** (`FR-ORG`).
   - `ROLE_ORGANIZATION_ADMIN` managing a same-org user → 200; a user **outside their org → 403**, with no information leak distinguishing "not allowed" from "doesn't exist."

**Acceptance:** all three added, suite green, each with Javadoc explaining the rule it locks in and
the FR/NFR it guards (consistent with `UserControllerLoginEnumerationTest`).

---

## 3. Definition of done for the week

- `master` contains the prod-readiness work (P0 merge).
- Prod profile demonstrably boots with `validate` against a `schema.sql`-only MySQL (P0).
- ≥3 new security-path tests; suite green (P1).
- M2 dashboard first cut is demoable (P1).
- This plan's status markers updated as items land; `plan.md`'s stale debt list reconciled.

---

## 4. Decisions needed / risks

- **Submission date.** `plan.md` still flags the passed 2026-05-26 date as unconfirmed. The P1/P2
  split assumes "integrate + verify + test first, visible feature second." If the demo is imminent,
  **flip P1 tests and P1 dashboard** — lead with M2 for the presentation, backfill tests after.
- **Prod MySQL for P0 verification.** Needs a throwaway MySQL (local container is fine). If none is
  available this week, the offline `JpaSchemaSyncTest` remains the standing guarantee and P0-verify
  slips — but it stays the top gap to close.
- **Scope vs. time.** P0 + the rotation/reuse test alone make a strong, honest "we hardened and
  proved the security core" story. M2 is the stretch that makes it *look* as good as it is.

---

*Near-term slice of [`plan.md`](plan.md). Update markers as items land; promote anything that
outlives the week back into `plan.md`'s roadmap.*
