# [Your title here]

> Working draft — **written by you.** The old `literature_review.md` is parked as a research map only.
> Coaching prompts are in _italic blockquotes_; delete them as you fill each section. Write in your own words.

**Author:** Bobby Oliver
**Course / Section:** _[fill in]_
**Date:** _[fill in — the extended deadline drives this]_

---

## Abstract
> _Write this LAST. ~150–200 words. After the paper exists, summarize: the problem (identity is the
> attack surface), your lens (build vs. buy), what you compared, and your one finding (the SMB gap +
> SecureCapita as an answer). If you can't summarize it in your own words, that's the signal a section
> isn't really yours yet._

[your draft]

---

## 1. Introduction & Problem Statement
> _Answer in your own words, no notes:_
> - _Why did security move from "defend the network" to "verify the identity"? (the perimeter is gone)_
> - _What is CIAM, and how is it different from workforce IAM?_
> - _State your thesis: this review surveys CIAM through a build-vs-buy lens; SecureCapita is the in-house end._
> - _One sentence roadmap of the paper._
> _Relevant sources: zero-trust [NIST SP 800-207]._

[your draft]

---

## 2. Background & Importance
> _Why is identity THE attack surface now? Use the breach-cost data as the hook (avg cost, % from stolen
> credentials, time-to-contain) — but explain in your words why a stolen credential is so dangerous
> (the attacker IS the user; no firewall to break). End by setting up the "how do you obtain CIAM" question._
> _Relevant sources: IBM Cost of a Data Breach 2024._

[your draft]

---

## 3. Theoretical Foundations
> _The citation-dense section. For each, explain it well enough to teach it back:_
> - _Zero-trust architecture (never trust / always verify / continuous re-evaluation)_
> - _OAuth 2.0 vs. OpenID Connect (authorization vs. the identity layer on top)_
> - _Stateless JWT vs. stateful sessions — the scalability-vs-revocability tension (this is YOUR hybrid hinge)_
> - _MFA / TOTP / passwordless (FIDO2/WebAuthn) — ties to your project's TOTP work_
> - _(optional) IAM metrics & federated identity_
> _Sources: NIST 800-207, ZTA survey, OAuth formal analysis, RFC 6749/7519/6238, OIDC Core, FIDO2 usability._

[your draft]

---

## 4. Existing CIAM Systems  ← THE CORE (most of the grade)
> _Open with the build-vs-buy spectrum. Then the comparison table is the centerpiece._
> _Rule: every product gets ≥2 pros, ≥2 cons, ≥1 citation. After the table, a couple sentences per
> product expanding its strongest pro + most material con. Make sure YOU can say why each con matters._
> _Products to cover: Okta/Auth0, Microsoft Entra External ID, Ping/ForgeRock, IBM Security Verify,
> AWS Cognito, Keycloak, and SecureCapita (yours) as the final row._

| System | Type | Pros | Cons | Refs |
|---|---|---|---|---|
| | | | | |

[your draft — discussion under the table]

---

## 5. Different Approaches: Build vs. Buy
> _Lay out the spectrum; place SecureCapita at the in-house end. Keycloak is your closest analog —
> what self-hosting buys (control, no per-MAU fee) and costs (you own ops/HA/scaling). Then explain
> SecureCapita's hybrid stance in YOUR words. Include the honest counter-argument: the market is moving
> AWAY from homegrown CIAM — then say when building still wins (cost control, data sovereignty, niche
> needs, learning value). The honesty is what earns the grade._

[your draft]

---

## 6. Economic Impact & Marketability
> _Market size + growth (cite the forecasts to show a range). Then the key argument: per-MAU pricing is
> a "tax on growth" for small businesses → that's the underserved gap a lightweight self-hostable CIAM
> fills. Tie back to breach-cost as risk-avoidance ROI. Position SecureCapita as a small-business basis._

[your draft]

---

## 7. Conclusion & Gap Analysis
> _Synthesize: what does the whole survey reveal? (a gap for affordable, sovereign, lightweight CIAM at
> small scale). How does SecureCapita answer it? Be modest — you're NOT claiming build beats buy in
> general, only inside a narrow window. Future work = your project roadmap (RBAC, TOTP, sessions/devices,
> risk-based step-up)._

[your draft]

---

## References
> _IEEE numbered style. Build this as you go — and READ (or at least open) every source before you cite it.
> The old draft has a vetted list of 20 you can pull from, but verify each one yourself; never cite
> something you haven't looked at. A citation you can't speak to is a weak spot a grader will find._

[your reference list]
