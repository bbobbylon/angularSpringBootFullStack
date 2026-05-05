import { Routes } from '@angular/router';
import { HomeComponent } from './component/home/home.component';
import { LoginComponent } from './component/login/login.component';
import { VerifyComponent } from './component/verify/verify.component';
import { ResetPasswordComponent } from './component/resetpassword/resetpassword.component';
import { RegisterComponent } from './component/register/register.component';

export const routes: Routes = [
  { path: '', component: HomeComponent, pathMatch: 'full' },
  { path: 'login', component: LoginComponent },
  { path: 'verify', component: VerifyComponent },
  { path: 'resetpassword', component: ResetPasswordComponent },
  { path: 'register', component: RegisterComponent },
  { path: 'user/verify/account/:key', component: VerifyComponent },
  { path: 'user/reset/password/:key', component: VerifyComponent },
  { path: '**', component: LoginComponent },
];