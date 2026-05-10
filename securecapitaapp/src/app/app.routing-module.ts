import { Routes } from '@angular/router';
import { HomeComponent } from './component/home/home.component';
import { LoginComponent } from './component/login/login.component';
import { VerifyComponent } from './component/verify/verify.component';
import { ResetPasswordComponent } from './component/reset-password/resetpassword.component';
import { RegisterComponent } from './component/register/register.component';
import { NewCustomerComponent } from './component/new-customer/new-customer.component';
import { CustomersComponent } from './component/customers/customers.component';
import { authenticationGuard } from './guard/authentication.guard';
import { InvoicesComponent } from './component/invoices/invoices.component.ts';
import { InvoiceDetailComponent } from './component/invoice-detail/invoice-detail.component.ts';
import { NewInvoiceComponent } from './component/new-invoice/new-invoice.component';
import { CustomerDetailsComponent } from './component/customer-details/customer-details.component.ts';

/**
 * Application route table for the standalone router.
 *
 * Maps public auth flows, verification links, and core
 * feature pages to their respective standalone components.
 */
export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'verify', component: VerifyComponent },
  { path: 'resetpassword', component: ResetPasswordComponent },
  { path: 'register', component: RegisterComponent },
  { path: 'user/verify/account/:key', component: VerifyComponent },
  { path: 'user/reset/password/:key', component: VerifyComponent },
  { path: '', component: HomeComponent, pathMatch: 'full', canActivate: [authenticationGuard] },
  { path: 'customers', component: CustomersComponent, canActivate: [authenticationGuard] },
  { path: 'customer/new', component: NewCustomerComponent, canActivate: [authenticationGuard] },
  { path: 'invoice/new', component: NewInvoiceComponent, canActivate: [authenticationGuard] },
  { path: 'invoices', component: InvoicesComponent, canActivate: [authenticationGuard] },
  { path: 'customer/:id', component: CustomerDetailsComponent, canActivate: [authenticationGuard] },
  { path: 'invoice/:id/:invoiceNumber', component: InvoiceDetailComponent, canActivate: [authenticationGuard] },
  { path: '**', redirectTo: '' },
];
