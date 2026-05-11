# SecureCapita Frontend

Angular standalone application that powers the SecureCapita UI. It integrates with the Spring Boot backend for authentication, profile management, and future customer/invoice features.

TODO - add admin access to allow specific users to view all users in the server/system/database.
TODO - spruce up the UI with Angular Material or Tailwind CSS for a more polished look, maybe utilize the stat database table to show some cool charts on the dashboard.
This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 21.2.3.

- Standalone Angular components (no AppModule)
- JWT access/refresh tokens stored in localStorage
- HTTP interceptor attaches access tokens and refreshes on 401
- Template-driven forms for login, profile, and verification flows

To start a local development server, run `npm run start` and navigate to `http://localhost:4200`. The frontend will proxy API requests to the Spring Boot backend at `http://localhost:8080`.

## Architecture Snapshot

```
Browser
  │
  │ 1) Form submit (login/profile/update)
  ▼
Angular Component (login/profile/home)
  │
  │ 2) Calls UserService via HttpClient
  ▼
UserService
  │
  │ 3) HttpClient request
  ▼
HTTP Interceptor (token-interceptor)
  │  ├─ Adds Authorization: Bearer <access_token>
  │  └─ On 401 → refreshToken$ → retry request
  ▼
Spring Boot API (http://localhost:8080)
```

## Key Frontend Files

## Code scaffolding

```
securecapitaapp/src/app
├── app.component.ts           # Root shell
├── app.config.ts              # Standalone providers (router, HttpClient, interceptor)
├── app.routing-module.ts      # Route definitions
├── interceptor/token-interceptor.ts
├── service/user.service.ts
├── component/
│   ├── login/                 # Login + MFA flow
│   ├── profile/               # Profile + password update
│   ├── navbar/                # Authenticated nav + logout
│   ├── verify/                # Account/password verification landing
│   ├── resetpassword/         # Reset password view
│   ├── register/              # Registration view
│   ├── home/                  # Dashboard shell + stats
│   ├── customer(s)/           # Customer placeholders
│   └── stats/                 # Stats widget
└── interface/, enumeration/   # Shared types and enums
```

## Routes

```
/                      → HomeComponent
/login                 → LoginComponent
/register              → RegisterComponent
/resetpassword         → ResetPasswordComponent
/verify                → VerifyComponent
/user/verify/account/:key  → VerifyComponent
/user/reset/password/:key  → VerifyComponent
/profile               → ProfileComponent
/customers             → CustomersComponent
/customer              → CustomerComponent
```

## Auth Flow (Frontend)

```
LoginComponent.submit()
  └─ UserService.login$()
     └─ POST /user/login
        ├─ access_token + refresh_token
        └─ (optional) using2FA = true → verify code

Token Interceptor
  ├─ Adds access token to requests
  └─ 401 → UserService.refreshToken$() → retry request
```

## Running Locally

```bash
npm install
npm run start
To build the project and store the build articats in the /dist folder, run:
npm run build
```

Frontend runs at: `http://localhost:4200`

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
npm run lint
npm test are other commands that can be ran
```

## Notes

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.

- The backend issues JWTs where `sub` is the user ID (see `TokenProvider.java`).
- Tokens are stored under keys defined in `Key` enum (see `src/app/enumeration`).
- Some customer/invoice features are placeholders until the backend endpoints are ready.

