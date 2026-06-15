> 🗑️ **DEPRECATED — MARKED FOR DELETION.** This file is corrupted/jumbled and out of date. Superseded by [`documentation/architecture.md` §6](architecture.md#6-frontend-architecture) and [`securecapitaapp/README.md`](../securecapitaapp/README.md). Retained temporarily pending removal; do not update or rely on it.

# Frontend Documentation (SecureCapita)

This document describes the current Angular frontend in `securecapitaapp`, including structure, flows, and how it
integrates with the Spring Boot backend.

## Tech Stack

- Angular standalone app (no AppModule)
- RxJS for async state
- Template-driven forms
- Bootstrap + Bootstrap Icons

## Architecture Overview

### When Backend is Fully Developed:

1. Add any NEW components/classes created
2. Document any Spring Security enhancements
3. Add DTOs or models not yet documented
4. Document any new endpoints created
5. Update interaction maps if flow changed

**How to do this:**

```
1. Identify new files created since current documentation
2. Contact me and provide:
   - List of new Java files
   - Any architecture changes
   - New endpoints added
3. Add comprehensive JavaDoc + update guides
User Interaction
  ▼
Component (login/profile/home)
  ▼
UserService (HttpClient)
  ▼
Token Interceptor
  ├─ Attach access token
  └─ 401 → refresh token → retry
  ▼
Spring Boot API
```

## Folder Structure

```
angularSpringBootFullStack/
├── backend/                    # Existing backend
│   ├── src/main/java/...
│   ├── pom.xml
│   └── [All backend docs we just created]
│
└── frontend/                   # New Angular app
    ├── src/
    │   ├── app/
    │   │   ├── services/       # API communication
    │   │   ├── components/     # UI components
    │   │   ├── models/         # TypeScript interfaces
    │   │   ├── guards/         # Route guards, auth guards
    │   │   ├── interceptors/   # HTTP interceptors
    │   │   ├── store/          # NgRx or state management
    │   │   ├── pipes/          # Custom pipes
    │   │   └── utils/          # Utility functions
    │   └── assets/
    ├── package.json
    └── angular.json
    
    securecapitaapp/src/app
├── component/
│   ├── login/          # Login + MFA flow
│   ├── profile/        # Profile + password update
│   ├── navbar/         # Authenticated nav + logout
│   ├── verify/         # Account/password verification landing
│   ├── resetpassword/  # Password reset view
│   ├── register/       # Registration view
│   ├── home/           # Dashboard shell + stats
│   ├── customer(s)/    # Customer placeholders
│   └── stats/          # Stats widget
├── service/
│   └── user.service.ts # Auth, profile, refresh token API calls
├── interceptor/
│   └── token.interceptor.ts
├── enumeration/        # DataState, Key, EventType
└── interface/          # API response and UI state contracts
```

## Key Flows

### Login + MFA

```
frontend/
├── FRONTEND_DOCUMENTATION_INDEX.md
├── ANGULAR_ARCHITECTURE_GUIDE.md
├── STATE_MANAGEMENT_GUIDE.md
├── API_INTEGRATION_GUIDE.md
├── AUTHENTICATION_FLOW_FRONTEND.md
└── COMPONENT_INTERACTIONS.md
LoginComponent.login()
  ├─ POST /user/login
  ├─ using2FA = true → show MFA prompt
  └─ store access/refresh tokens → route to /
```

### Token Refresh

```
HTTP Interceptor → 401
  └─ UserService.refreshToken$()
     ├─ GET /user/refresh/token (Bearer refresh_token)
     ├─ store new tokens
     └─ retry original request
```

#### 2. **Key Feature Areas**

```
- Authentication flow (login, logout, token refresh)
- API integration (how frontend calls backend)
- State management (if any)
- Component hierarchy (parent-child relationships)
- Form handling (reactive forms, template forms)
- Error handling
```

#### 3. **File Listing**

```
Provide: tree/list of all .ts, .html, .css files in src/app/
Example:
src/app/
  ├── services/
  │   ├── auth.service.ts
  │   ├── user.service.ts
  │   └── api.service.ts
  ├── components/
  │   ├── login/
  │   │   ├── login.component.ts
  │   │   ├── login.component.html
  │   │   └── login.component.css
  etc.
```

```
ProfileComponent.updateProfile()
  └─ PATCH /user/update
```

---

## What Frontend Documentation Will Include

### 1. **ANGULAR_ARCHITECTURE_GUIDE.md** (Similar to Spring Security Guide)

- Complete Angular architecture explanation
- Dependency injection flow
- Change detection strategies
- RxJS/Observables patterns
- Component lifecycle hooks
- Data binding mechanisms

### 2. **Authentication Flow (Frontend)**

```
User Login Form
    ↓
AuthService.login(credentials)
    ↓
HttpClient.post(/user/login, credentials)
    ↓
Backend returns JWT token
    ↓
Store token in localStorage
    ↓
Set Authorization header for future requests
    ↓
Redirect to dashboard
```

### 3. **API Integration Documentation**

- How services call backend
- HTTP interceptors (adding auth headers)
- Error handling
- Request/response flow
- JWT token refresh mechanism

### 4. **State Management Documentation** (if using NgRx/Akita)

- Actions → Effects → Reducers → Selectors flow
- Store structure
- Dispatch patterns
- Selector usage

### 5. **Component Interaction Maps**

```
AppComponent
├── LoginComponent
│   └── Calls: AuthService.login()
├── DashboardComponent
│   ├── UserListComponent
│   │   └── Calls: UserService.getUsers()
│   └── ProfileComponent
│       └── Calls: UserService.getProfile()
```

### 6. **Detailed Component Documentation**

For each component:

- Purpose and responsibility
- Inputs and Outputs
- Services it uses
- Observables/subscriptions
- Form validation
- User interactions

### 7. **Service Documentation**

For each service:

- What API endpoints it calls
- What data it transforms
- Error handling
- Caching strategy (if any)
- Observable usage patterns

### 8. **Route Guard Documentation**

```
AuthGuard → checks if user logged in
AdminGuard → checks if user is admin
CanDeactivateGuard → prevents unsaved changes
```

### 9. **HTTP Interceptor Documentation**

```
AuthInterceptor:
  - Adds Authorization header with JWT token
  - Handles 401 responses (refresh token)
  - Handles 403 responses (redirect to login)

ErrorInterceptor:
  - Catches all HTTP errors
  - Formats error messages
  - Shows user-friendly notifications
```

### 10. **TypeScript Interfaces/Models Documentation**

```
User interface → maps to backend User
Role interface → maps to backend Role
UserDTO interface → what backend returns
Permission enum → auth permissions
ProfileComponent.updatePassword()
  └─ PATCH /user/update/password
  └─ backend returns new tokens → stored automaticall
```

## Routes

### For .ts Files (Services, Components, etc.)

```typescript
/**
 * ═══════════════════════════════════════════════════════════════════════
 * AuthService - Handles all authentication operations
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Responsible for:
 * - User login/logout
 * - JWT token storage and retrieval
 * - Token refresh mechanism
 * - Authentication state management
 *
 * Dependencies injected:
 * - HttpClient: Makes HTTP requests to backend /user/login endpoint
 * - Router: Navigates after successful/failed login
 * - LocalStorageService: Stores/retrieves JWT token
 *
 * Usage in components:
 * @Component(...)
 * class LoginComponent {
 *   constructor(private authService: AuthService) {}
 *
 *   login(email, password) {
 *     this.authService.login(email, password).subscribe(
 *       response => navigateToDashboard(),
 *       error => showErrorMessage(error)
 *     );
 *   }
 * }
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  /**
   * Authenticates user with provided credentials
   *
   * Flow:
   * 1. Calls backend POST /user/login with credentials
   * 2. Backend returns JWT token + user data
   * 3. Stores token in localStorage
   * 4. Adds Authorization header to all future HTTP requests
   * 5. Component navigates to dashboard
   *
   * @param email user's email
   * @param password user's password
   * @returns Observable<{token: string, user: User}>
   */
  login(email: string, password: string): Observable<any> {
    // implementation
  }
}
```

### For Components

```typescript
/**
 * LoginComponent - User authentication UI
 *
 * Template binds to:
 * - loginForm: Reactive form with email and password validators
 * - @Output() loginSuccess: Emits when login succeeds
 *
 * Interaction flow:
 * 1. User enters credentials in form
 * 2. Form validation checks (required, email format, password length)
 * 3. On submit: calls authService.login(credentials)
 * 4. Backend responds with JWT token
 * 5. Component emits loginSuccess event
 * 6. App routing navigates to dashboard
 *
 * Error handling:
 * - Invalid credentials → shows "Email or password incorrect"
 * - Network error → shows "Connection failed, please try again"
 * - 401 from backend → shows custom error from response
 */
@Component({ ... })
export class LoginComponent {
  onSubmit() {
    // implementation
  }
}

/                      → HomeComponent
/login                 → LoginComponent
/register              → RegisterComponent
/resetpassword         → ResetPasswordComponent
/verify                → VerifyComponent
/user/v
erify / account /
:
key  → VerifyComponent
/ user / reset / password /
:
key  → VerifyComponent
/ profile               → ProfileComponent
/ customers             → CustomersComponent
/ customer              → CustomerComponent
```

## State and Error Handling

- Components use `DataState` enum to expose LOADING/LOADED/ERROR states.
- `UserService.handleError()` normalizes backend errors to a single message.
- The interceptor retries once after refresh; if refresh fails, it clears tokens.

## Diagrams

### Component-to-Service Map

```
I'm ready to add comprehensive documentation to my Angular frontend.

Frontend Setup:
- Angular version: 17.0
- State management: NgRx (or none)
- Authentication: JWT in localStorage
- HTTP client: HttpClientModule
- Routing: Lazy loading enabled

Key Features Implemented:
- Login component with form validation
- Dashboard with user list
- Profile page
- JWT token refresh mechanism
- Error handling with interceptor
- Role-based access control

Files to document:
- 12 services in src/app/services/
- 8 components in src/app/components/
- 2 HTTP interceptors
- 3 route guards
- 5 TypeScript models/interfaces

Attached: [Share your src/app/ folder structure or key files]

Complex areas that need extra attention:
- NgRx store architecture
- Observable subscription patterns
- Form validation logic
```

LoginComponent ─┐
VerifyComponent ├─> UserService ─> HttpClient ─> Token Interceptor ─> API
ProfileComponent┘
localStorage
├── Key.TOKEN
└── Key.REFRESH_TOKEN

### Token Storage

```
User Request
    ↓
Angular Component (documented with interaction map)
    ↓
Angular Service (documented with API calls)
    ↓
HTTP Interceptor (documented with auth header logic)
    ↓
Backend HTTP (documented with SecurityConfig rules)
    ↓
Spring Controller (documented with endpoint purpose)
    ↓
AuthenticationManager (documented with 10-step flow)
    ↓
DaoAuthenticationProvider (documented with password verification)
    ↓
Database Query (documented in UserRepoImpl)
    ↓
[Response flows back with same documentation]

Result: Complete visibility from UI to database and back!
```

---

## Quick Checklist for Frontend Documentation Readiness

Before contacting me, ensure:

- [ ] Frontend code is well-organized in suggested folder structure
- [ ] Services follow Angular best practices
- [ ] Components are properly documented (at least README comments)
- [ ] HTTP interceptors implemented
- [ ] Error handling in place
- [ ] At least 3-5 components created
- [ ] API calls implemented
- [ ] Authentication flow working
- [ ] You can describe the architecture in words
- [ ] You have the file structure ready to share

---

## Next Steps

1. **Continue Backend Development** - Finish implementing all features
2. **Revisit Backend Docs** - I'll add any new components/classes
3. **Create Angular Project** - Set up following suggested structure
4. **Implement Features** - Build out Angular components and services
5. **Contact Me** - When frontend is ready with information from Phase 3
6. **I'll Document** - Comprehensive documentation like we did for backend
7. **Final Result** - Complete documentation from frontend → backend → database

---

- Replace placeholder customer/invoice views with real API data
- Add route guards for authenticated-only routes
- Migrate profile forms to reactive forms
- Expand error UX for refresh failures

**Last Updated:** May 7, 2026
