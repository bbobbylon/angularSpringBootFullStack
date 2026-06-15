# Final Presentation — Video Script & Recording Guide
### SecureCapita — presenting the slides with a live demo

| | |
|---|---|
| **Presenter** | [Your Name] |
| **Course** | [Course Code / Title] |
| **Format** | Narrated slide presentation + live demo of 1–2 scenarios |
| **Slides** | [`final-presentation-slides.md`](final-presentation-slides.md) (Marp → export to PPTX/PDF) |
| **Suggested length** | 12–18 minutes (confirm your course's limit) |

> This is the script/runbook for recording the final presentation. Presentation **organization and style count** — rehearse, keep slides clean, and let the demo prove the project works.

---

## Before you record

- Export the deck to PPTX/PDF (Marp) and open it in presenter/full-screen mode.
- Have the **running app** in a second window, logged out, DB seeded, authenticator ready.
- Record at 1080p with a good mic. Do a short test.
- Decide your **two demo scenarios** in advance (recommended: *Login + MFA/Security Center* and *Admin RBAC*).

---

## Run sheet (slide → talking point → time)

| Slide(s) | Talking point | Time |
|----------|---------------|------|
| Title | Greeting, your name, project name | 0:30 |
| Problem | Why secure identity is hard; what you set out to solve | 1:00 |
| Objectives | The 5 objectives, briefly | 1:00 |
| Background | CIAM, zero-trust, stateless vs stateful (tie to lit review) | 1:30 |
| Requirements | SRS highlights (don't read every line) | 1:00 |
| Architecture | Walk the diagram; name the 4+1 views | 1:30 |
| Security model | The differentiator — rotation + reuse detection + MFA | 1:30 |
| Tech stack | 30-second skim | 0:30 |
| Implementation highlights | 3–4 things you're proud of | 1:00 |
| **Demo 1** | **Switch to live app** — Login + MFA + Security Center | 2:30 |
| **Demo 2** | **Live app** — Admin role change + audit + org scoping | 2:00 |
| Results | What works | 0:45 |
| Challenges/lessons | The hard parts and what you learned | 1:00 |
| Limitations/future | Honest gaps + roadmap | 0:45 |
| Conclusion + Thanks | Recap; invite questions | 0:45 |

**Total ≈ 18 min** — trim Background/Requirements and one demo scenario if your limit is tighter.

---

## Demo scenarios (the part that must work on camera)

### Scenario 1 — Login + MFA + Security Center (~2.5 min)
1. Log in as `eve.admin@tessera.dev` / `TesseraDemo@1`; complete the authenticator challenge.
2. Open **Security Center**: enroll an authenticator (QR → confirm → recovery codes) *or* show it already enrolled.
3. Show the **sessions/devices** list; revoke one; explain reuse detection in a sentence.

### Scenario 2 — Admin RBAC (~2 min)
1. Open **Users**; search the directory.
2. Reassign a user's role; show it landing in that user's **audit history**.
3. Mention **organization scoping** (org admins are limited to their org; full admins are not).

> Keep a backup screen-recording of each scenario in case the live demo misbehaves during recording.

---

## Delivery tips
- **Style matters:** one idea per slide, minimal text, talk *to* the audience not the slides.
- **Show, don't tell:** spend the demo time clicking through real flows, not narrating screenshots.
- **Be honest** about limitations — it reads as maturity, not weakness.
- Practice the transition into and out of the live demo so it's smooth.
