import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { DataState } from '../../../enumeration/datastate.enum';
import { LoginStateInterface } from '../../../interface/appstates.interface';
import { UserService } from '../../../service/user.service';
import { Key } from '../../../enumeration/key.enumeration';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom, retry } from 'rxjs';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoDirective } from '@jsverse/transloco';
import { isWebAuthnSupported, shouldPromptForPasskey, startAuthentication } from '../../../utils/webauthn.utils';
import { UserInterface } from '../../../interface/user.interface';

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
  imports: [RouterModule, CommonModule, FormsModule, TranslocoDirective],
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
  /** Whether this browser supports WebAuthn at all — hides the passkey button when false. */
  protected readonly webauthnSupported = isWebAuthnSupported();
  /** True while a passkey sign-in ceremony is in flight. */
  protected readonly passkeyLoginInProgress = signal(false);
  /** True while the email-domain SSO lookup request is in flight (FUTURE-ENHANCEMENTS.md §3.1). */
  protected readonly orgSsoLookupInProgress = signal(false);
  /**
   * Set to {@code true} only after a lookup comes back {@code found: false} — renders the neutral
   * "no organization SSO found" hint. Never set on any other outcome (success navigates away
   * immediately; an error is routed to the shared notification toast instead), and cleared as soon
   * as the user edits the email again so a stale message can't linger against a corrected address.
   */
  protected readonly orgSsoNotFound = signal(false);
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
  /** True while a resend request is in flight, so the button can't be double-clicked. */
  protected readonly resendInFlight = signal(false);
  /** Seconds remaining before another resend is allowed. Client-side courtesy only — the
   *  backend's own auth-tier rate limit (10/min/IP) is the real enforcement. */
  protected readonly resendCooldown = signal(0);
  private resendCooldownHandle: ReturnType<typeof setInterval> | undefined;

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
      .pipe(
        // The backend may still be booting when the login page first paints (or may restart while
        // the page is open), which would leave the federated buttons missing until a manual refresh.
        // Retry a few times so discovery self-heals once the backend is up. Complements the
        // backend-ready gate in start.sh, and also covers IDE/separate launches and restarts.
        retry({ count: 5, delay: 2000 }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => this.federatedProviders.set(response.data?.providers ?? []),
        // Discovery ultimately failing must never block password login — just omit the buttons.
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
   * Looks up whether the given email's domain is claimed by an organization's SSO configuration
   * (FUTURE-ENHANCEMENTS.md §3.1 email-domain discovery) and, if so, hands the browser off to that
   * organization's IdP. Deliberately mirrors {@link loginWithProvider}'s full-page-navigation
   * pattern on success — this is still the OAuth2 Authorization Code flow underneath.
   *
   * <p>A {@code found: false} response is not an error — it renders a neutral inline hint and lets
   * the user fall back to password login or a consumer provider button, without ever confirming or
   * denying that the email address itself belongs to a known account (only whether its domain has
   * SSO configured, a materially different and non-enumerating signal).
   *
   * @param orgSsoEmailForm - the standalone email-only form for this affordance
   */
  lookupOrgSso(orgSsoEmailForm: NgForm): void {
    const email = orgSsoEmailForm.value.orgSsoEmail;
    if (!email || this.orgSsoLookupInProgress()) {
      return;
    }
    this.orgSsoNotFound.set(false);
    this.orgSsoLookupInProgress.set(true);
    this.userService
      .orgSsoLookup$(email)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.orgSsoLookupInProgress.set(false);
          if (response.data?.found) {
            this.userService.initiateOrgSsoLogin(response.data.loginUrl!);
          } else {
            this.orgSsoNotFound.set(true);
          }
        },
        error: (error: string) => {
          this.orgSsoLookupInProgress.set(false);
          this.notification.onError(error);
        },
      });
  }

  /**
   * Routes home after ANY successful login — password, MFA-verified, or passkey — detouring
   * through the one-time "Add a passkey?" prompt first when {@link shouldPromptForPasskey} says
   * to (account has none yet, browser supports WebAuthn, not already dismissed on this device).
   * Centralized here rather than duplicated per success branch, since this component has three.
   */
  private navigateHome(user: UserInterface | undefined): void {
    this.router.navigate([shouldPromptForPasskey(user?.usingPasskey) ? '/welcome-passkey' : '/']);
  }

  /**
   * Runs a usernameless passkey sign-in: fetches request options, prompts the browser to pick a
   * registered passkey via {@link startAuthentication}, then posts the assertion for
   * verification. On success this goes straight to tokens — no risk-based step-up and no second
   * factor, mirroring how {@code OAuth2LoginSuccessHandler} already treats federated login as
   * strong enough on its own (see {@code PasskeyController} for the backend side of that decision).
   *
   * <p>A cancelled or dismissed platform prompt throws a {@code DOMException} from
   * {@code navigator.credentials.get()} without ever reaching the backend — caught here and
   * treated as a silent no-op rather than an error, since the user simply changed their mind and
   * the password form is still right there.
   */
  async loginWithPasskey(): Promise<void> {
    this.passkeyLoginInProgress.set(true);
    try {
      const options = await firstValueFrom(this.userService.webauthnLoginOptions$());
      const credential = await startAuthentication(options.data!.publicKey as PublicKeyCredentialRequestOptionsJSON);
      const response = await firstValueFrom(this.userService.webauthnLoginVerify$(credential));
      localStorage.setItem(Key.TOKEN, response.data!.access_token);
      localStorage.setItem(Key.REFRESH_TOKEN, response.data!.refresh_token);
      this.navigateHome(response.data!.user);
      this.loginState.set({ dataState: DataState.LOADED, loginSuccess: true });
    } catch (error) {
      if (!(error instanceof DOMException)) {
        this.notification.onError(error instanceof Error ? error.message : String(error));
      }
    } finally {
      this.passkeyLoginInProgress.set(false);
    }
  }

  /**
   * Submits the MFA verification code once the backend has requested 2FA.
   *
   * Dispatches to the method-appropriate endpoint (FR-MFA-2/4, FR-TPF-1): the SMS path
   * verifies email + code, while the TOTP path submits the stored first-factor challenge
   * plus the authenticator (or recovery) code. The risk step-up ('email') deliberately
   * shares the SMS branch — the backend mints its code into the same verification store,
   * so a separate endpoint would only duplicate the expiry and single-use rules. All
   * three converge on identical token handling; past this point the session is
   * method-agnostic.
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
        this.navigateHome(response.data!.user);
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
   * Requests redelivery of the outstanding SMS/email code from the "Resend code" link.
   * Only reachable for the {@code 'sms'}/{@code 'email'} methods — TOTP has no server-issued
   * code to resend, so the template hides the link entirely for that method.
   *
   * <p>The backend's response is deliberately non-committal (FR-AUTH-4) — it never confirms
   * whether a code was actually resent — so this always shows the same neutral confirmation
   * and starts a client-side cooldown, both to discourage repeat clicks and to mirror the
   * backend's own auth-tier rate limit (10 req/min/IP) with immediate UI feedback rather than
   * only surfacing a 429 after the fact.
   */
  resendCode(): void {
    const email = this.email();
    if (!email || this.resendInFlight() || this.resendCooldown() > 0) {
      return;
    }
    this.resendInFlight.set(true);
    this.userService
      .resendCode$(email)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.resendInFlight.set(false);
          this.notification.onSuccess("If a code is pending, we've sent it again.");
          this.startResendCooldown();
        },
        error: (error: string) => {
          this.resendInFlight.set(false);
          this.notification.onError(error);
        },
      });
  }

  /** Runs a 30-second countdown, ticking {@link resendCooldown} down to 0 once a second. */
  private startResendCooldown(): void {
    clearInterval(this.resendCooldownHandle);
    this.resendCooldown.set(30);
    this.resendCooldownHandle = setInterval(() => {
      const remaining = this.resendCooldown() - 1;
      this.resendCooldown.set(Math.max(remaining, 0));
      if (remaining <= 0) {
        clearInterval(this.resendCooldownHandle);
      }
    }, 1000);
    this.destroyRef.onDestroy(() => clearInterval(this.resendCooldownHandle));
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
          } else if (response.data!.stepUp) {
            // Risk step-up (FR-TPF-1): this account has no second factor enrolled, so the
            // two branches above did not fire — but the backend judged the sign-in unfamiliar
            // and withheld tokens behind a one-time code emailed to the account holder.
            // Verification reuses the SMS endpoint, so only the copy differs from 'sms'.
            this.email.set(response.data!.user!.email ?? null);
            this.loginState.set({
              dataState: DataState.LOADED,
              loginSuccess: false,
              isUsingMfa: true,
              mfaMethod: 'email',
            });
          } else {
            localStorage.setItem(Key.TOKEN, response.data!.access_token);
            localStorage.setItem(Key.REFRESH_TOKEN, response.data!.refresh_token);
            this.navigateHome(response.data!.user);
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
