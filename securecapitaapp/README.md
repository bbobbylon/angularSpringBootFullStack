# TesseraApp — Frontend (Angular)

The Angular 21 single-page app for TesseraApp. It talks to the Spring Boot REST API for authentication, profile/security management, the admin dashboard, and customer/invoice features.

> **Backend & full docs:** see the repo root [`README.md`](../README.md) and [`documentation/`](../documentation/).
> Architecture context: [documentation/architecture.md §6](../documentation/architecture.md#6-frontend-architecture) · API: [documentation/api-reference.md](../documentation/api-reference.md).

---

## Tech stack

- **Angular 21** — standalone components (no `NgModule`), bootstrapped from `app.config.ts`
- **Bootstrap 5** + Bootstrap Icons — styling
- **RxJS** — async/state via observables and a `DataState` enum
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
│   ├── auth/                 login, register, reset-password, verify, oauth2-callback
│   ├── home/                 dashboard
│   ├── customers/            list, details, new
│   ├── invoices/             list, detail, new
│   ├── users/                admin: users, user-details, roles-matrix
│   ├── security/             Account Security Center (MFA + sessions)
│   └── profile/              profile + password
├── service/                  user, admin-user, customer, theme, notifications, http-cache
├── guard/                    authentication.guard, admin.guard
├── interceptor/              token.interceptor (auth + refresh), cache.interceptor
├── interface/                API/UI TypeScript contracts
└── enumeration/              Key (storage keys), DataState, EventType
```

---

## Routes

| Path | Component | Access |
|------|-----------|--------|
| `/login`, `/register`, `/resetpassword`, `/verify` | auth flows | public |
| `/user/verify/account/:key`, `/user/verify/password/:key` | email-link landings | public |
| `/oauth2/callback` | federated-login landing | public |
| `/` | Home (dashboard) | authenticated |
| `/profile` | Profile | authenticated |
| `/security` | Account Security Center (TOTP + sessions) | authenticated |
| `/customers`, `/customers/:id`, `/customer/new` | Customers | authenticated |
| `/invoices`, `/invoice/new`, `/invoice/:id/:invoiceNumber` | Invoices | authenticated |
| `/users`, `/users/:id`, `/roles` | Admin (users & roles) | authenticated + `adminGuard` |
| `**` | redirect → `/` | — |

Routes are lazy (`loadComponent`) and preloaded (`PreloadAllModules`). The `adminGuard` is a **usability aid** — it hides admin routes from users who lack staff authority, but the backend independently enforces the same authorities, so it's never the security boundary.

---

## Authentication flow (frontend)

```
LoginComponent → UserService.login$()  → POST /user/login
   ├─ plain login → store access + refresh tokens (localStorage) → navigate to /
   ├─ SMS 2FA     → prompt for code → GET /user/verify/code/{email}/{code}
   └─ TOTP        → prompt for code → POST /user/verify/totp { challenge, code }

Every protected request → tokenInterceptor:
   ├─ attaches  Authorization: Bearer <access_token>
   └─ on 401 → silently calls GET /user/refresh/token, stores rotated tokens, retries once
              (concurrent 401s share a single refresh via a BehaviorSubject guard)
```

- Tokens are stored in `localStorage` under the `Key` enum (`Key.TOKEN`, `Key.REFRESH_TOKEN`).
- `authenticationGuard` checks `UserService.isAuthenticated()` (valid, non-expired JWT) and redirects to `/login` otherwise.
- **Don't attach tokens manually** — the interceptor owns that (and the refresh dance). Backend token mechanics: [documentation/security.md](../documentation/security.md).

---

## Running locally

```bash
npm install
npm start        # dev server → http://localhost:4200 (API calls go to http://localhost:8080)
```

> The full stack is usually launched from the repo root via `./start.sh` (which starts both the API and this app). See [documentation/getting-started.md](../documentation/getting-started.md).

Other scripts:

```bash
npm run build        # production build → dist/securecapitaapp/browser/
npm test             # Vitest unit tests
npm run lint         # ESLint
npm run format       # Prettier (write)
```

---

## Notes & known rough edges

- **API base URL is `http://localhost:8080`** in the services. Deploying against a different backend origin means updating the environment files and rebuilding (a known limitation — see [deployment.md §7](../documentation/deployment.md#7-pre-deployment-checklist)).
- In Docker/production the built app is **compiled into the Spring Boot JAR** and served from `:8080` — there's no separate frontend server (see [architecture.md §7](../documentation/architecture.md#7-runtime-topology)).
- Generated with Angular CLI 21. Use `ng generate component features/<area>/<name>` to scaffold new standalone components.
