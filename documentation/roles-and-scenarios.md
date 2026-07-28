# Roles, Capabilities & Scenarios

**What every kind of user can actually do in TesseraApp**, end to end — the seven roles, the two
authorization axes, what each screen offers whom, and worked scenarios for the situations that come
up in practice.

> **Audience:** anyone who needs to answer "can this person do that?" — support, reviewers,
> assessors, and developers wiring up a new screen.
> **See also:** [security.md](security.md) (how enforcement works) ·
> [api-reference.md](api-reference.md) (endpoint-level authority) ·
> [database.md](database.md) (the role catalog and its ids)

---

## Table of contents

1. [The two authorization axes](#1-the-two-authorization-axes)
2. [The seven roles at a glance](#2-the-seven-roles-at-a-glance)
3. [Capability matrix](#3-capability-matrix)
4. [What each role sees in the UI](#4-what-each-role-sees-in-the-ui)
5. [Organization scoping explained](#5-organization-scoping-explained)
6. [Scenarios — everyday user](#6-scenarios--everyday-user)
7. [Scenarios — help desk admin](#7-scenarios--help-desk-admin)
8. [Scenarios — organization admin](#8-scenarios--organization-admin)
9. [Scenarios — platform admin](#9-scenarios--platform-admin)
10. [Scenarios — security incidents](#10-scenarios--security-incidents)
11. [What nobody can do](#11-what-nobody-can-do)

---

## 1. The two authorization axes

Every administrative question in this application is really two questions, and they are enforced by
different mechanisms. Confusing them is the most common way to reason about this system wrongly.

| Axis | Question | Mechanism | Failure |
|---|---|---|---|
| **Authority** | *What kind of action may you perform?* | Permission strings on the role (`READ:USER`, `UPDATE:CUSTOMER`, …), checked by `SecurityConfig` matchers **and** `@PreAuthorize` | `403` |
| **Organization scope** | *Whose records may you perform it on?* | Shared active membership in `userorganizations`, checked per-target | `403` |

A `ROLE_ORGANIZATION_ADMIN` holds `UPDATE:USER` — the authority to change account state — and can
still be refused, because the *target* is outside their organizations. Passing the first check is
not evidence about the second.

> **A third, quieter axis: identity.** Several operations are scoped to *you* regardless of role —
> your own sessions, your own connected providers, your own profile. Those take the account from the
> JWT and never from the request, so there is no id a caller could supply to reach somebody else's.

---

## 2. The seven roles at a glance

| # | Role | In one sentence |
|---|------|-----------------|
| 1 | `ROLE_GUEST` | Can sign in and see their own account. Read-only, and cannot even list customers. |
| 2 | `ROLE_USER` | The default. Browses customers and invoices; changes nothing. |
| 3 | `ROLE_MODERATOR` | A user who may also **edit customers and raise invoices**. Not staff. |
| 4 | `ROLE_HELP_DESK_ADMIN` | Support: can reach the admin surface and fix account state, but **cannot assign roles**. |
| 5 | `ROLE_ORGANIZATION_ADMIN` | Tenant administrator: full admin surface, **restricted to their own organizations**. |
| 6 | `ROLE_ADMIN` | Platform administrator. Unscoped, plus customer creation and user deletion. |
| 7 | `ROLE_APPLICATION_ADMIN` | Everything `ROLE_ADMIN` has, plus `DELETE:CUSTOMER`. |

New accounts — whether registered with a password or created on a first federated sign-in — get
`ROLE_USER`.

---

## 3. Capability matrix

✅ allowed · 🟡 allowed but organization-scoped · ❌ refused (`403`)

| Capability | Guest | User | Moderator | Help&nbsp;Desk | Org&nbsp;Admin | Admin | App&nbsp;Admin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| **Own account** | | | | | | | |
| Sign in, view own profile | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit own profile, change password | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Enrol / remove authenticator (TOTP) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| View & revoke own sessions | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Connect / disconnect own providers | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| View own activity log | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Customers** | | | | | | | |
| Browse / search customers | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Create a customer | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit a customer | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Delete a customer | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| **Invoices** | | | | | | | |
| Browse invoices, export PDF | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Create an invoice | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit an invoice | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Services catalog** | | | | | | | |
| Browse the catalog | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Add / edit / retire services | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Administration** | | | | | | | |
| Open the user directory | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| View another user's detail & audit log | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| Enable / unlock an account | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| Edit another user's profile | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| **Assign roles** | ❌ | ❌ | ❌ | ❌ | 🟡 | ✅ | ✅ |
| Delete a user | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Reporting** | | | | | | | |
| Billing overview | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| Analytics hub | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| Security dashboard | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| Roles × permissions matrix | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |

> **The help desk gap is deliberate.** `ROLE_HELP_DESK_ADMIN` holds `UPDATE:USER` but not
> `UPDATE:ROLE`, so support can unlock an account without being able to promote it. That is the
> whole point of splitting the two authorities.

---

## 4. What each role sees in the UI

The interface is gated so a refusal is felt *before* the click, not as a `403` on submit. Three
layers do this, and none of them is the security boundary — the server re-checks everything
(NFR-SEC-4).

| Layer | Example |
|---|---|
| **Route guards** | `/users`, `/roles`, `/billing`, `/analytics`, `/security-overview`, `/services/manage` need staff authority; `/customer/new` and `/invoice/new` need write authority |
| **Navigation** | Menu entries and command-palette commands are hidden when the authority is missing — a dead entry in a menu is noise |
| **Controls** | Buttons are disabled with an explanatory tooltip when hiding would read as a bug (a form whose submit button is simply absent) |

**What a `ROLE_USER` sees:** Home dashboard with quick actions, All Customers, All Invoices, Service
Catalog, Profile, Security Center. No New Customer / New Invoice entries, no Admin menu.

**What a `ROLE_MODERATOR` adds:** New Customer, New Invoice, and working Save buttons on customer
and invoice forms.

**What staff roles add:** the Admin menu — User Directory, Roles & Permissions, Security Overview,
Billing Overview, Analytics Hub — plus Manage Services under the Services menu.

**What a super-admin (`DELETE:USER`) adds:** the Assign Roles shortcut and system-wide billing scope.

> Every denial names the capability and points at the remedy — *"You don't have permission to manage
> users — contact your administrator"* — and never reveals whether a particular record exists.

---

## 5. Organization scoping explained

Organizations bound **who** a tenant administrator may act on. Only `ROLE_ORGANIZATION_ADMIN` is
scoped; `ROLE_ADMIN` and `ROLE_APPLICATION_ADMIN` act globally by design.

**The predicate:** an org admin may act on a user who shares at least one **active** membership with
them. Deactivating a membership removes visibility immediately — a lapsed membership must stop
granting access the moment it lapses.

**Where it applies:**

| Surface | Behaviour for an org admin |
|---|---|
| User directory | Lists only users sharing an organization |
| User detail, audit log | `403` for anyone outside scope — **reads are scoped, not just writes** |
| Role / settings / profile changes | `403` for out-of-scope targets |
| Billing, analytics, security dashboard | Aggregates restricted to their organizations' customers |

**Two rules that matter more than they look:**

- **Scoping is applied inside the SQL, never to the results.** An aggregate has discarded its
  attribution by the time it is a number — you cannot subtract another tenant's contribution from a
  `SUM` after the fact — and filtering a page after retrieval corrupts `totalElements` and returns
  short pages.
- **An empty scope means *nothing*, not *everything*.** An org admin with no active memberships sees
  zeros and empty lists. Collapsing that into "unscoped" would hand the platform-wide view to
  precisely the account with the least established standing.

New customers are stamped with the creating user's organization from the JWT, never from the request
body — a client-supplied `organizationId` would let anyone file records into another tenant's
dashboards.

---

## 6. Scenarios — everyday user

### Signing up and getting in
1. Register → account created **disabled**, verification email sent.
2. Follow the emailed link → account enabled.
3. Sign in → access token (30 min) + refresh session (5 days sliding).

Alternatively, sign in with Google / GitHub / Microsoft. A first federated sign-in creates an
enabled, passwordless account with `ROLE_USER`. If an account already exists with that verified
email, the identity is linked to it instead of duplicating the person.

### Turning on an authenticator
Security Center → Multi-factor → Set up → scan the QR → enter a code to confirm → **save the recovery
codes, which are shown exactly once**. From then on, sign-in requires a code. Removing the
authenticator also requires a code, so a stolen session alone cannot strip the second factor.

### Connecting a second sign-in method
Security Center → Connected accounts → Connect. You stay signed in as yourself; the identity is
attached to *your* account. Disconnecting is refused if it is your **last** way in — no password and
no other provider — because that would lock you out with no self-service path back.

### Seeing where you are signed in
Security Center → Sessions & devices lists every live session with device, address, and last activity;
the current one is badged. Revoke one, or "log out everywhere else" to keep only this device.

### Forgot the password
Request a reset → time-boxed single-use link by email → set a new password meeting the policy
(8+ characters, upper, lower, digit, no spaces).

> **The login screen never reveals whether an email exists.** An unknown address and a wrong password
> produce byte-identical responses.

---

## 7. Scenarios — help desk admin

### "I'm locked out"
1. Admin → User Directory → search by name or email.
2. Open the account. **Account State** shows *Locked* (brute-force protection) or *Not enabled*
   (never verified) — they present identically to the caller but need different fixes.
3. Tick *Account unlocked* and/or *Account enabled* → Update State.
4. The change is audited **against the target user**, so it appears in their activity log.

### "Can you make me an administrator?"
Help desk **cannot** — the role dropdown is disabled, with *"You don't have permission to assign
roles — contact your administrator."* That escalation has to go to an org admin or above.

### "Something looks wrong with my account"
Open the user's detail page and read their audit log: sign-ins, profile changes, MFA changes, session
revocations, provider links, and any flagged sign-ins.

---

## 8. Scenarios — organization admin

### Onboarding someone into your organization
The person registers (or signs in federated) themselves, which creates a `ROLE_USER` account. Once
they share an active membership with you, they appear in your directory and you can assign a role.

> Membership itself is currently seeded/managed in the database, not through the UI — see
> [ROADMAP.md](../ROADMAP.md).

### Promoting a colleague to moderator
User Directory → open them → Assigned role → `ROLE_MODERATOR` → Update Role. They can now create and
edit customers and invoices, without gaining any administrative surface.

### "Why can't I see this user?"
Because they share no active organization with you. The response is a `403` that names no account —
you cannot tell an out-of-scope user apart from one that does not exist, and that is deliberate.

### Reading your dashboards
Billing, Analytics and Security Overview all show **only your organizations' data**, and the security
dashboard says so explicitly at the top. A dashboard that looked identical whether it covered the
platform or one slice of it would invite its worst misreading: concluding all is quiet when you can
only see your own corner.

### You cannot promote yourself
Administrators may not change their own role or account state through the admin surface —
self-targeting is refused. Role changes always involve a second person.

---

## 9. Scenarios — platform admin

### Managing the services catalog
Services → Manage Services. Add, edit, retire, reinstate. Retiring removes a service from the
new-invoice picker while keeping it in the catalog — invoices already raised keep the name and price
they captured, so editing or retiring never restates history. **There is no delete.**

### Correcting a wrong invoice
Open the invoice → Edit invoice → adjust status, date, or amounts. The invoice **number** cannot
change: it is the reference already printed on the customer's copy. Reassigning an invoice to a
different customer is a separate operation.

### Reviewing platform security
Security Overview, with a 7 / 30 / 90-day window:
- **Anomalous sign-ins** — flagged by device/network mismatch, with what was noticed and which
  step-up was applied
- **Accounts flagged more than once** — one flag is usually a new laptop; repeats are a pattern
- **Failed sign-in rate** — a rate, because forty failures is alarming against fifty successes and
  routine against forty thousand
- **Token replay detected** — refresh-token reuse, with the session family already revoked
- **Restricted accounts** — locked and unverified together
- **MFA coverage** — the single-factor group is exactly the population that falls back to an emailed
  code when a sign-in is flagged

---

## 10. Scenarios — security incidents

### A sign-in from an unrecognised device
Detection compares against **that account's own** history of devices and networks (networks at prefix
granularity, so a DHCP renewal is not "new"). On a mismatch:

| Account has | What happens |
|---|---|
| Authenticator (TOTP) | Normal TOTP challenge; the anomaly is recorded |
| SMS 2FA | Normal SMS challenge; the anomaly is recorded |
| **Password only** | A one-time code is emailed and **no tokens are issued** until it is entered |

The user is told extra verification is needed, never *why* — the reason goes only to the account
owner's inbox and the audit row. The check **fails open**: if it cannot run, the sign-in proceeds
without it, logged as a warning. It runs after the password has already been accepted, so failing
closed would break logins to protect nothing.

### A stolen refresh token
Every refresh mints a new token and retires the old one. Presenting a retired token is the signature
of theft, so the **entire session family is revoked** and the attempt is audited as
`TOKEN_REUSE_DETECTED`. Both the thief and the legitimate user are signed out and must authenticate
again — which is the correct outcome, because at that point the two cannot be told apart.

### Someone tries to connect an identity that is not theirs
Refused. Linking is rejected when the provider identity already belongs to another account —
otherwise "Connect a provider" would be an account-takeover primitive, since links are keyed on the
provider's subject rather than on the verified email. The refusal names no account.

### Repeated failed passwords
Per-account lockout after repeated failures, persisting until an administrator unlocks it. Separately,
`RateLimitFilter` applies a general request budget and answers `429` with `Retry-After`.

---

## 11. What nobody can do

Constraints that hold for **every** role, including `ROLE_APPLICATION_ADMIN`:

- **Change your own role or account state through the admin surface.** Self-targeting is refused; a
  second administrator is always involved.
- **Read another user's password.** Only BCrypt hashes are stored, and federated accounts have none.
- **See recovery codes twice.** Only hashes are kept; the plaintext is shown once at enrolment.
- **Learn whether an email address is registered** from a login, password-reset, or authorization
  failure. Every such response is deliberately uninformative.
- **Disconnect an account's last sign-in method.**
- **Delete a service or a role.** Services retire; roles are seeded reference data.
- **Reach another tenant's data as an organization admin** — including by reading rather than writing.

---

## Related documents

- [security.md](security.md) — how each control is enforced
- [api-reference.md](api-reference.md) — endpoint-by-endpoint authority
- [database.md](database.md) — role catalog, permissions, organization tables
- [flows/](flows/README.md) — click-to-database traces of these journeys
- [ROADMAP.md](../ROADMAP.md) — what is planned next
