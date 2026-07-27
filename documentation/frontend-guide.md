# Frontend Internals Guide

The Angular client from the inside: how the standalone app boots and wires its providers, the route → component map and which guard protects each page, the two HTTP interceptors and their load-bearing registration order, the cache strategy (TTL, keying, invalidation), every service's API surface, the guards, the `DataState`/signals state machine that drives every screen, and the enumerations + envelope interfaces that tie it to the backend.

> **Audience:** frontend contributors and anyone tracing a request from a component to the wire.
> **Frontend root:** `tesseraapp/src/app/` · **Stack:** Angular 21 standalone (no NgModule) · Bootstrap 5.3 color-mode · stateless JWT in `localStorage`.
> **Key source files:** `app.config.ts` · `app.routes.ts` · `interceptor/cache.interceptor.ts` · `interceptor/token.interceptor.ts` · `service/user.service.ts`
> **See also:** [architecture.md §6](architecture.md#6-frontend-architecture) (frontend in the whole-system picture) · [security.md](security.md) (token/auth internals the interceptor relies on) · [api-reference.md](api-reference.md) (the backend endpoints these services call) · [flows/](flows/README.md) (click-to-DB sequence diagrams) · [`../tesseraapp/README.md`](../tesseraapp/README.md) (frontend quick start).

> **Code wins over docs.** Every claim below is cited `file:line` relative to `tesseraapp/src/app/` (or `tesseraapp/src/` for environments). If a table here and the code ever disagree, **the code wins** — fix the doc.

---

## Table of contents

1. [Bootstrap & provider wiring](#1-bootstrap--provider-wiring)
2. [Route → component map](#2-route--component-map)
3. [Guards](#3-guards)
4. [Interceptors: order, behavior & cache strategy](#4-interceptors-order-behavior--cache-strategy)
5. [Services API reference](#5-services-api-reference)
6. [State management: DataState, signals & the RxJS trio](#6-state-management-datastate-signals--the-rxjs-trio)
7. [Enumerations & envelope interfaces](#7-enumerations--envelope-interfaces)
8. [Internationalization (Transloco)](#8-internationalization-transloco)
9. [Capability-level UI gating](#9-capability-level-ui-gating)
10. [Command palette](#10-command-palette)
11. [Known limitations, gotchas & gap register](#11-known-limitations-gotchas--gap-register)

---

## 1. Bootstrap & provider wiring

There is no `AppModule`. `appConfig` (an `ApplicationConfig`) is passed to `bootstrapApplication()` in `main.ts`; every provider it registers is a singleton for the app's lifetime (`app.config.ts:20`).

| Provider | Line | What it does |
|----------|------|--------------|
| `provideBrowserGlobalErrorListeners()` | `app.config.ts:28` | Routes unhandled JS errors (timeouts, promise rejections) through Angular's `ErrorHandler`. |
| `provideRouter(routes, withComponentInputBinding(), withPreloading(PreloadAllModules))` | `app.config.ts:36` | Registers the route table (§2). `withComponentInputBinding()` binds route params (`:id`, `:key`) straight to component `@Input()`s. `PreloadAllModules` eagerly pulls every lazy chunk **after** first paint. |
| `provideHttpClient(withInterceptors([cacheInterceptor, tokenInterceptor]))` | `app.config.ts:49` | Installs the two functional interceptors **in this exact order** (§4). |
| `{ provide: IMAGE_CONFIG, useValue: { disableImageSizeWarning: true } }` | `app.config.ts:51` | Silences `NgOptimizedImage` size warnings. |
| `provideAnimationsAsync()` | `app.config.ts:52` | Lazy-loads the animations module. |
| `provideToastr({ timeOut: 4000, positionClass: 'toast-bottom-right', preventDuplicates: true })` | `app.config.ts:53` | Global `ngx-toastr` defaults, fronted by `NotificationsService` (§5). |

> **Gotcha — interceptor array order is a contract, not a style choice.** `[cacheInterceptor, tokenInterceptor]` (`app.config.ts:49`) means a cache hit short-circuits *before* any `Authorization` header is computed. See §4.

**API base URL.** Every service reads `environment.apiUrl` (no hardcoded origin). Dev = `http://localhost:8080` (`tesseraapp/src/environments/environment.ts:11`); production = `''` (relative, same-origin behind a reverse proxy) (`tesseraapp/src/environments/environment.production.ts:12`). `angular.json` `fileReplacements` swaps the file at build time, so services import one symbol with no runtime branching.

---

## 2. Route → component map

All routes are lazy via `loadComponent` (`app.routes.ts:11`). Public auth/verification routes carry **no guard**; feature routes use `authenticationGuard`; admin pages add `adminGuard` (always *after* `authenticationGuard`). Because `withComponentInputBinding()` is on, path params (`:id`, `:key`, `:invoiceNumber`) bind to component `@Input()`s.

| Path | Component | Guard | Line | Purpose |
|------|-----------|-------|------|---------|
| `login` | `LoginComponent` | 🔓 none | `app.routes.ts:13` | Email/password sign-in, SMS/TOTP MFA step, federated-provider buttons. |
| `verify` | `VerifyComponent` | 🔓 none | `:17` | Generic verify landing. |
| `resetpassword` | `ResetPasswordComponent` | 🔓 none | `:21` | Forgot-password request form. |
| `register` | `RegisterComponent` | 🔓 none | `:25` | New-account signup. |
| `user/verify/account/:key` | `VerifyComponent` | 🔓 none | `:32` | Matches the backend account-verification email link (`/user/verify/account/{uuid}`). |
| `user/verify/password/:key` | `VerifyComponent` | 🔓 none | `:36` | Matches the backend password-reset email link. |
| `oauth2/callback` | `Oauth2CallbackComponent` | 🔓 none | `:43` | Federated login landing; tokens or an MFA handoff arrive in the URL fragment (public — user is mid-auth). |
| `''` (full) | `HomeComponent` | 🔑 `authenticationGuard` | `:47` | Dashboard: paginated customer table + stats + insights. |
| `customers` | `CustomersComponent` | 🔑 `authenticationGuard` | `:53` | Customer list + search. |
| `customer/new` | `NewCustomerComponent` | 🔑 `authenticationGuard` | `:58` | Create-customer form. |
| `invoice/new` | `NewInvoiceComponent` | 🔑 `authenticationGuard` | `:63` | Create-invoice form. |
| `invoices` | `InvoicesComponent` | 🔑 `authenticationGuard` | `:68` | Invoice list. |
| `profile` | `ProfileComponent` | 🔑 `authenticationGuard` | `:73` | Self-service profile, settings, password, MFA, audit events. |
| `security` | `SecurityCenterComponent` | 🔑 `authenticationGuard` | `:80` | Account Security Center: TOTP enrollment + sessions/devices. **Plain auth, no admin guard** (self-service). |
| `customers/:id` | `CustomerDetailsComponent` | 🔑 `authenticationGuard` | `:85` | Single-customer detail/edit. |
| `users` | `UsersComponent` | 🔑🛡 `[authenticationGuard, adminGuard]` | `:94` | Admin user directory (FR-ADMIN-1/2/5). |
| `users/:id` | `UserDetailsComponent` | 🔑🛡 `[authenticationGuard, adminGuard]` | `:99` | Admin single-user management. |
| `roles` | `RolesMatrixComponent` | 🔑🛡 `[authenticationGuard, adminGuard]` | `:106` | Roles × permissions matrix (FR-RBAC-1/2). |
| `invoice/:id/:invoiceNumber` | `InvoiceDetailComponent` | 🔑 `authenticationGuard` | `:111` | Invoice detail (white-paper layout for PDF). |
| `billing` | `BillingComponent` | 🔑🛡 `[authenticationGuard, adminGuard]` | `:118` | Admin billing analytics (client-derived). |
| `services` | `ServicesCatalogComponent` | 🔑 `authenticationGuard` | `:125` | Service/app catalog — **all** authenticated users. |
| `analytics` | `AnalyticsComponent` | 🔑🛡 `[authenticationGuard, adminGuard]` | `:135` | Admin analytics hub (client-derived charts). |
| `**` | → redirect `/` | — | `:142` | Catch-all to the dashboard. |

Legend: 🔓 no guard · 🔑 requires a valid JWT · 🛡 additionally requires a staff-grade authority.

> **Gotcha — admin guard is frontend-only for the analytics/billing data.** `billing`/`analytics` gate the *route* behind `adminGuard`, but the GET endpoints they call (`/customer/stats`, `/customer/list`, `/customer/invoice/list`, `/customer/invoice/new`) fall through `SecurityConfig`'s broad `requestMatchers(GET, "/**").hasAnyAuthority("READ:USER","READ:CUSTOMER")` rule — they do **not** require an admin authority. A non-admin who calls them directly receives the same system-wide data. The `BillingComponent` docstring's "double-checked server-side" claim is inaccurate. See [security.md](security.md) and [flows/32](flows/32-dashboard.md).

---

## 3. Guards

Both guards are `CanActivateFn`s that inject `UserService` + `Router` and decide entirely from the locally decoded JWT. **Neither is a security boundary** — the backend re-enforces every authority at the URL + method level (NFR-SEC-4). A tampered token changes only what renders, never what the API returns.

| Guard | File | Allows | Denies → redirect |
|-------|------|--------|-------------------|
| `authenticationGuard` | `guard/authentication.guard.ts:17` | `userService.isAuthenticated()` true (token present **and** not expired) → `true` (`:21`) | otherwise `router.createUrlTree(['/login'])` (`:24`) — a redirect, not boolean `false`. Does **not** inspect authorities. |
| `adminGuard` | `guard/admin.guard.ts:20` | authenticated **and** `hasAnyAuthority('UPDATE:USER','UPDATE:ROLE')` → `true` (`:27`) | not authenticated → `/login` (`:24`); authenticated but lacking the authority → `/` home (`:30`), avoiding a 403-filled broken view. |

`adminGuard` is always listed **after** `authenticationGuard` in the route config (`app.routes.ts:95, 100, 107, 119, 136`), so an anonymous user is sent to login before the authority check runs.

---

## 4. Interceptors: order, behavior & cache strategy

Two functional `HttpInterceptorFn`s, registered `[cacheInterceptor, tokenInterceptor]` (`app.config.ts:49`). Angular runs them in array order on the request; the cache one runs first.

```
                 request                                   request
  HttpClient ──────────────▶ cacheInterceptor ──────────────▶ tokenInterceptor ──────────▶ server
                              │  bypass? evict? hit?           │  public route? attach Bearer
                              │                                │  401 → silent refresh + retry
            cache HIT: of(cached)  ◀── short-circuits here, tokenInterceptor never runs
```

### 4.1 `cacheInterceptor` — `interceptor/cache.interceptor.ts`

Decides, in order:

1. **Bypass routes** — `bypassRoutes = ['verify','login','register','refresh','resetpassword','new/password']` (`:47`). If `req.url` includes any, `next(req)` straight through, zero cache interaction (`:48`).
2. **Mutations & downloads** — if `req.method !== 'GET'` **or** url includes `'download'` → `httpCache.evictAll()` then `next(req)` (`:63-66`). Any POST/PUT/PATCH/DELETE wipes the **entire** cache; downloads are excluded so large blobs are never cached.
3. **Cache hit** — `httpCache.get(req.url)` found → `return of(cachedResponse)` immediately, **never calling `next()`** (`:77-82`). This is why `tokenInterceptor` is skipped on a hit.
4. **Cache miss** — `storeCacheResponse()` forwards via `next(req).pipe(tap(...))` and, on a final `HttpResponse` (and `method !== 'DELETE'`, a defensive guard), calls `httpCache.put(req.url, response)` (`:121-129`).

### 4.2 Cache strategy / TTL / invalidation

| Aspect | Behavior | Source |
|--------|----------|--------|
| Backing store | `private httpResponseCache: Record<string, HttpResponse<never>> = {}` — a plain in-memory object | `service/http-cache.service.ts:44` |
| **Key** | The **full request URL** including query string — each page/size/search variant is a distinct entry | `cache.interceptor.ts:77, 126` |
| **TTL / expiry** | ❌ **None.** No TTL, no max-size, no per-entry expiry — entries live until an `evictAll()` | `http-cache.service.ts:44, 92` |
| **Invalidation** | Coarse: **every** non-GET mutation calls `evictAll()` and wipes all entries. Trade-off: one extra round-trip on the next GET; benefit: guaranteed-fresh state after any write | `cache.interceptor.ts:63-66` |
| Single-key evict | ❌ Does not exist in active code — only a commented-out line in the Javadoc | `http-cache.service.ts:83` |
| Logout eviction | `UserService.logOut()` calls `httpCache.evictAll()` — essential because `/user/login` is in `bypassRoutes`, so a same-SPA user switch would otherwise serve the prior user's cached `/user/profile` | `user.service.ts:405-409` |

> **Why keying on URL only is safe here:** only GETs ever reach the cache-store path (step 4), so method/header keying is unnecessary. Two callers hitting the same GET URL share one entry until any mutation evicts all. `HttpCacheService` carries a TODO to move caching to the backend (Cache-Control/ETag/Redis) (`http-cache.service.ts:25`).

### 4.3 `tokenInterceptor` — `interceptor/token.interceptor.ts`

Attaches the access token and performs a silent, single-flight refresh-and-retry on 401.

- **Module-level shared state** (persists across requests): `let isTokenRefreshing = false` and `const refreshTokenSubject = new BehaviorSubject(null)` (`:11-12`).
- **Public skip list** — `publicRoutes = ['login','register','verify','resetpassword','refresh']` (`:49`). If `req.url` includes any, `next(req)` with **no** `Authorization` header (`:51`). `'verify'` deliberately also covers `/user/verify/totp` and `/user/verify/code` (they carry no session).
- **Otherwise** clone with `Authorization: Bearer <localStorage TOKEN>` via `addAuthorizationTokenHeader()` (`:55, 79-81`).
- **On 401** → `handleRefreshToken()` (`:57-59`): if no refresh in flight, set the flag, push `null`, call `userService.refreshToken$()` (which rewrites both tokens), then `switchMap` retries the original request with the new access token; on refresh failure clear both tokens and rethrow (app redirects to login) (`:101-122`).
- **Concurrent 401s** wait on `refreshTokenSubject.pipe(filter(non-null), take(1), switchMap(retry))` — **single-flight**, no thundering herd (`:124-128`). Non-401 errors are rethrown unchanged (`:60`).

> **Gotcha — lockstep lists.** `tokenInterceptor.publicRoutes` (`:49`), `cacheInterceptor.bypassRoutes` (`cache.interceptor.ts:47`), and the backend's `Constants.PUBLIC_URLS`/`PUBLIC_ROUTES` must stay aligned. A route public in one list but not another breaks on a stale `Authorization` header. See [security.md](security.md).

---

## 5. Services API reference

All services are `providedIn: 'root'` singletons. Every `$`-suffixed method returns `Observable<CustomHttpResponseInterface<T>>` (the backend envelope, §7) and ends with `catchError(this.handleError)`; `handleError` normalizes to `Observable<never>`, preferring the server's `error.error.reason` (`user.service.ts:419-437`, `admin-user.service.ts:112`, `customer.service.ts:192`). `server = environment.apiUrl` on each.

### `UserService` — `service/user.service.ts`

Central facade for auth, self-service, MFA, sessions, and federation. Owns the `localStorage` token side-effects and JWT decode/expiry (`JwtHelperService`) so the interceptor and components share one source of truth.

| Method | HTTP | Path | Responsibility | Line |
|--------|------|------|----------------|------|
| `verifyCode$(email, code)` | GET | `/user/verify/code/{email}/{code}` | Complete SMS 2FA after login | `:39` |
| `verifyAccount$(key, type)` | GET | `/user/verify/{type}/{key}` | Resolve account/password email link | `:44` |
| `setNewPassword$(form)` | PUT | `/user/new/password` | Finish forgot-password reset (body = userID + passwords) | `:63` |
| `login$(email, password)` | POST | `/user/login` | Authenticate; response carries tokens or MFA challenge | `:76` |
| `register$(user & {password})` | POST | `/user/register` | Create account | `:91` |
| `requestPasswordReset$(email)` | GET | `/user/resetpassword/{email}` | Email a reset link | `:106` |
| `profile$()` | GET | `/user/profile` | Fetch authenticated user + roles | `:117` |
| `userEvents$(page, size=10)` | GET | `/user/events?page&size` | Page own audit events | `:130` |
| `update$(user)` | PATCH | `/user/update` | Update own profile fields | `:141` |
| `refreshToken$()` | GET | `/user/refresh/token` | Sends `Authorization: Bearer <REFRESH_TOKEN>`; **tap rewrites both TOKEN + REFRESH_TOKEN** in localStorage | `:153` (`:161-164`) |
| `updatePassword$({current,new,confirm})` | PATCH | `/user/update/password` | Change password; **tap swaps in the new token pair** | `:182` (`:188-192`) |
| `updateAccountSettings$({enabled, notLocked})` | PATCH | `/user/update/settings` | Toggle own account flags | `:209` |
| `updateProfileImage$(formData)` | PATCH | `/user/update/image` | Upload avatar (multipart key `image`) | `:224` |
| `toggleMFA$()` | PATCH | `/user/update/togglemfa` | Flip SMS-MFA flag (needs a phone) | `:236` |
| `verifyTotp$(challenge, code)` | POST | `/user/verify/totp` | Complete a TOTP-gated login (challenge binds it to the password step) | `:253` |
| `totpSetup$()` | POST | `/user/totp/setup` | Begin authenticator enrollment (secret + QR) | `:266` |
| `totpEnable$(code)` | POST | `/user/totp/enable` | Confirm enrollment; returns one-time recovery codes | `:279` |
| `totpDisable$(code)` | POST | `/user/totp/disable` | Disable authenticator (needs live code/recovery) | `:292` |
| `totpStatus$()` | GET | `/user/totp/status` | Enabled flag + remaining recovery codes | `:303` |
| `sessions$()` | GET | `/user/sessions` | List live refresh sessions + current family | `:314` |
| `revokeSession$(family)` | DELETE | `/user/sessions/{family}` | Revoke one device | `:326` |
| `revokeOtherSessions$()` | DELETE | `/user/sessions` | "Log out everywhere else" | `:337` |
| `federatedProviders$()` | GET | `/oauth2/providers` | Discover configured IdPs for the login screen | `:350` |

Non-`$` helpers:

| Member | Returns | Responsibility | Line |
|--------|---------|----------------|------|
| `initiateFederatedLogin(provider)` | `void` | `window.location.assign('/oauth2/authorization/{provider}')` — a **full-page redirect**, not XHR (OAuth2 code flow is a redirect chain) | `:365` |
| `isAuthenticated()` | `boolean` | Token present **and** not expired (`jwtHelper`) | `:369` |
| `hasAnyAuthority(...authorities)` | `boolean` | Decodes the `authorities` claim; true if unexpired and grants any. Used by `adminGuard` + navbar | `:385` |
| `logOut()` | `void` | Clears TOKEN + REFRESH_TOKEN **and** `httpCache.evictAll()` (cross-session leak prevention) | `:405` |
| `handleError(error)` (private) | `Observable<never>` | Prefers `error.error.reason`; falls back to status/message | `:419` |

> **History:** `updateUserRole$` was intentionally removed from `UserService` (FR-RBAC-4) — users cannot change their own role. Admin role changes go through `AdminUserService` (`user.service.ts:197-199`).

### `AdminUserService` — `service/admin-user.service.ts`

Facade for the backend `AdminUserController` under `/admin/user` (authority-gated `UPDATE:USER` / `UPDATE:ROLE`). Kept **separate from `UserService`** so admin-on-other-user ops never share a code path with self-service (FR-RBAC-4). All mutations are PATCH, so `cacheInterceptor` evicts the whole GET cache on each — directory/detail views always refetch fresh.

| Method | HTTP | Path | Responsibility | Line |
|--------|------|------|----------------|------|
| `users$(page=0, searchTerm='', size=10)` | GET | `/admin/user/list?page&size&searchTerm` (URI-encoded) | Paged, searchable user directory (FR-ADMIN-1) | `:38` |
| `user$(id)` | GET | `/admin/user/{id}` | Single-user view: profile, role, state, first events page (FR-ADMIN-2) | `:52` |
| `updateUserRole$(id, roleName)` | PATCH | `/admin/user/{id}/role/{roleName}` | Reassign role; backend rejects self-target (FR-ADMIN-3) | `:66` |
| `updateAccountSettings$(id, {enabled, notLocked})` | PATCH | `/admin/user/{id}/settings` | Change another user's account state (FR-ADMIN-4) | `:81` |
| `userEvents$(id, page=0, size=10)` | GET | `/admin/user/{id}/events?page&size` | Page a managed user's audit history | `:98` |
| `handleError` (private) | — | — | Same contract as `UserService` | `:112` |

### `CustomerService` — `service/customer.service.ts`

Facade for all customer + invoice calls.

| Method | HTTP | Path | Responsibility | Line |
|--------|------|------|----------------|------|
| `stats$()` | GET | `/customer/stats` | System-wide totals. **Currently unused** — stats ride along on `customers$` (TODO to wire) | `:44` |
| `customers$(page=0, size=20)` | GET | `/customer/list?page&size` | Paged customer list (+ embedded stats/statusBreakdown) | `:56` |
| `customerId$(id)` | GET | `/customer/get/{id}` | Single customer + auth user | `:73` |
| `updateCustomer$(customer)` | PUT | `/customer/update/{customer.id}` | Update; id from path, body id ignored (needs `UPDATE:CUSTOMER` or `UPDATE:USER`) | `:89` |
| `newCustomer$(customer)` | POST | `/customer/create` | Create customer | `:101` |
| `invoices$(page=0, size=20)` | GET | `/customer/invoice/list?page&size` | Paged invoice list | `:112` |
| `invoice$(invoiceId)` | GET | `/customer/invoice/get/{id}` | Invoice + its customer + auth user | `:128` |
| `newInvoice$()` | GET | `/customer/invoice/new` | Customer dropdown + available services for the new-invoice form | `:139` |
| `addInvoiceToCustomer$(customerId, invoice)` | POST | `/customer/invoice/addtocustomer/{customerId}` | Create + link invoice | `:151` |
| `downloadCustomerReport$()` | GET | `/customer/download/report` | `Observable<HttpEvent<Blob>>` (`reportProgress`, `observe:'events'`, blob) | `:156` |
| `downloadInvoiceReport$()` | GET | `/customer/invoice/download/report` | Same, for invoices | `:161` |
| `searchCustomers$(name, page=0, size=20)` | GET | `/customer/search?name&page&size` (URI-encoded) | Name-substring search | `:177` |
| `handleError` (private) | — | — | Same contract as `UserService` | `:192` |

### `ThemeService` — `service/theme.service.ts`

Owns dark/light color-mode as a signal, mirrored to the document root `data-bs-theme` attribute (consumed by Bootstrap 5.3 + the custom token layer in `styles.css`).

| Member | Kind | Responsibility | Line |
|--------|------|----------------|------|
| `type Theme = 'dark' \| 'light'` | type | The two modes | `:4` |
| `STORAGE_KEY = 'sc-theme'` | const | localStorage key | `:7` |
| `_theme` | private signal | Backing state, init via `readInitial()` | `:29` |
| `theme` | readonly signal | Public read-only view (navbar reads it) | `:32` |
| `constructor()` | — | Re-asserts the attribute so Bootstrap + tokens match the signal | `:34-38` |
| `toggle()` | method | Flip dark↔light (wired to navbar `toggleTheme()`) | `:44` |
| `set(theme)` | method | Update signal + DOM + localStorage (swallows storage errors for private mode) | `:53` |
| `apply(theme)` (private) | method | Sets `data-bs-theme` on `documentElement` + updates `meta[theme-color]` (`#0a0c12` dark / `#f5f6fb` light) | `:68` |
| `readInitial()` (private) | method | localStorage → `prefers-color-scheme` → default `dark` | `:82` |

> **Note:** the *pre-paint* initial value is set by an inline script in `index.html` to avoid a flash of the wrong theme; this service mirrors it into a signal for runtime reactivity (`theme.service.ts:18-22`).

### `NotificationsService` — `service/notifications-service.ts`

Thin app-wide facade over `ngx-toastr` so the toast library can be swapped in one place. Components call `notification.onError(error)` inside the `catchError` branch of the `DataState` pattern (§6).

| Method | Delegates to | Line |
|--------|-------------|------|
| `onSuccess(message)` | `toastr.success` | `:14` |
| `onError(message)` | `toastr.error` | `:18` |
| `onInfo(message)` | `toastr.info` | `:22` |
| `onWarning(message)` | `toastr.warning` | `:26` |

### `HttpCacheService` — `service/http-cache.service.ts`

In-memory cache backing `cacheInterceptor` (cache-aside). See §4.2 for the strategy. Methods: `put(key, response)` (`:55`), `get(key)` → response or `undefined`/`null` on miss (`:70`), `evictAll()` resets the map to `{}` (`:92`), `logCache()` debug dump (`:103`). Stores `HttpResponse<never>` (body-agnostic; callers cast). No single-key evict in active code (`:83`).

---

## 6. State management: DataState, signals & the RxJS trio

Async UI state is a three-value machine: `DataState` (`enumeration/datastate.enum.ts`) wrapped in `GlobalStateInterface<T>` (`interface/global-state.interface.ts:4-8`), held in an Angular `signal`. Components are `ChangeDetectionStrategy.OnPush`; the signal drives change detection.

```
                          startWith(LOADING)
   (re)fetch ────────────────────────────────▶  LOADING ──┐
                                                           │ map(response)
                                                           ▼
                                                        LOADED  (appData = response)
                                                           │ catchError
                                                           ▼
                                                        ERROR   (error message; stream stays alive)
```

### The canonical pattern (verified in `HomeComponent`)

```ts
// state signal + template handle to the enum
homeState = signal<GlobalStateInterface<...>>({ dataState: DataState.LOADING });  // home.component.ts:40
readonly DataState = DataState;                                                   // :37

ngOnInit(): void {
  combineLatest([this._currentPage$, this._pageSize$])               // :66  toObservable(signal) bridges :53-54
    .pipe(
      switchMap(([page, size]) =>                                    // :68  cancels stale in-flight requests
        this.customerService.customers$(page, size).pipe(
          map(response => ({ dataState: DataState.LOADED, appData: response })),  // :70-74
          startWith({ dataState: DataState.LOADING }),                            // :75  emits LOADING on every refetch
          catchError(error => {                                                   // :76-79
            this.notification.onError(error);
            return of({ dataState: DataState.ERROR, error });        // swallow → stream never dies
          }),
        ),
      ),
      takeUntilDestroyed(this.destroyRef),                           // :82  no manual ngOnDestroy
    )
    .subscribe(state => this.homeState.set(state));                  // :84
}
```

The signature pieces:

| Piece | Role | Source |
|-------|------|--------|
| `signal<GlobalStateInterface<T>>` | Holds the current state; `.set()` from the subscription | `home.component.ts:40, 84` |
| `toObservable(signal)` + `combineLatest` | Bridge input signals (page, size, search term) into one stream; any change refetches | `home.component.ts:53-54, 66` |
| `switchMap` | Cancels the previous in-flight request when a new emission arrives | `home.component.ts:68` |
| `map → LOADED` | Wrap a successful response | `home.component.ts:70-74` |
| `startWith(LOADING)` | Emit `LOADING` immediately on every (re)fetch (spinner) | `home.component.ts:75` |
| `catchError → of(ERROR)` | Toast + swallow into `ERROR` so the outer stream survives | `home.component.ts:76-79` |
| `takeUntilDestroyed(destroyRef)` | Auto-unsubscribe on destroy | `home.component.ts:82` |

Templates branch on the state with `@switch`/`@if` against `DataState.LOADING/LOADED/ERROR` to render spinner vs data vs error. `HomeComponent.report()` preserves `appData: this.data()` while `LOADING` so the table stays visible under the download progress bar (`home.component.ts:97`).

### Variations

- **`LoginComponent`** uses `LoginStateInterface` (extends the state shape with `isUsingMfa` / `mfaMethod` / `loginSuccess` / `phone`) and an **imperative** `.subscribe({ next, error })` that sets `LOADING`/`LOADED`/`ERROR` by hand instead of the `map`/`startWith`/`catchError` pipe (`login.component.ts:29-32, 72-79, 95-114`). The federated-provider discovery failing must never block password login, so its error branch just empties the provider list.
- The pattern is pervasive: `DataState` + `startWith` + `catchError` appear across the feature components (the grouped analytics/billing/services pages compute their charts in `computed()` signals layered on the same state).

---

## 7. Enumerations & envelope interfaces

| Enum / interface | Members | Source |
|------------------|---------|--------|
| `DataState` | `LOADING='LOADING_STATE'`, `LOADED='LOADED_STATE'`, `ERROR='ERROR_STATE'` | `enumeration/datastate.enum.ts:1-5` |
| `Key` | `TOKEN='[KEY] TOKEN'`, `REFRESH_TOKEN='[REFRESH] REFRESH_TOKEN'` — the two `localStorage` keys for the JWT pair | `enumeration/key.enumeration.ts:1-5` |
| `EventType` | 15 values mirroring the backend `EventType`: `LOGIN_ATTEMPT(_FAILURE/_SUCCESS)`, `PROFILE_UPDATE`, `PROFILE_PICTURE_UPDATE`, `ROLE_UPDATE`, `ACCOUNT_SETTINGS_UPDATE`, `PASSWORD_UPDATE`, `MFA_UPDATE`, `FEDERATED_LOGIN`, `TOTP_ENROLLED`, `TOTP_DISABLED`, `RECOVERY_CODE_USED`, `SESSION_REVOKED`, `TOKEN_REUSE_DETECTED` | `enumeration/event-type.enum.ts:3-19` |
| `GlobalStateInterface<T>` | `{ dataState: DataState; appData?: T; error?: string }` | `interface/global-state.interface.ts:4-8` |
| `CustomHttpResponseInterface<T>` | `{ statusCode; message; data?: T; timestamp; reason?; devMessage?; status }` — the frontend mirror of the backend `HttpResponse` envelope `{ timeStamp, statusCode, status, message, data }` (errors add `reason`) | `interface/customhttpresponse.interface.ts:1-10` |

> **Note — token storage.** The access/refresh tokens live in `localStorage` keyed by `Key.TOKEN` / `Key.REFRESH_TOKEN` (no httpOnly cookies). `EventType` values are kept verbatim even when unused in the UI because the backend can send any of them in event-history responses (`event-type.enum.ts:1-2`).

---

## 8. Internationalization (Transloco)

Six locales — English, Spanish, French, German, Portuguese, Simplified Chinese — switchable at
runtime from the navbar, with the choice persisted like the theme.

**Why runtime, not `@angular/localize`.** The built-in tooling resolves translations at *compile*
time and emits one bundle per language. That is the right choice for a public marketing site, and
the wrong one here: switching language would mean loading a different build, so the user loses their
place. Transloco swaps a JSON dictionary in place, so the switch is instant and the current view
survives it. The cost — dictionaries are not tree-shaken — is a few kilobytes, fetched lazily and
cached per language.

| Piece | Location |
|---|---|
| Provider config | `app.config.ts` (`provideTransloco`) |
| Dictionary loader | `service/transloco-loader.ts` |
| Active-language state | `service/language.service.ts` (mirrors `ThemeService`) |
| Dictionaries | `public/assets/i18n/{en,es,fr,de,pt,zh}.json` |

**Conventions that matter:**

- **`fallbackLang: 'en'` + `useFallbackTranslation`.** A key missing from a translation renders the
  English text, not the raw key. This is what makes an incremental translation pass safe.
- **One `*transloco="let t"` scope per template**, usually on the outermost element. Where the
  template's top level is `@if`/`@switch` control flow rather than an element, wrap the whole file
  in `<ng-container *transloco="let t">` — a scope on one branch is invisible to the others, and
  the symptom is a compile error reading *"Property 't' does not exist"*.
- **Translate whole sentences, never fragments.** The home greeting emits a complete phrase per
  branch rather than `"Welcome back"` + a name fragment, because word order and punctuation around
  a name are not universal; splitting a sentence silently forces every language into English's
  arrangement.
- **Languages are labelled in their own language** ("Español", never "Spanish"). A user stranded in
  a language they cannot read needs an exit they can recognise.
- **RTL locales are deliberately absent.** Arabic and Hebrew need `dir="rtl"` plus a pass converting
  the stylesheet's physical properties (`margin-left`, `float`, `text-align: left`) to logical ones.
  Shipping one before that work renders a visibly broken page, which serves those readers worse than
  not offering the language.
- Component-level strings (toasts, command-palette labels) resolve through `TranslocoService`
  injected into the component, not the template pipe.

---

## 9. Capability-level UI gating

Route guards answer "may you open this page?". These answer "may you use this control?" — so a
refusal is felt *before* the click rather than as a 403 on submit.

| Piece | Purpose |
|---|---|
| `*appHasAuthority` | Structural directive — renders content only for a held authority; supports `; else` for a read-only substitute |
| `[appRequiresAuthority]` | Attribute directive — leaves the control visible but inert, with `aria-disabled`, a `.is-restricted` class, and a `title` naming the missing capability |
| `capabilityGuard` | Route-data-driven gate (`requiredAuthorities` + `deniedActionKey`); **fails closed** when a route declares nothing |

**Hide or disable?** Remove a control when its presence is pure noise (a Delete button a viewer can
never use). Disable it when absence would read as a rendering bug — a form whose submit button
simply is not there. Both directives ship because the choice is per-control.

> **Authority flags must be getters, not fields.** `hasAnyAuthority` returns `false` for an
> **expired** token, not only a missing authority. A flag captured once at construction latches
> whatever was true then — and on a page refresh that is usually an expired token, so an admin sees
> the non-admin view until something reconstructs the component. Write
> `get isAdmin() { return this.userService.hasAnyAuthority(...); }`. `UserService` memoises the
> decode on the token string, so per-change-detection evaluation is a string compare.

**None of this is a security boundary (NFR-SEC-4).** The authorities come from a token the user
controls; the backend re-derives them from the database on every request and enforces them at both
the URL and method level. These directives change what renders, never what the API permits.

---

## 10. Command palette

⌘/Ctrl+K opens a type-to-filter launcher, mounted once in `AppComponent` beside the router outlet so
it is reachable from every route. It self-gates on authentication and **rebuilds its command list
from the live token on every open**, so admin destinations and creation commands appear only for
tokens that carry the matching authorities.

Anything outside its subtree opens it through `CommandPaletteService` (the navbar's search-styled
trigger does). That indirection exists because the palette and the navbar sit in different branches
of the component tree — and because dispatching a synthetic `Ctrl+K` at `document` would couple the
button to a keybinding rather than to an intent.

---

## 11. Known limitations, gotchas & gap register

Status legend: ✅ done/wired · ⚠️ built-but-not-production-ideal · ❌ open/planned.

| Item | Status | Detail | Source |
|------|--------|--------|--------|
| HTTP cache TTL / per-key invalidation | ❌ | No TTL, no max-size, no single-key evict; only coarse `evictAll()` on any mutation/logout. TODO to move caching to the backend | `http-cache.service.ts:25, 44, 83` |
| Cache keyed by URL only | ⚠️ | Safe today (only GETs are stored) but two callers of the same GET URL share one entry | `cache.interceptor.ts:77, 126` |
| `adminGuard` is frontend-only for analytics/billing data | ⚠️ | `/customer/stats`, `/customer/list`, `/customer/invoice/list`, `/customer/invoice/new` need only `READ:USER`/`READ:CUSTOMER` server-side; the `BillingComponent` "double-checked server-side" docstring is inaccurate | `guard/admin.guard.ts:20`; backend `SecurityConfig` GET `/**` rule |
| Lockstep lists | ⚠️ | `tokenInterceptor.publicRoutes`, `cacheInterceptor.bypassRoutes`, and backend `PUBLIC_URLS`/`PUBLIC_ROUTES` must stay aligned or a stale `Authorization` header breaks a "public" route | `token.interceptor.ts:49`, `cache.interceptor.ts:47` |
| `CustomerService.stats$()` | ❌ | Declared but unused — stats come embedded in `customers$`; TODO to wire into `StatsComponent` | `customer.service.ts:44` |
| Navbar user via `@Input` | ⚠️ | Navbar receives the user from the parent's customer-list response rather than calling `profile$()` itself; documented TODO to decouple | `shared/navbar/navbar.component.ts:14-23` |
| Frontend specs | ❌ | Zero frontend unit tests in `src/`; security-critical guard/interceptor behavior is untested on the client | — |
| `data-bs-theme` double-apply | ✅ | Intentional: inline `index.html` script sets it pre-paint; `ThemeService` re-asserts in its constructor | `theme.service.ts:34-38` |

> **Security standing rule (client side).** The guards and `hasAnyAuthority()` are usability aids only (NFR-SEC-4) — the backend independently enforces every authority. Never treat a client-side check as a boundary; never surface backend `reason` text that would reveal whether an email/account exists (the envelope's generic messages preserve enumeration-safety). See [security.md](security.md).
