import { Routes } from '@angular/router';
import { HomeComponent } from './component/home/home.component';
import { LoginComponent } from './component/login/login.component';
import { VerifyComponent } from './component/verify/verify.component';
import { ResetPasswordComponent } from './component/resetpassword/resetpassword.component';
import { RegisterComponent } from './component/register/register.component';
import { CustomerComponent } from './component/customer/customer.component';
import { ProfileComponent } from './component/profile/profile.component';
import { CustomersComponent } from './component/customers/customers.component';
import { authenticationGuard } from './guard/authentication.guard';

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
  { path: 'customer', component: CustomerComponent, canActivate: [authenticationGuard] },
  { path: 'profile', component: ProfileComponent, canActivate: [authenticationGuard] },
  { path: '**', redirectTo: '' },
];
