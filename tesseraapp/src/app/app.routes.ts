import { Routes } from '@angular/router';
import { authenticationGuard } from './guard/authentication.guard';
import { adminGuard } from './guard/admin.guard';
import { capabilityGuard } from './guard/capability.guard';

/**
 * Application route table for the standalone router.
 *
 * Maps public auth flows, verification links, and core
 * feature pages to their respective standalone components.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  // Matches the URLs the backend emits in account/password verification emails.
  // Format: /verify/{account|password}/{uuid-key} — see UserRepoImpl#getVerificationURL.
  //
  // The /user prefix is deliberately absent. These were `user/verify/...`, mirroring the backend
  // endpoint exactly, which works only while Angular runs on its own dev server. In Docker/prod the
  // SPA is served from the SAME origin as the API, so Spring MVC matched the real
  // UserController#verifyAccount handler first and the recipient was shown the raw JSON envelope
  // instead of this screen. Bare/plural paths belong to the SPA, /user/** belongs to the API — that
  // split is what makes one emailed link behave identically in both topologies.
  //
  // Declared before the bare 'verify' route so the intent reads top-down (Angular backtracks past a
  // leaf route that cannot consume the whole URL anyway), and the flow is carried in static `data`
  // so VerifyComponent no longer has to infer it from how the path happens to be spelled.
  {
    path: 'verify/account/:key',
    data: { verificationType: 'account' },
    loadComponent: () => import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'verify/password/:key',
    data: { verificationType: 'password' },
    loadComponent: () => import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'verify',
    loadComponent: () => import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'resetpassword',
    loadComponent: () => import('./features/auth/reset-password/resetpassword.component').then((m) => m.ResetPasswordComponent),
  },
  {
    path: 'register',
    loadComponent: () => import('./features/auth/register/register.component').then((m) => m.RegisterComponent),
  },
  // Public legal pages — no auth guard. Exist mainly to give third parties (e.g. Twilio's
  // A2P 10DLC campaign registration for SMS 2FA) a stable, publicly reachable URL to review.
  {
    path: 'privacy',
    loadComponent: () => import('./features/legal/privacy-policy/privacy-policy.component').then((m) => m.PrivacyPolicyComponent),
  },
  {
    path: 'terms',
    loadComponent: () => import('./features/legal/terms/terms.component').then((m) => m.TermsComponent),
  },
  {
    path: 'contact',
    loadComponent: () => import('./features/legal/contact/contact.component').then((m) => m.ContactComponent),
  },
  {
    path: 'features',
    loadComponent: () =>
      import('./features/marketing/feature-tour/feature-tour.component').then((m) => m.FeatureTourComponent),
  },
  // Federated login landing (SRS FR-FED-4): the backend's OAuth2 success handler
  // redirects here with tokens (or an MFA handoff) in the URL fragment. Public by
  // design — the user is mid-authentication when they arrive.
  {
    path: 'oauth2/callback',
    loadComponent: () => import('./features/auth/oauth2-callback/oauth2-callback.component').then((m) => m.Oauth2CallbackComponent),
  },
  // Skippable, one-time-per-device passkey nudge shown right after a successful password/MFA
  // login when the account has no passkey yet (LoginComponent redirects here instead of '/' —
  // see webauthn.utils.ts#shouldPromptForPasskey). Requires authenticationGuard because tokens
  // are already stored by the time the user lands here.
  {
    path: 'welcome-passkey',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/auth/passkey-welcome/passkey-welcome.component').then((m) => m.PasskeyWelcomeComponent),
  },
  {
    path: '',
    pathMatch: 'full',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/home/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'customers',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/customers/customers/customers.component').then((m) => m.CustomersComponent),
  },
  // Creation routes require write authority, not staff status (ROADMAP §2). Both forms POST,
  // so they land on SecurityConfig's
  // .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER") — the guard
  // asks for exactly that pair. Note this is deliberately NOT adminGuard: ROLE_MODERATOR holds
  // UPDATE:CUSTOMER without any staff authority, and must keep reaching these pages. The navbar
  // and command palette hide the links for accounts that fail this check; the guard is what
  // closes the URL-typing path, so a read-only user gets an explanation instead of a form that
  // 403s on submit.
  {
    path: 'customer/new',
    canActivate: [authenticationGuard, capabilityGuard],
    data: { requiredAuthorities: ['UPDATE:CUSTOMER', 'UPDATE:USER'], deniedAction: 'create customers', deniedActionKey: 'permissions.actions.createCustomers' },
    loadComponent: () => import('./features/customers/new-customer/new-customer.component').then((m) => m.NewCustomerComponent),
  },
  {
    path: 'invoice/new',
    canActivate: [authenticationGuard, capabilityGuard],
    data: { requiredAuthorities: ['UPDATE:CUSTOMER', 'UPDATE:USER'], deniedAction: 'create invoices', deniedActionKey: 'permissions.actions.createInvoices' },
    loadComponent: () => import('./features/invoices/new-invoice/new-invoice.component').then((m) => m.NewInvoiceComponent),
  },
  {
    path: 'invoices',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/invoices/invoices/invoices.component').then((m) => m.InvoicesComponent),
  },
  {
    path: 'profile',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/profile/profile/profile.component').then((m) => m.ProfileComponent),
  },
  // Account Security Center (plan.md M4/M5): authenticator-app MFA enrollment and the
  // sessions & devices panel. Self-service — plain authentication suffices, no admin guard.
  {
    path: 'security',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/security/security-center/security-center.component').then((m) => m.SecurityCenterComponent),
  },
  {
    path: 'customers/:id',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/customers/customer-details/customer-details.component').then((m) => m.CustomerDetailsComponent),
  },
  // Administrative Users dashboard (SRS FR-ADMIN-1/2/5). adminGuard additionally requires a
  // staff-grade authority (UPDATE:USER / UPDATE:ROLE) in the access token; the backend
  // enforces the same authorities on every /admin/** request, so the guard is purely a
  // usability aid (NFR-SEC-4).
  {
    path: 'users',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'manage users', deniedActionKey: 'permissions.actions.manageUsers' },
    loadComponent: () => import('./features/users/users/users.component').then((m) => m.UsersComponent),
  },
  {
    path: 'users/:id',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'manage users', deniedActionKey: 'permissions.actions.manageUsers' },
    loadComponent: () => import('./features/users/user-details/user-details.component').then((m) => m.UserDetailsComponent),
  },
  // Roles × Permissions Matrix (SRS M3, FR-RBAC-1/2). adminGuard mirrors the
  // /users route — only staff-grade authorities (UPDATE:USER / UPDATE:ROLE) reach it.
  {
    path: 'roles',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'manage roles and permissions', deniedActionKey: 'permissions.actions.manageRoles' },
    loadComponent: () => import('./features/users/roles-matrix/roles-matrix.component').then((m) => m.RolesMatrixComponent),
  },
  {
    path: 'invoice/:id/:invoiceNumber',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/invoices/invoice-detail/invoice-detail.component').then((m) => m.InvoiceDetailComponent),
  },
  // Billing overview (SRS admin-only analytics). adminGuard is the usability gate; the
  // real boundary is server-side: this page reads the admin-only /admin/analytics/**
  // API, which SecurityConfig's /admin/** matcher (+ @PreAuthorize) locks to UPDATE:USER
  // / UPDATE:ROLE. A user who bypasses this guard still gets 403 from the data API.
  {
    path: 'billing',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'view billing', deniedActionKey: 'permissions.actions.viewBilling' },
    loadComponent: () => import('./features/billing/billing/billing.component').then((m) => m.BillingComponent),
  },
  // Service / app catalog — all authenticated users can browse available services and
  // launch a new invoice pre-filled for a selected service.
  {
    path: 'services',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/services/services-catalog/services-catalog.component').then(
        (m) => m.ServicesCatalogComponent,
      ),
  },
  // Services catalog administration (ROADMAP §2). Staff-only, and separate from /services
  // deliberately: the browse page shows only what can be put on an invoice, while this one shows
  // retired entries too so they can be reinstated. The write endpoints live under
  // /admin/services/**, so the authority is enforced server-side regardless of this guard.
  {
    path: 'services/manage',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'manage the services catalog', deniedActionKey: 'permissions.actions.manageServices' },
    loadComponent: () =>
      import('./features/services/services-admin/services-admin.component').then(
        (m) => m.ServicesAdminComponent,
      ),
  },
  // Analytics hub (admin-only) — dual-area trend chart, acquisition bars, stacked status
  // breakdown, service utilization. adminGuard mirrors billing, and like billing the data
  // is fetched from the admin-gated /admin/analytics/** API (UPDATE:USER / UPDATE:ROLE
  // enforced server-side), so the guard is a usability aid, not the security boundary.
  {
    path: 'analytics',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'view analytics', deniedActionKey: 'permissions.actions.viewAnalytics' },
    loadComponent: () =>
      import('./features/analytics/analytics/analytics.component').then(
        (m) => m.AnalyticsComponent,
      ),
  },
  // Administrative security dashboard (SRS FR-TPF-2) — the review surface for the anomaly
  // detection FR-TPF-1 added. Distinct from /security, which is the *self-service* Account
  // Security Center every authenticated user gets: this one reports on the whole (in-scope)
  // population and is therefore staff-only. Like billing and analytics, the guard is the
  // usability half and /admin/security/** is where the authority is actually enforced.
  {
    path: 'security-overview',
    canActivate: [authenticationGuard, adminGuard],
    data: { deniedAction: 'view security monitoring', deniedActionKey: 'permissions.actions.viewSecurity' },
    loadComponent: () =>
      import('./features/security/security-overview/security-overview.component').then(
        (m) => m.SecurityOverviewComponent,
      ),
  },
  { path: '**', redirectTo: '/', pathMatch: 'full' },
];
