import { Routes } from '@angular/router';
import { authenticationGuard } from './guard/authentication.guard';
import { adminGuard } from './guard/admin.guard';

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
  // Matches the URLs the backend emits in account/password verification emails.
  // Format: /user/verify/{account|password}/{uuid-key}
  // See UserRepoImpl#sendAccountVerificationEmail / sendPasswordResetEmail.
  {
    path: 'user/verify/account/:key',
    loadComponent: () => import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'user/verify/password/:key',
    loadComponent: () => import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  // Federated login landing (SRS FR-FED-4): the backend's OAuth2 success handler
  // redirects here with tokens (or an MFA handoff) in the URL fragment. Public by
  // design — the user is mid-authentication when they arrive.
  {
    path: 'oauth2/callback',
    loadComponent: () => import('./features/auth/oauth2-callback/oauth2-callback.component').then((m) => m.Oauth2CallbackComponent),
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
  {
    path: 'customer/new',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/customers/new-customer/new-customer.component').then((m) => m.NewCustomerComponent),
  },
  {
    path: 'invoice/new',
    canActivate: [authenticationGuard],
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
    loadComponent: () => import('./features/users/users/users.component').then((m) => m.UsersComponent),
  },
  {
    path: 'users/:id',
    canActivate: [authenticationGuard, adminGuard],
    loadComponent: () => import('./features/users/user-details/user-details.component').then((m) => m.UserDetailsComponent),
  },
  {
    path: 'invoice/:id/:invoiceNumber',
    canActivate: [authenticationGuard],
    loadComponent: () => import('./features/invoices/invoice-detail/invoice-detail.component').then((m) => m.InvoiceDetailComponent),
  },
  { path: '**', redirectTo: '/', pathMatch: 'full' },
];
