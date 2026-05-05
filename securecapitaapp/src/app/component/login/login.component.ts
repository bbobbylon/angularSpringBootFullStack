import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { DataState } from '../../enumeration/datastate.enum';
import { LoginState } from '../../interface/appstates.interface';
import { UserService } from '../../service/user.service';
import { BehaviorSubject, catchError, map, Observable, of, startWith } from 'rxjs';
import { Key } from '../../enumeration/key.enumeration';

@Component({
  selector: 'app-login',
  standalone: true,
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css'],
  imports: [RouterModule, CommonModule, FormsModule],
})
export class LoginComponent {
  readonly DataState = DataState;
  loginState$: Observable<LoginState> = of({
    dataState: DataState.LOADED,
    isUsingMfa: false,
  });
  state: LoginState = {
    dataState: DataState.LOADED,
    loginSuccess: false,
    isUsingMfa: false,
    phone: '',
    error: '',
    message: '',
  };
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private phoneSubject = new BehaviorSubject<string | null>(null);
  private emailSubject = new BehaviorSubject<string | null>(null);

  verifyCode(verifyCodeForm: NgForm): void {
    this.loginState$ = this.userService.verifyCode$(this.emailSubject.value, verifyCodeForm.value.code).pipe(
      map(response => {
        localStorage.setItem(Key.TOKEN, response.data.access_token);
        localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
        this.router.navigate(['/']);
        return { dataState: DataState.LOADED, loginSuccess: true };
      }),
      startWith({
        dataState: DataState.LOADING,
        isUsingMfa: false,
      }),
      catchError((error: string) => {
        return of({
          dataState: DataState.ERROR,
          error,
          isUsingMfa: false,
        });
      }),
    );
  }

  loginPage(): void {
    this.loginState$ = of({
      dataState: DataState.LOADED,
    });
  }

  login(loginForm: NgForm): void {
    this.loginState$ = this.userService.login$(loginForm.value.email, loginForm.value.password).pipe(
      map(response => {
        if (response.data.user.using2FA) {
          this.phoneSubject.next(response.data.user.phoneNumber);
          this.emailSubject.next(response.data.user.email);
          return {
            dataState: DataState.LOADED,
            loginSuccess: false,
            isUsingMfa: true,
            phone: response.data.user.phoneNumber.substring(response.data.user.phoneNumber.length - 4),
          };
        } else {
          localStorage.setItem(Key.TOKEN, response.data.access_token);
          localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
          this.router.navigate(['/']);
          return { dataState: DataState.LOADED, loginSuccess: true };
        }
      }),
      startWith({
        dataState: DataState.LOADING,
        isUsingMfa: false,
      }),
      catchError((error: string) => {
        return of({
          dataState: DataState.ERROR,
          error,
          isUsingMfa: false,
        });
      }),
    );
  }
}
