# 34 · Billing overview (admin analytics)

> Assumes [`00-anatomy-of-a-request.md`](./00-anatomy-of-a-request.md) and the list idiom from
> [`30 §A`](./30-customers.md). This doc covers the **read-only billing analytics page**: how it reuses
> the existing invoice/stats endpoints, what it derives client-side, and the one place where its
> admin-only promise is weaker than it looks. It only references the shared interceptor/filter
> machinery where it matters.

**Route:** `/billing` → `BillingComponent`
(`tesseraapp/src/app/features/billing/billing/billing.component.ts`, `.html`), guarded
`canActivate: [authenticationGuard, adminGuard]` (`app.routes.ts:117-121`).
**Primary endpoints:** `GET /customer/stats` · `GET /customer/invoice/list?page=0&size=200` →
`CustomerController`. **There is no dedicated billing endpoint** — both are pre-existing customer
endpoints, reused as-is.

This is a pure **client-side derivation** page: two GETs fetch raw stats + a page of invoices, and
every KPI, donut, bar, and table is a `computed()` signal recalculated in Angular. The only
server-computed numbers are the three `Stats` totals from `STATS_QUERY`; everything else is folded
from the loaded invoice page in the browser.

| Source value | Carries | UI outcome |
| --- | --- | --- |
| `data.stats` (`/customer/stats`) | `totalBilled`, `totalInvoices`, `totalCustomers` | the three left-most KPI cards + `avgInvoiceValue` |
| `data.invoices.content` (`/customer/invoice/list`, ≤200 rows) | a page of `Invoice` rows incl. `services[]` line items | collection-rate KPI, status donut, monthly-revenue bars, revenue-by-service, recent-invoices table |
| user lacks `UPDATE:USER`/`UPDATE:ROLE` | — | `adminGuard` redirects to `/` (never reaches the page) |
| either fetch errors | `error` | toast via `NotificationsService`; that panel shows `DataState.ERROR` |

---

## A · Loading the billing overview

The page is reachable only from the **Admin** dropdown, which the navbar renders only when
`canManageUsers` (`navbar.component.html:62`); the "Billing Overview" link sits at
`navbar.component.html:88`. On navigation `adminGuard` runs first (see [§C](#c--the-admin-gate-is-frontend-only)),
then `ngOnInit` fires **two independent fetches in parallel** — each with its own state signal, so the
KPI cards and the invoice-derived charts load and fail independently.

### What the user does
1. Click **Admin → Billing Overview** in the navbar (`navbar.component.html:88`).
2. (Nothing else — the page is read-only; the header buttons just route to `/invoice/new` and
   `/invoices`, `billing.component.html:24-29`.)

### The full trace

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant CMP as BillingComponent
    participant SVC as CustomerService
    participant CACHE as cacheInterceptor
    participant TOK as tokenInterceptor
    participant SEC as SecurityConfig
    participant CTRL as CustomerController
    participant SRV as CustomerServiceImpl
    participant DB as DB
    Note over CMP: ngOnInit fires BOTH fetches  :248-274
    par stats KPIs
        CMP->>SVC: stats$()  :250 / customer.service.ts:44
        SVC->>CACHE: GET /customer/stats
        Note over CACHE: GET → cache miss → forward  cache.interceptor.ts
        CACHE->>TOK: next(req)
        Note over TOK: attaches Bearer 🔑  token.interceptor.ts:49
        TOK->>SEC: GET /customer/stats  🔑
        Note over SEC: matcher GET /** → READ:USER / READ:CUSTOMER  :160
        SEC->>CTRL: getStats()  :62-74
        CTRL->>SRV: getStats()  :197-199
        SRV->>DB: STATS_QUERY (aggregate subqueries)  CustomerQuery.java:18
        CTRL-->>CMP: 200 { user, stats, statusBreakdown }  :64-73
        Note over CMP: statusBreakdown IGNORED; reads stats only  :100
    and invoice-derived charts
        CMP->>SVC: invoices$(0, 200)  :263 / customer.service.ts:112
        SVC->>SEC: GET /customer/invoice/list?page=0&size=200  🔑
        SEC->>CTRL: getInvoices(0,200)  :219-230
        CTRL->>SRV: getInvoices(0,200)  :142-144
        SRV->>DB: invoiceRepo.findAll(PageRequest) (Hibernate SELECT)
        Note over DB: + eager SELECT on invoiceserviceitems  Invoice.java:55-58
        CTRL-->>CMP: 200 { user, invoices: Page<Invoice> }  :221-229
    end
    Note over CMP: computed() signals fold rows → donut/bars/services/recent  :139-235
    CMP-->>U: KPIs + 4 charts render (per-panel LOADING→LOADED)
```

Each fetch uses the canonical `map → startWith(LOADING) → catchError(ERROR)` pipe and writes its own
signal (`statsState`, `invoicesState`, `billing.component.ts:250-273`). `user()` is read off
`statsState` (`:99`); a combined `isLoading()` is true while *either* stream is still loading
(`:242-246`), driving the header "Loading…" badge (`billing.component.html:16-20`).

> **Note — `statusBreakdown` is fetched but discarded.** `/customer/stats` also returns a system-wide
> *customer*-status map (`CustomerController.java:69`, `CustomerQuery.CUSTOMER_STATUS_BREAKDOWN_QUERY`),
> but Billing reads only `data.stats` (`:100`). Its donut is an **invoice**-status ring computed
> client-side from the loaded page (`:139-168`), not the server breakdown. The dashboard
> ([`32 §A`](./32-dashboard.md)) is what consumes `statusBreakdown`.

---

## B · What's computed where (server total vs client fold)

Only the three `Stats` totals are server-computed; everything else is derived in the browser from the
≤200-row invoice page. This split matters for accuracy — see the cap gotcha below.

| Widget | Computed by | Source | Citation |
| --- | --- | --- | --- |
| Total Billed (KPI) | server | `Stats.totalBilled` (`STATS_QUERY`, system-wide) | `:114` |
| Total Invoices (KPI) | server | `Stats.totalInvoices` (system-wide) | `:115` |
| Avg Invoice Value (KPI) | client | `totalBilled / totalInvoices` | `:118-121` |
| Collection Rate (KPI) | client | `PAID / loaded-page count` × 100 | `:123-128` |
| Invoice-status donut | client | `GROUP BY status` over the loaded page (`r=15.915` dasharray trick) | `:139-168` |
| Monthly-revenue bars (last 6 mo) | client | bucket loaded page by `invoiceDate` YYYY-MM, sum `totalAmount` | `:174-202` |
| Revenue by service (top 8) | client | flatten `inv.services[]`, sum `price` by `name` | `:206-227` |
| Recent invoices (top 6) | client | loaded page sorted desc by `invoiceDate` | `:231-235` |

> **Gotcha — the page cap silently undercounts large datasets.** The KPI cards *Total Billed* and
> *Total Invoices* are accurate (server aggregate over the whole table). But **every chart and the
> *Collection Rate* KPI are folded from only the first 200 invoices** (`invoices$(0, 200)`, `:263`). If
> the table holds more than 200 invoices, the donut, monthly bars, service breakdown, and collection
> rate quietly reflect only the most-recently-paged slice while the two top-line KPIs say otherwise —
> an internal inconsistency, not a crash. The fix is server-side aggregation (an invoice equivalent of
> `STATS_QUERY`); the related stub is tracked as the [invoice total-sum `@Query`](./README.md#backend-endpoints--features-planned)
> and discussed for the dashboard in [`32 §A`](./32-dashboard.md).

The **scope badge** ("All Organizations" for `DELETE:USER`, else "Your Organization",
`:109-110`, `billing.component.html:11-14`) is **purely cosmetic**. There is no org-scoped filtering on
the backend yet — both `STATS_QUERY` and `getInvoices` return system-wide data regardless of the
viewer's tier. (Org-scoped administration *is* enforced elsewhere, in `AdminUserController` — see
[`20`](./20-admin-users-rbac.md) — just not on these two read endpoints.)

---

## C · The admin gate is frontend-only

The `/billing` route requires `UPDATE:USER` or `UPDATE:ROLE` via `adminGuard` (`admin.guard.ts:20-31`,
which mirrors the `/admin/**` authority set). But `adminGuard` is explicitly a **usability aid, not a
security boundary** (NFR-SEC-4, documented in the guard's own Javadoc, `admin.guard.ts:5-16`). The real
question is what the *backend* requires for the two endpoints this page calls.

| Endpoint | Real backend authority | Where enforced |
| --- | --- | --- |
| `GET /customer/stats` | `READ:USER` **or** `READ:CUSTOMER` | `SecurityConfig.java:160` (broad `GET /**` catch-all) |
| `GET /customer/invoice/list` | `READ:USER` **or** `READ:CUSTOMER` | `SecurityConfig.java:160` |

Neither endpoint matches an `/admin/**` rule, so **neither requires any admin-grade authority**. The
matcher chain is evaluated top-down (`SecurityConfig.java:136-164`): the specific `/admin/**`,
`/user/totp/**`, and delete rules come first, then both billing endpoints fall through to the broad
`requestMatchers(GET, "/**").hasAnyAuthority("READ:USER", "READ:CUSTOMER")` at `:160`.

> ⚠️ **Doc-vs-code discrepancy — flagged honestly.** `BillingComponent`'s class Javadoc claims admin
> scope is *"double-checked server-side on every API call"* (`billing.component.ts:67-68`). **The code
> disagrees, and the code wins.** A logged-in non-admin who is bounced from the `/billing` *route* by
> `adminGuard` can still call `GET /customer/stats` and `GET /customer/invoice/list` directly (e.g. via
> curl with any token carrying `READ:USER`/`READ:CUSTOMER`) and receive the **same system-wide billing
> data**. The admin restriction on this page is therefore cosmetic at the API layer.
>
> **If admin-only billing is a real requirement**, add an explicit matcher *above* line 160 — e.g.
> `requestMatchers(GET, "/customer/stats", "/customer/invoice/list").hasAnyAuthority("UPDATE:USER", "UPDATE:ROLE")`
> — and correct the component Javadoc. The same caveat applies to the Analytics hub (`/analytics`),
> which reuses `/customer/list` + `/customer/invoice/list` identically.

---

## D · Failure paths

| Failure | Where | User sees |
| --- | --- | --- |
| Not authenticated | `authenticationGuard` → `/login`; API → 401 | redirect / silent refresh ([`00 §7.2`](./00-anatomy-of-a-request.md)) |
| Authenticated, no `UPDATE:USER`/`UPDATE:ROLE` | `adminGuard` (`admin.guard.ts:30`) | redirect to home `/` (not a 403 view) |
| Token expired mid-session | entry point → interceptor | silent refresh + retry ([`00 §7.2`](./00-anatomy-of-a-request.md)) |
| `/customer/stats` errors | `stats$` `catchError` (`:254-257`) | error toast; KPI cards show `ERROR`; charts still load |
| `/customer/invoice/list` errors | `invoices$` `catchError` (`:267-270`) | error toast; charts show `ERROR`; KPI cards still load |
| > 200 invoices exist | no error — silent | charts/collection-rate undercount vs KPI totals (see [§B](#b--whats-computed-where-server-total-vs-client-fold) gotcha) |
| Empty dataset | template `@else` branches | per-panel "No … data yet" placeholders (`billing.component.html:116,143,175,229`) |

---

## E · Wire-level detail

### E.1 · Requests

```http
GET /customer/stats                              ← environment.apiUrl (environment.ts)
Authorization: Bearer <access JWT>               ← tokenInterceptor (token.interceptor.ts:49) 🔑

GET /customer/invoice/list?page=0&size=200       ← customer.service.ts:112-115
Authorization: Bearer <access JWT>               🔑
```

Both are GETs, so `cacheInterceptor` may serve a cache hit and short-circuit before the token is even
attached; any prior mutation (a new invoice from [`31 §A`](./31-invoices.md)) calls `evictAll()` and
forces a fresh fetch on the next visit.

### E.2 · `GET /customer/stats` → `200`
```jsonc
{ "timeStamp": "2026-06-28T…",
  "data": {
    "user": { /* authenticated user */ },
    "stats": { "totalCustomers": 42, "totalInvoices": 318, "totalBilled": 64720 }, // ← only this is read (:100)
    "statusBreakdown": { "ACTIVE": 30, "PENDING": 8, "INACTIVE": 4 }               // ← fetched but ignored by Billing
  },
  "message": "Stats retrieved successfully!", "status": "OK", "statusCode": 200 }
```

### E.3 · `GET /customer/invoice/list?page=0&size=200` → `200`
```jsonc
{ "data": {
    "user": { /* … */ },
    "invoices": {                       // Spring Data Page<Invoice>
      "content": [
        { "id": 1041, "invoiceNumber": "INV-1041", "status": "PAID",
          "invoiceDate": "2026-06-14", "totalAmount": 190,
          "services": [ { "name": "Consulting", "price": 150 }, { "name": "Hosting", "price": 40 } ] }
        /* … up to 200 rows; services[] eager-loaded from invoiceserviceitems … */
      ],
      "totalElements": 318, "totalPages": 2, "number": 0, "size": 200
    } },
  "message": "All Invoices retrieved successfully!", "status": "OK", "statusCode": 200 }
```
(Field names representative — see `invoice.interface.ts` / `appstates.interface.ts`. The donut/bars/
service rows are folded from `content[]` in the browser, so `totalElements > size` is the cap risk.)

### E.4 · SQL executed

| Step | Source | SQL |
| --- | --- | --- |
| stats totals | `CustomerQuery.STATS_QUERY` (`CustomerServiceImpl.java:197-199`) | `SELECT c.total_customers, i.total_invoices, inv.total_billed FROM (SELECT COUNT(*) total_customers FROM customer) c, (SELECT COUNT(*) total_invoices FROM invoice) i, (SELECT ROUND(SUM(totalAmount)) total_billed FROM invoice) inv` |
| invoice page | `invoiceRepo.findAll(PageRequest)` (`CustomerServiceImpl.java:142-144`) | Hibernate-generated `SELECT … FROM invoice … LIMIT 200 OFFSET 0` (+ `COUNT(*)`) |
| line items | `@ElementCollection(EAGER)` (`Invoice.java:55-58`) | eager secondary `SELECT … FROM invoiceserviceitems WHERE invoice_id = ?` per invoice — populates `services[]` |
| status breakdown (unused here) | `CUSTOMER_STATUS_BREAKDOWN_QUERY` | `SELECT status, COUNT(*) AS count FROM customer GROUP BY status ORDER BY count DESC` |

---

## Cross-links
- The invoices this page aggregates (list / new / detail) → [`31-invoices.md`](./31-invoices.md)
- `STATS_QUERY` and its other consumer (the home dashboard) → [`32 §A`](./32-dashboard.md)
- The admin authority model this page *displays* but does not *enforce* server-side → [`20-admin-users-rbac.md`](./20-admin-users-rbac.md)
- The `GET /**` matcher fall-through that makes the admin gate frontend-only → [`00 §6-7`](./00-anatomy-of-a-request.md) · [`../GUIDE.md` §7](../GUIDE.md#7-security-model)
- The shared list idiom & pagination envelope → [`30 §A`](./30-customers.md)
