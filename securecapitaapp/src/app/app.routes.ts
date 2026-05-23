import { Routes } from '@angular/router';
import { authenticationGuard } from './guard/authentication.guard';

/**
 * Application route table for the standalone router.
 *
 * Maps public auth flows, verification links, and core
 * feature pages to their respective standalone components.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'verify',
    loadComponent: () =>
      import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'resetpassword',
    loadComponent: () =>
      import('./features/auth/reset-password/resetpassword.component').then((m) => m.ResetPasswordComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/auth/register/register.component').then((m) => m.RegisterComponent),
  },
  // Matches the URLs the backend emits in account/password verification emails.
  // Format: /user/verify/{account|password}/{uuid-key}
  // See UserRepoImpl#sendAccountVerificationEmail / sendPasswordResetEmail.
  {
    path: 'user/verify/account/:key',
    loadComponent: () =>
      import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'user/verify/password/:key',
    loadComponent: () =>
      import('./features/auth/verify/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: '',
    pathMatch: 'full',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/home/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'customers',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/customers/customers/customers.component').then((m) => m.CustomersComponent),
  },
  {
    path: 'customer/new',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/customers/new-customer/new-customer.component').then((m) => m.NewCustomerComponent),
  },
  {
    path: 'invoice/new',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/invoices/new-invoice/new-invoice.component').then((m) => m.NewInvoiceComponent),
  },
  {
    path: 'invoices',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/invoices/invoices/invoices.component').then((m) => m.InvoicesComponent),
  },
  {
    path: 'profile',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/profile/profile/profile.component').then((m) => m.ProfileComponent),
  },
  {
    path: 'customers/:id',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/customers/customer-details/customer-details.component').then((m) => m.CustomerDetailsComponent),
  },
  {
    path: 'invoice/:id/:invoiceNumber',
    canActivate: [authenticationGuard],
    loadComponent: () =>
      import('./features/invoices/invoice-detail/invoice-detail.component').then((m) => m.InvoiceDetailComponent),
  },
  { path: '**', redirectTo: '' },
];
