import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
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
  /**
   * Registration ids of the federated providers configured on the backend
   * (e.g. ['google','github']). Empty until {@code GET /oauth2/providers} responds —
   * and stays empty when federated login is not configured, which hides the
   * "or continue with" section entirely.
   */
  protected readonly federatedProviders = signal<string[]>([]);
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  private readonly phone = signal<string | null>(null);
  private readonly email = signal<string | null>(null);
  /**
   * Opaque first-factor proof for TOTP logins (FR-MFA-4), captured from the login
   * response (or the federated callback's query params) and submitted together with
   * the authenticator code to {@code POST /user/verify/totp}. Holds no account
   * information — it is useless without the authenticator.
   */
  private readonly challenge = signal<string | null>(null);

  /**
   * Boot logic for the login screen. Beyond the original authenticated-redirect,
   * this now (1) discovers which federated providers to offer, and (2) handles the
   * two redirect re-entries the federated flow can land here with:
   *
   * - {@code ?error=...} — the backend's OAuth2 failure/refusal redirects. The codes
   *   are deliberately coarse (NFR-SEC-7); they are mapped to friendly messages here.
   * - {@code ?mfa=true&email=...&phone=...} — an MFA-enabled account completed the
   *   federated first factor; the backend already sent the SMS code (FR-MFA-2), so
   *   this screen jumps straight into the existing MFA verification state and the
   *   normal {@link verifyCode} flow finishes the login.
   */
  ngOnInit(): void {
    if (this.userService.isAuthenticated()) {
      this.router.navigate(['/']);
      return;
    }
    this.userService
      .federatedProviders$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.federatedProviders.set(response.data?.providers ?? []),
        // Discovery failing must never block password login — just omit the buttons.
        error: () => this.federatedProviders.set([]),
      });

    const params = this.route.snapshot.queryParamMap;
    const error = params.get('error');
    if (error) {
      this.notification.onError(
        error === 'account'
          ? 'Your account is disabled or locked. Contact an administrator.'
          : 'Federated sign-in failed. Please try again or sign in with your password.',
      );
    }
    if (params.get('mfa') === 'true') {
      const email = params.get('email');
      const phone = params.get('phone') ?? '';
      this.email.set(email);
      this.phone.set(phone);
      this.loginState.set({
        dataState: DataState.LOADED,
        loginSuccess: false,
        isUsingMfa: true,
        mfaMethod: 'sms',
        phone: phone ? phone.substring(phone.length - 4) : '',
      });
    }
    // Federated first factor completed for a TOTP-enrolled account (FR-MFA-4): the
    // backend minted a challenge and bounced here; jump straight into the
    // authenticator-code state. No email/phone needed — the challenge is the identity.
    if (params.get('mfa') === 'totp') {
      this.challenge.set(params.get('challenge'));
      this.loginState.set({
        dataState: DataState.LOADED,
        loginSuccess: false,
        isUsingMfa: true,
        mfaMethod: 'totp',
      });
    }
  }

  /**
   * Hands the browser to the backend to run the OAuth2 Authorization Code flow for
   * the chosen provider (full-page redirect chain — see
   * {@code UserService#initiateFederatedLogin}).
   *
   * @param provider - one of the ids from {@link federatedProviders}
   */
  loginWithProvider(provider: string): void {
    this.userService.initiateFederatedLogin(provider);
  }

  /**
   * Submits the MFA verification code once the backend has requested 2FA.
   *
   * Dispatches to the method-appropriate endpoint (FR-MFA-2/4): the SMS path verifies
   * email + code, while the TOTP path submits the stored first-factor challenge plus
   * the authenticator (or recovery) code. Both converge on identical token handling —
   * past this point the session is method-agnostic.
   */
  verifyCode(verifyCodeForm: NgForm): void {
    const method = this.loginState().mfaMethod ?? 'sms';
    const phone = this.phone();
    const phoneTail = phone ? phone.substring(phone.length - 4) : '';
    this.loginState.set({
      dataState: DataState.LOADING,
      loginSuccess: false,
      isUsingMfa: true,
      mfaMethod: method,
      phone: phoneTail,
    });
    const verification$ =
      method === 'totp'
        ? this.userService.verifyTotp$(this.challenge()!, verifyCodeForm.value.code)
        : this.userService.verifyCode$(this.email()!, verifyCodeForm.value.code);
    verification$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (response) => {
        localStorage.setItem(Key.TOKEN, response.data!.access_token);
        localStorage.setItem(Key.REFRESH_TOKEN, response.data!.refresh_token);
        this.router.navigate(['/']);
        this.loginState.set({ dataState: DataState.LOADED, loginSuccess: true });
      },
      error: (error: string) => {
        this.notification.onError(error);
        this.loginState.set({
          dataState: DataState.ERROR,
          error,
          isUsingMfa: true,
          mfaMethod: method,
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
          if (response.data!.user!.usingTotp) {
            // Authenticator MFA (FR-MFA-4): the backend withheld tokens and returned a
            // first-factor challenge instead — switch to the authenticator-code panel.
            this.challenge.set(response.data!.challenge ?? null);
            this.loginState.set({
              dataState: DataState.LOADED,
              loginSuccess: false,
              isUsingMfa: true,
              mfaMethod: 'totp',
            });
          } else if (response.data!.user!.using2FA) {
            this.phone.set(response.data!.user!.phoneNumber ?? null);
            this.email.set(response.data!.user!.email ?? null);
            this.loginState.set({
              dataState: DataState.LOADED,
              loginSuccess: false,
              isUsingMfa: true,
              mfaMethod: 'sms',
              phone: response.data!.user!.phoneNumber!.substring(response.data!.user!.phoneNumber!.length - 4),
            });
          } else {
            localStorage.setItem(Key.TOKEN, response.data!.access_token);
            localStorage.setItem(Key.REFRESH_TOKEN, response.data!.refresh_token);
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
