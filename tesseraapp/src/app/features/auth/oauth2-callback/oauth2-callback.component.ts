import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Key } from '../../../enumeration/key.enumeration';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoService } from '@jsverse/transloco';

/**
 * SPA landing route for the federated login redirect (SRS FR-FED-4, route
 * {@code /oauth2/callback}).
 *
 * The backend's {@code OAuth2LoginSuccessHandler} finishes the Authorization Code flow
 * and redirects here with results in the URL <em>fragment</em> (the part after {@code #}).
 * Fragments never leave the browser — they are not sent with HTTP requests and do not
 * appear in server or proxy logs — which is why they carry the tokens instead of query
 * parameters.
 *
 * Four fragment shapes arrive:
 * - {@code #access_token=…&refresh_token=…} — tokens are stored under the same
 *   localStorage keys the password flow uses, then the app navigates home. From this
 *   point the session is indistinguishable from an in-house login (the hybrid model's
 *   token-exchange contract).
 * - {@code #mfa=true&email=…&phone=…} — the account has SMS MFA enabled; the backend
 *   has already sent the SMS code (FR-MFA-2), so this component forwards to the login
 *   screen's existing MFA verification state via query params.
 * - {@code #mfa=totp&challenge=…} — the account has an authenticator enrolled
 *   (FR-MFA-4); the backend minted a first-factor challenge, which is forwarded to the
 *   login screen's authenticator-code state. The challenge carries no account data.
 * - anything else — treated as a failed flow; the user is returned to the login screen
 *   with a generic error toast (no account information is disclosed, NFR-SEC-7).
 *
 * {@code replaceUrl: true} on every navigation scrubs the token-bearing URL from
 * browser history so back-navigation cannot resurface credentials.
 */
@Component({
  selector: 'app-oauth2-callback',
  standalone: true,
  template: `
    <div class="d-flex flex-column align-items-center justify-content-center" style="min-height: 60vh;">
      <span aria-hidden="true" class="spinner-border" role="status"></span>
      <p class="mt-3 text-secondary">Completing sign-in…</p>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Oauth2CallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly notification = inject(NotificationsService);
  /** Translates toast copy at call time, so a language switch applies to the next toast. */
  private readonly transloco = inject(TranslocoService);

  /**
   * Parses the redirect fragment and dispatches to the matching flow — tokens,
   * MFA handoff, or failure — per the class contract above.
   */
  ngOnInit(): void {
    const params = new URLSearchParams(this.route.snapshot.fragment ?? '');

    if (params.get('mfa') === 'true') {
      this.router.navigate(['/login'], {
        replaceUrl: true,
        queryParams: { mfa: 'true', email: params.get('email'), phone: params.get('phone') },
      });
      return;
    }

    if (params.get('mfa') === 'totp') {
      this.router.navigate(['/login'], {
        replaceUrl: true,
        queryParams: { mfa: 'totp', challenge: params.get('challenge') },
      });
      return;
    }

    const accessToken = params.get('access_token');
    const refreshToken = params.get('refresh_token');
    if (accessToken && refreshToken) {
      localStorage.setItem(Key.TOKEN, accessToken);
      localStorage.setItem(Key.REFRESH_TOKEN, refreshToken);
      this.router.navigate(['/'], { replaceUrl: true });
      return;
    }

    this.notification.onError(this.transloco.translate('toasts.federatedFailed'));
    this.router.navigate(['/login'], { replaceUrl: true });
  }
}
