import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { DataState } from '../../../enumeration/datastate.enum';
import { LoginStateInterface } from '../../../interface/appstates.interface';
import { UserService } from '../../../service/user.service';
import { Key } from '../../../enumeration/key.enumeration';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';

/**
 * Handles login and MFA verification flows.
 *
 * Drives the login form, token storage, and the optional
 * two-factor verification step when required by the backend.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css'],
  imports: [RouterModule, CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginComponent implements OnInit {
  /** Template access to the DataState enum for UI state rendering. */
  readonly DataState = DataState;
  loginState = signal<LoginStateInterface>({
    dataState: DataState.LOADED,
    isUsingMfa: false,
  });
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  private readonly phone = signal<string | null>(null);
  private readonly email = signal<string | null>(null);

  ngOnInit(): void {
    if (this.userService.isAuthenticated()) {
      this.router.navigate(['/']);
    } else {
      this.router.navigate(['/login']);
    }
  }

  /**
   * Submits the MFA verification code once the backend has requested 2FA.
   *
   * Stores tokens, navigates to the home route on success, and emits
   * an error state if the verification fails.
   */
  verifyCode(verifyCodeForm: NgForm): void {
    const phone = this.phone();
    const phoneTail = phone ? phone.substring(phone.length - 4) : '';
    this.loginState.set({
      dataState: DataState.LOADING,
      loginSuccess: false,
      isUsingMfa: true,
      phone: phoneTail,
    });
    this.userService
      .verifyCode$(this.email(), verifyCodeForm.value.code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          localStorage.setItem(Key.TOKEN, response.data.access_token);
          localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
          this.router.navigate(['/']);
          this.loginState.set({ dataState: DataState.LOADED, loginSuccess: true });
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.loginState.set({
            dataState: DataState.ERROR,
            error,
            isUsingMfa: true,
            loginSuccess: false,
            phone: phoneTail,
          });
        },
      });
  }

  /**
   * Resets the login view back to the initial form state.
   *
   * Used when the user wants to switch back from MFA entry.
   */
  loginPage(): void {
    this.loginState.set({
      dataState: DataState.LOADED,
    });
  }

  /**
   * Authenticates with email/password and handles optional 2FA.
   *
   * On success stores tokens and routes to the home page; on failure
   * emits an error state for the template to display.
   */
  login(loginForm: NgForm): void {
    this.loginState.set({ dataState: DataState.LOADING, isUsingMfa: false });
    this.userService
      .login$(loginForm.value.email, loginForm.value.password)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          if (response.data.user.using2FA) {
            this.phone.set(response.data.user.phoneNumber);
            this.email.set(response.data.user.email);
            this.loginState.set({
              dataState: DataState.LOADED,
              loginSuccess: false,
              isUsingMfa: true,
              phone: response.data.user.phoneNumber.substring(response.data.user.phoneNumber.length - 4),
            });
          } else {
            localStorage.setItem(Key.TOKEN, response.data.access_token);
            localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
            this.router.navigate(['/']);
            this.loginState.set({ dataState: DataState.LOADED, loginSuccess: true });
          }
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.loginState.set({
            dataState: DataState.ERROR,
            error,
            isUsingMfa: false,
            loginSuccess: false,
          });
        },
      });
  }
}
