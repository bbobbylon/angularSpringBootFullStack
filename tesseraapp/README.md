# TesseraApp — Frontend (Angular)

The Angular 21 single-page app for TesseraApp. It talks to the Spring Boot REST API for authentication, profile/security management, the admin dashboard, and customer/invoice features.

> **Backend & full docs:** see the repo root [`README.md`](../README.md) and [`documentation/`](../documentation/).
> Architecture context: [GUIDE.md §1](../documentation/GUIDE.md#1-architecture) · API: [GUIDE.md §8](../documentation/GUIDE.md#8-api-reference).

---

## Tech stack

- **Angular 21** — standalone components (no `NgModule`), zoneless, signals; bootstrapped from `app.config.ts`
- **Bootstrap 5** + Bootstrap Icons — styling, including Bootstrap's own `data-bs-theme` color mode
- **RxJS** — async/state via observables and a `DataState` enum
- **Transloco** (`@jsverse/transloco`) — runtime i18n across six locales, switchable without a reload
- **@fontsource/ibm-plex-sans**, **@fontsource/ibm-plex-mono** — self-hosted fonts (no external CDN, so the production CSP stays `default-src 'self'`)
- **ngx-toastr** — toast notifications
- **@auth0/angular-jwt** — JWT decoding/expiry checks
- **jsPDF**, **file-saver** — client-side exports
- **Vitest** + ESLint + Prettier — test/lint/format

---

## Project structure

```
src/app/
├── app.component.ts          Root shell
├── app.config.ts             Standalone providers: router (preloading), HttpClient + interceptors, toastr
├── app.routes.ts             Lazy-loaded routes + guards
├── features/                 Feature pages (standalone components)
│   ├── auth/                 login, register, reset-password, verify, oauth2-callback, passkey-welcome
│   ├── home/                 dashboard
│   ├── customers/            list, details, new
│   ├── invoices/             list, detail, new
│   ├── services/             services-catalog (all users), services-admin (staff)
│   ├── users/                admin: users, user-details, roles-matrix
│   ├── security/             security-center (own MFA + sessions), security-overview (admin)
│   ├── analytics/            admin reporting hub
│   ├── billing/              admin billing hub
│   ├── profile/              profile + password
│   ├── legal/                public: contact, privacy-policy, terms
│   └── marketing/            public: feature-tour
├── shared/                   navbar, footer, command-palette, page-size-select, stats, charts,
│                             insights, animations — mounted once or reused across features
├── service/                  user, admin-user, current-user, customer, services-catalog, analytics,
│                             security-dashboard, contact, theme, language, notifications,
│                             command-palette, + transloco-loader
├── guard/                    authentication.guard, admin.guard, capability.guard
├── directive/                has-authority.directive (*appHasAuthority / [appRequiresAuthority])
├── interceptor/              token.interceptor (auth + refresh), language.interceptor
├── constants/                password-policy, phone-policy — UX mirrors of the backend rules
├── pipe/                     extract-array-value.pipe
├── utils/                    webauthn.utils, event-display.utils
├── testing/                  shared spec helpers
├── interface/                API/UI TypeScript contracts
└── enumeration/              Key (storage keys), DataState, EventType
```

> **The `constants/` mirrors are for UX only.** `password-policy.ts` and `phone-policy.ts` restate
> rules the backend enforces, so a rejection shows as a disabled submit and an inline hint instead of
> a confusing 400. They are never the check — see
> [GUIDE.md §7.12](../documentation/GUIDE.md#712-input-validation-policies).

---

## Routes

| Path | Component | Access |
|------|-----------|--------|
| `/login`, `/register`, `/resetpassword`, `/verify` | auth flows | public |
| `/verify/account/:key`, `/verify/password/:key` | email-link landings | public |
| `/oauth2/callback` | federated-login landing | public |
| `/privacy`, `/terms` | legal pages (Twilio A2P 10DLC campaign requirement) | public |
| `/contact` | Contact Us form → `POST /contact/send` | public |
| `/features` | Marketing feature tour | public |
| `/welcome-passkey` | One-time post-login passkey nudge (skippable) | authenticated |
| `/` | Home (dashboard) | authenticated |
| `/profile` | Profile | authenticated |
| `/security` | Account Security Center (TOTP + passkeys + sessions) | authenticated |
| `/customers`, `/customers/:id` | Customers | authenticated |
| `/invoices`, `/invoice/:id/:invoiceNumber` | Invoices | authenticated |
| `/customer/new`, `/invoice/new` | Creation forms | authenticated + `capabilityGuard` (`UPDATE:CUSTOMER` / `UPDATE:USER`) |
| `/services` | Services catalog (read) | authenticated |
| `/users`, `/users/:id`, `/roles` | Admin (users & roles × permissions matrix) | authenticated + `adminGuard` |
| `/services/manage` | Catalog administration (note: `manage`, not `admin`) | authenticated + `adminGuard` |
| `/billing`, `/analytics`, `/security-overview` | Admin billing, analytics and security hubs | authenticated + `adminGuard` |
| `**` | redirect → `/` | — |

Routes are lazy (`loadComponent`) and preloaded (`PreloadAllModules`). All three guards are a **usability aid** — they hide or divert what a user cannot use, but the backend independently enforces the same authorities on every request, so none of them is the security boundary. `capabilityGuard` reads `requiredAuthorities` from the route's own `data` and **fails closed** when a route declares nothing.

> **Adding a route means touching `SecurityConfig` too.** Once Angular is compiled into the Spring Boot jar there is a single origin, and security filters run *before* the SPA fallback — so a direct navigation to a client route hits the filter chain first. Every path above (except `/welcome-passkey`, which is a known gap) has a matching `GET`/`HEAD` `permitAll` matcher. Keep new routes plural or bare and **never** under `/user`, `/customer` or `/admin`, or a real controller will answer them instead: see [GUIDE.md §7.4](../documentation/GUIDE.md#74-authorization).

---

## Authentication flow (frontend)

```
LoginComponent → UserService.login$()  → POST /user/login
   ├─ plain login → store access + refresh tokens (localStorage) → navigate to /
   ├─ SMS 2FA     → prompt for code → GET /user/verify/code/{email}/{code}
   └─ TOTP        → prompt for code → POST /user/verify/totp { challenge, code }

Passkey sign-in skips the form entirely (usernameless):
   "Sign in with a passkey" → POST /user/verify/webauthn/options
                            → navigator.credentials.get()   (webauthn.utils.ts)
                            → POST /user/verify/webauthn { credential }  → tokens

Every protected request → tokenInterceptor:
   ├─ attaches  Authorization: Bearer <access_token>
   └─ on 401 → silently calls GET /user/refresh/token, stores rotated tokens, retries once
              (concurrent 401s share a single refresh via a BehaviorSubject guard)
```

- Tokens are stored in `localStorage` under the `Key` enum (`Key.TOKEN`, `Key.REFRESH_TOKEN`).
- `authenticationGuard` checks `UserService.isAuthenticated()` (valid, non-expired JWT) and redirects to `/login` otherwise.
- **Don't attach tokens manually** — the interceptor owns that (and the refresh dance). Backend token mechanics: [GUIDE.md §7](../documentation/GUIDE.md#7-security-model).

---

## Running locally

```bash
npm install
npm start        # dev server → http://localhost:4200 (API calls go to http://localhost:8080)
```

> The full stack is usually launched from the repo root via `./start.sh` (which starts both the API and this app). See [GUIDE.md §2](../documentation/GUIDE.md#2-getting-started).

Other scripts:

```bash
npm run build        # production build → dist/tesseraapp/browser/
npm test             # Vitest unit tests — 90 specs across 9 files
npm run lint         # ESLint (gates in CI)
npm run format       # Prettier (write)
npm run format:check # Prettier (verify only)
```

> **Always run the suite via `npm test` / `ng test`, never `npx vitest` directly.** The Angular
> `@angular/build:unit-test` builder is what emits `init-testbed.js` and calls
> `TestBed.initTestEnvironment()`; calling Vitest straight fails every spec with *"Need to call
> TestBed.initTestEnvironment() first"* — a red suite that tells you nothing about the code.

---

## Notes & known rough edges

- **API base URL is environment-driven** — every service reads `environment.apiUrl`. Dev is `http://localhost:8080`; production is `''` (same-origin relative URLs, because the SPA is served from inside the Spring Boot jar). `angular.json` `fileReplacements` swaps the file at build time, so **no rebuild is needed to change the backend origin** (see [GUIDE.md §6.1](../documentation/GUIDE.md#61-bootstrap-and-providers)).
- In Docker/production the built app is **compiled into the Spring Boot JAR** and served from `:8080` — there's no separate frontend server (see [GUIDE.md §1](../documentation/GUIDE.md#1-architecture)).
- Generated with Angular CLI 21. Use `ng generate component features/<area>/<name>` to scaffold new standalone components.
