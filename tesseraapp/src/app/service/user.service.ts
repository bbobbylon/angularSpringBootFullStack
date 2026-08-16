import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { AccountType, NewPasswordFormInterface, ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import {
  PasskeysDataInterface,
  ProviderLinksDataInterface,
  RecoveryCodesInterface,
  SessionsDataInterface,
  TotpEnableInterface,
  TotpSetupInterface,
  TotpStatusInterface,
} from '../interface/security.interface';
import { UserInterface } from '../interface/user.interface';
import { Key } from '../enumeration/key.enumeration';
import { JwtHelperService } from '@auth0/angular-jwt';
import { environment } from '../../environments/environment';

/**
 * Central HTTP service for all user-related API calls.
 *
 * <p><b>About the commented-out {@code tap(console.log)} operators below.</b> They are kept
 * for local debugging and must stay commented in anything that ships. Several of these calls
 * — {@code login$}, {@code refreshToken$}, {@code updatePassword$}, {@code verifyTotp$} —
 * resolve to envelopes containing {@code access_token} and {@code refresh_token}, and
 * {@code profile$} resolves to the full user record. Angular does not strip {@code console.log}
 * from production builds, so enabling one of these writes a usable bearer token into the
 * browser console, where it persists in the devtools buffer and in any screen recording.
 *
 * Each method returns a typed Observable wrapping the server's standard
 * CustomHttpResponseInterface envelope. Errors are normalized by handleError
 * into a single Error observable so callers can handle failures uniformly.
 * Token storage side-effects (reading/writing localStorage) live here rather
 * than in components so the interceptor and components share one source of truth.
 */
@Injectable({
  providedIn: 'root',
})
export class UserService {
  private http = inject(HttpClient);
  private jwtHelper = new JwtHelperService();
  private readonly server = environment.apiUrl;

  /**
   * Verifies a user's 2FA code after login.
   *
   * @param email - the user's email address
   * @param code  - the 2FA code entered by the user
   * @returns Observable emitting a ProfileInterface response on success
   */
  verifyCode$ = (email: string, code: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/verify/code/${email}/${code}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Requests redelivery of an outstanding 2FA/step-up code ({@code POST /user/verify/resend},
   * public). The backend always returns the same non-committal 200 (FR-AUTH-4) regardless of
   * whether the email exists or a code was actually pending — see
   * {@code UserController#resendVerificationCode}.
   *
   * @param email - the email currently mid-verification on the login screen
   * @returns Observable emitting the backend's generic acknowledgement
   */
  resendCode$ = (email: string): Observable<CustomHttpResponseInterface<void>> =>
    this.http
      .post<CustomHttpResponseInterface<void>>(`${this.server}/user/verify/resend`, { email })
      .pipe(catchError(this.handleError));

  verifyAccount$ = (key: string, type: AccountType): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/verify/${type}/${key}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Completes the forgot-password reset flow by submitting a new password for the
   * user identified by {@code form.userID}.
   *
   * Called after {@link verifyAccount$} has resolved the reset link and populated
   * the {@code userSubject} on the verify component — at that point the caller
   * holds the user's ID, so the new password is sent in the request body rather
   * than embedded in the URL. Field names in {@link NewPasswordFormInterface} must
   * match the backend {@code NewPasswordForm.java} exactly so Spring's
   * {@code @RequestBody @Valid} binding succeeds.
   *
   * @param form - userID, newPassword, and confirmPassword for the reset
   * @returns Observable emitting the standard API envelope on success
   */
  setNewPassword$ = (form: NewPasswordFormInterface): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .put<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/new/password`, form)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Authenticates the user with email and password.
   * On success the response contains the access and refresh tokens.
   *
   * @param email    - the user's email address
   * @param password - the user's plain-text password
   * @returns Observable emitting a ProfileInterface response containing tokens
   */
  login$ = (email: string, password: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/login`, { email, password })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Registers a new user account.
   *
   * Sends the form values directly as the request body so Spring's {@code @RequestBody @Valid User}
   * binding can map every field. The intersection type enforces that {@code password} is present
   * at the call site even though {@link UserInterface} omits it (passwords are never returned by the API).
   *
   * @param user - the registration form values including the plain-text password
   * @returns Observable emitting a ProfileInterface response on success
   */
  register$ = (user: UserInterface & { password: string }): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/register`, user)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Initiates a password reset by sending a reset link to the given email address.
   *
   * The backend generates a UUID key, stores it in the {@code reset_password_verifications}
   * table, and emails a link to {@code GET /user/verify/password/{key}}. No authentication
   * token is required — this endpoint is listed in {@code PUBLIC_URLS}.
   *
   * @param email - the email address of the account to reset
   * @returns Observable emitting the server's confirmation message on success
   */
  requestPasswordReset$ = (email: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/resetpassword/${email}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches the currently authenticated user's profile from the backend.
   * The request is automatically decorated with the access token by the token interceptor.
   *
   * @returns Observable emitting a ProfileInterface response containing the user object
   */
  profile$ = (): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http.get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/profile`).pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches one page of audit events for the authenticated user.
   *
   * Called by the Profile page pagination controls — avoids re-fetching the
   * full profile (user + roles) on every page turn.
   *
   * @param page - zero-based page index
   * @param size - number of events per page (default 10)
   * @returns Observable emitting a ProfileInterface response containing only events and pagination metadata
   */
  userEvents$ = (page: number, size = 10): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/events?page=${page}&size=${size}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Submits updated profile fields for the authenticated user.
   *
   * @param user - the updated user data to persist
   * @returns Observable emitting a ProfileInterface response with the updated user
   */
  update$ = (user: UserInterface): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update`, user)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Exchanges the stored refresh token for a new access/refresh token pair.
   * On success the tap operator replaces both tokens in localStorage so subsequent
   * requests automatically use the new access token.
   *
   * @returns Observable emitting a ProfileInterface response containing new tokens
   */
  refreshToken$ = (): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<
        CustomHttpResponseInterface<ProfileInterface>
      >(`${this.server}/user/refresh/token`, { headers: { Authorization: `Bearer ${localStorage.getItem(Key.REFRESH_TOKEN)}` } })
      .pipe(
        tap((response) => {
          // console.log('Received refresh token response:', response);
          localStorage.removeItem(Key.TOKEN);
          localStorage.removeItem(Key.REFRESH_TOKEN);
          localStorage.setItem(Key.TOKEN, response.data!.access_token);
          localStorage.setItem(Key.REFRESH_TOKEN, response.data!.refresh_token);
        }),
        catchError(this.handleError),
      );

  /**
   * Submits a password change for the authenticated user.
   *
   * Field names must exactly match {@code UpdatePasswordForm.java} so Spring's
   * {@code @RequestBody} binding succeeds. On success the backend returns a fresh
   * token pair (old tokens are invalidated by the {@code passwordChangedAt} check),
   * which the {@code tap} operator swaps into localStorage so subsequent requests
   * use the new credentials automatically.
   *
   * @param form - object literal with {@code currentPassword}, {@code newPassword},
   *               and {@code confirmPassword} matching the backend form fields
   * @returns Observable of the standard API envelope containing the updated token pair
   */
  updatePassword$ = (form: {
    currentPassword: string;
    newPassword: string;
    confirmPassword: string;
  }): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http.patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update/password`, form).pipe(
      tap((response) => {
        localStorage.removeItem(Key.TOKEN);
        localStorage.removeItem(Key.REFRESH_TOKEN);
        localStorage.setItem(Key.TOKEN, response.data!.access_token);
        localStorage.setItem(Key.REFRESH_TOKEN, response.data!.refresh_token);
      }),
      catchError(this.handleError),
    );

  // NOTE(FR-RBAC-4): updateUserRole$ was removed together with the backend's self-service
  // PATCH /user/update/role endpoint — users cannot change their own role. Administrators
  // reassign roles through AdminUserService against the /admin/user endpoints.

  /**
   * Persists the enabled and notLocked account-settings flags for the authenticated user.
   * Field names must match {@code SettingsForm.java} for Spring's {@code @RequestBody}
   * binding to succeed.
   *
   * @param settingsForm - object with {@code enabled} and {@code notLocked} booleans
   * @returns Observable of the API envelope containing the updated user and roles
   */
  updateAccountSettings$ = (settingsForm: { enabled: boolean; notLocked: boolean }): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update/settings`, settingsForm)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Uploads a new profile image for the authenticated user.
   * Wraps the file in a {@code FormData} object under the key {@code "image"}
   * to match the {@code @RequestParam("image") MultipartFile} parameter on the
   * backend {@code PATCH /user/update/image} endpoint. On success the response
   * contains the updated user with the new {@code imageUrl}.
   *
   * @param formData - FormData containing the image file under the key "image"
   * @returns Observable of the API envelope containing the updated user and roles
   */
  updateProfileImage$ = (formData: FormData): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update/image`, formData)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Flips the authenticated user's MFA (two-factor authentication) flag.
   * Requires a phone number to be set on the account; the backend throws if one
   * is missing. The backend introduces a 2-second delay for loading-state testing.
   *
   * @returns Observable of the API envelope containing the updated user and roles
   */
  toggleMFA$ = (): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update/togglemfa`, {})
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Completes a TOTP-gated login ({@code POST /user/verify/totp}, public). The
   * {@code challenge} is the opaque first-factor proof returned by {@link login$} for
   * accounts with an authenticator enrolled; pairing it with the code is what prevents
   * a bare TOTP code from ever skipping the password step. POSTed as a body so neither
   * value reaches URL or proxy logs. The route contains "verify", so the token
   * interceptor correctly attaches no Authorization header.
   *
   * @param challenge - the challenge from the login (or federated callback) response
   * @param code      - a 6-digit authenticator code or an unused recovery code
   * @returns Observable emitting a ProfileInterface response containing tokens
   */
  verifyTotp$ = (challenge: string, code: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/verify/totp`, { challenge, code })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Starts authenticator enrollment ({@code POST /user/totp/setup}, authenticated).
   * Returns the secret, otpauth URI, and a server-rendered QR data URI for the
   * Security Center wizard. Safe to call again to restart with a fresh secret while
   * enrollment is unconfirmed.
   *
   * @returns Observable of the API envelope carrying {@link TotpSetupInterface} fields
   */
  totpSetup$ = (): Observable<CustomHttpResponseInterface<TotpSetupInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<TotpSetupInterface>>(`${this.server}/user/totp/setup`, {})
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Confirms enrollment ({@code POST /user/totp/enable}, authenticated) by echoing a
   * code from the freshly scanned authenticator. The response carries the plaintext
   * recovery codes for their ONE AND ONLY display — the backend stores only hashes.
   *
   * @param code - the 6-digit code from the authenticator app
   * @returns Observable of the API envelope with the updated user and recoveryCodes
   */
  totpEnable$ = (code: string): Observable<CustomHttpResponseInterface<TotpEnableInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<TotpEnableInterface>>(`${this.server}/user/totp/enable`, { code })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Disables the authenticator ({@code POST /user/totp/disable}, authenticated).
   * Requires a live TOTP code or an unused recovery code — a session alone cannot
   * strip the second factor.
   *
   * @param code - a current authenticator code or an unused recovery code
   * @returns Observable of the API envelope with the updated user
   */
  totpDisable$ = (code: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/totp/disable`, { code })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Replaces the entire recovery-code batch on demand ({@code POST /user/totp/recovery-codes/regenerate},
   * authenticated) — the standalone counterpart to disable-then-re-enroll. Requires the same
   * proof of possession as {@link totpDisable$}.
   *
   * @param code - a current authenticator code or an unused (about-to-be-replaced) recovery code
   * @returns Observable of the API envelope carrying the fresh plaintext {@code recoveryCodes}
   */
  regenerateRecoveryCodes$ = (code: string): Observable<CustomHttpResponseInterface<RecoveryCodesInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<RecoveryCodesInterface>>(`${this.server}/user/totp/recovery-codes/regenerate`, { code })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches authenticator status ({@code GET /user/totp/status}, authenticated) for the
   * Security Center: enabled flag plus how many recovery codes remain unused.
   *
   * @returns Observable of the API envelope carrying {@link TotpStatusInterface} fields
   */
  totpStatus$ = (): Observable<CustomHttpResponseInterface<TotpStatusInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<TotpStatusInterface>>(`${this.server}/user/totp/status`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Begins passkey registration ({@code POST /user/webauthn/enroll/options}, authenticated).
   * Returns the {@code publicKey} creation options for {@link startRegistration} — see
   * `utils/webauthn.utils.ts`.
   *
   * <p>The path says "enroll", not "register": {@code tokenInterceptor} withholds the
   * Authorization header from any URL with {@code register} (or {@code verify}) as a path segment,
   * which this authenticated endpoint needs attached.
   *
   * @returns Observable of the API envelope carrying the WebAuthn creation options
   */
  webauthnEnrollOptions$ = (): Observable<CustomHttpResponseInterface<{ publicKey: unknown }>> =>
    this.http
      .post<CustomHttpResponseInterface<{ publicKey: unknown }>>(`${this.server}/user/webauthn/enroll/options`, {})
      .pipe(catchError(this.handleError));

  /**
   * Completes passkey registration ({@code POST /user/webauthn/enroll/complete}, authenticated).
   *
   * @param deviceName - the nickname the user gave this passkey
   * @param credential - the browser's registration response from {@link startRegistration}
   * @returns Observable of the API envelope with the updated user and passkey list
   */
  webauthnEnrollComplete$ = (
    deviceName: string,
    credential: unknown,
  ): Observable<CustomHttpResponseInterface<PasskeysDataInterface & { user: UserInterface }>> =>
    this.http
      .post<
        CustomHttpResponseInterface<PasskeysDataInterface & { user: UserInterface }>
      >(`${this.server}/user/webauthn/enroll/complete`, { deviceName, credential })
      .pipe(catchError(this.handleError));

  /**
   * Lists the caller's registered passkeys ({@code GET /user/webauthn/list}, authenticated) for
   * the Security Center's Passkeys card.
   *
   * @returns Observable of the API envelope carrying {@link PasskeysDataInterface}
   */
  webauthnList$ = (): Observable<CustomHttpResponseInterface<PasskeysDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<PasskeysDataInterface>>(`${this.server}/user/webauthn/list`)
      .pipe(catchError(this.handleError));

  /**
   * Removes one of the caller's own passkeys ({@code DELETE /user/webauthn/:id}, authenticated).
   *
   * @param id - the credential's database id (never the WebAuthn credential id itself)
   * @returns Observable of the API envelope carrying the refreshed passkey list
   */
  webauthnDelete$ = (id: number): Observable<CustomHttpResponseInterface<PasskeysDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<PasskeysDataInterface>>(`${this.server}/user/webauthn/${id}`)
      .pipe(catchError(this.handleError));

  /**
   * Begins a usernameless passkey sign-in ({@code POST /user/verify/webauthn/options}, public).
   * No email is sent — the whole point of a discoverable-credential login is that the browser
   * offers every passkey it holds for this site without the server naming an account first.
   *
   * @returns Observable of the API envelope carrying the WebAuthn request options
   */
  webauthnLoginOptions$ = (): Observable<CustomHttpResponseInterface<{ publicKey: unknown }>> =>
    this.http
      .post<CustomHttpResponseInterface<{ publicKey: unknown }>>(`${this.server}/user/verify/webauthn/options`, {})
      .pipe(catchError(this.handleError));

  /**
   * Completes a passkey sign-in ({@code POST /user/verify/webauthn}, public). Mirrors
   * {@link verifyTotp$}'s response shape (user + token pair) — the route contains "verify", so
   * the token interceptor correctly attaches no Authorization header.
   *
   * @param credential - the browser's authentication response from {@link startAuthentication}
   * @returns Observable emitting a ProfileInterface response containing tokens
   */
  webauthnLoginVerify$ = (credential: unknown): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/verify/webauthn`, { credential })
      .pipe(catchError(this.handleError));

  /**
   * Lists the caller's live refresh sessions ({@code GET /user/sessions}) for the
   * Security Center's devices panel, including which family is the current one.
   *
   * @returns Observable of the API envelope carrying sessions and currentFamily
   */
  sessions$ = (): Observable<CustomHttpResponseInterface<SessionsDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<SessionsDataInterface>>(`${this.server}/user/sessions`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Revokes one session ({@code DELETE /user/sessions/{family}}). The revoked device
   * can no longer refresh; its access token ages out within 30 minutes.
   *
   * @param family - the session family from the {@link sessions$} response
   * @returns Observable of the API envelope with the refreshed session list
   */
  revokeSession$ = (family: string): Observable<CustomHttpResponseInterface<SessionsDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<SessionsDataInterface>>(`${this.server}/user/sessions/${family}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * "Log out everywhere else" ({@code DELETE /user/sessions}): revokes every session
   * except the one this browser is using.
   *
   * @returns Observable of the API envelope with the refreshed session list
   */
  revokeOtherSessions$ = (): Observable<CustomHttpResponseInterface<SessionsDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<SessionsDataInterface>>(`${this.server}/user/sessions`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Lists the identity providers connected to the caller's own account
   * ({@code GET /user/sessions/providers}, authenticated) — ROADMAP §1.4.
   *
   * Distinct from {@link federatedProviders$}, which is public and answers a different question:
   * that one lists what this *deployment* supports, this one lists what *you* have connected.
   *
   * @returns Observable of the envelope carrying {@code providers}
   */
  connectedProviders$ = (): Observable<CustomHttpResponseInterface<ProviderLinksDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProviderLinksDataInterface>>(`${this.server}/user/sessions/providers`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Starts connecting an identity provider to the caller's own account (ROADMAP §1.4).
   *
   * Returns a single-use, five-minute ticket plus the URL to navigate to. The two-step shape exists
   * because the browser leaves the app during the OAuth handshake, and a JWT cannot ride a
   * top-level navigation — so the account is decided *here*, while the caller is still
   * authenticated, and carried across in the ticket rather than inferred from the provider response.
   *
   * @param provider - the registration id to connect
   * @returns Observable of the envelope carrying `ticket` and `linkUrl`
   */
  startProviderLink$ = (provider: string): Observable<CustomHttpResponseInterface<{ ticket: string; linkUrl: string }>> =>
    this.http
      .post<CustomHttpResponseInterface<{ ticket: string; linkUrl: string }>>(
        `${this.server}/user/sessions/providers/link/${provider}`, {})
      .pipe(catchError(this.handleError));

  /**
   * Disconnects one provider from the caller's own account
   * ({@code DELETE /user/sessions/providers/{provider}}).
   *
   * The account is taken from the JWT server-side, so this cannot be pointed at anyone else. The
   * backend refuses when the provider is the account's last remaining sign-in method; that arrives
   * here as a normal error whose message names the remedy.
   *
   * @param provider - the registration id to disconnect
   * @returns Observable of the envelope carrying the refreshed {@code providers} list
   */
  unlinkProvider$ = (provider: string): Observable<CustomHttpResponseInterface<ProviderLinksDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<ProviderLinksDataInterface>>(`${this.server}/user/sessions/providers/${provider}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches which federated identity providers are configured on the backend
   * ({@code GET /oauth2/providers}, public). The login screen renders one button per
   * returned id; an empty list (federated login not configured in this environment)
   * renders no federated section at all.
   *
   * @returns Observable of the API envelope carrying {@code providers}, e.g. ['github']
   */
  federatedProviders$ = (): Observable<CustomHttpResponseInterface<{ providers: string[] }>> =>
    this.http
      .get<CustomHttpResponseInterface<{ providers: string[] }>>(`${this.server}/oauth2/providers`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Starts the federated login flow for the given provider by performing a full-page
   * navigation to the backend's Spring Security initiation endpoint
   * ({@code /oauth2/authorization/{provider}}). This is deliberately NOT an XHR — the
   * OAuth2 Authorization Code flow is a chain of browser redirects (backend → provider
   * consent screen → backend callback → SPA /oauth2/callback with tokens in the URL
   * fragment), so the whole window must travel.
   *
   * @param provider - a registration id previously returned by {@link federatedProviders$}
   */
  initiateFederatedLogin(provider: string): void {
    window.location.assign(`${this.server}/oauth2/authorization/${provider}`);
  }

  /**
   * Whether storage currently holds a decodable, unexpired access token.
   *
   * <p>Consulted by {@code authenticationGuard} on every protected navigation, so its failure
   * mode matters more than its happy path. {@link JwtHelperService#decodeToken} *throws* on a
   * value that does not split into three dot-separated parts — it does not return null — and a
   * throw here escapes the guard and aborts the router navigation, stranding the user on the
   * current page instead of sending them to {@code /login}. Storage can hold such a value
   * legitimately: a half-written token, a leftover from an older build, or anything a user has
   * typed into devtools. A token we cannot read is treated exactly like no token at all.
   *
   * @returns true only when a well-formed, unexpired token is present
   */
  isAuthenticated = (): boolean => {
    const token = localStorage.getItem(Key.TOKEN) ?? '';
    if (!token) {
      return false;
    }
    try {
      return !!this.jwtHelper.decodeToken<string>(token) && !this.jwtHelper.isTokenExpired(token);
    } catch {
      // Unreadable token: fail closed to "signed out" so the guard can redirect normally.
      return false;
    }
  };

  /**
   * Returns whether the current access token grants at least one of the given authorities.
   *
   * Decodes the {@code authorities} array claim that the backend's {@code TokenProvider}
   * embeds in every access token (the same claim Spring Security enforces server-side).
   * Used by {@code adminGuard} and the navbar to decide whether to expose the admin
   * Users dashboard. This is a usability gate only (NFR-SEC-4) — the backend re-checks
   * the same authorities at the URL and method level on every request, so a tampered
   * token changes what renders but never what the API permits.
   *
   * @param authorities - one or more authority strings (e.g. {@code 'UPDATE:ROLE'})
   * @returns true if the token is present, unexpired, and carries any of the authorities
   */
  hasAnyAuthority = (...authorities: string[]): boolean => {
    const granted = this.grantedAuthorities();
    return authorities.some((authority) => granted.includes(authority));
  };

  /** The token string the memo below was built from, so a rotated token invalidates it. */
  private cachedToken = '';
  /** Decoded authorities for {@link cachedToken}. */
  private cachedAuthorities: string[] = [];

  /**
   * The authorities carried by the access token currently in storage.
   *
   * <p>Re-read on every call rather than snapshotted, because callers evaluate this on each change
   * detection and the token underneath them changes: it is rotated by the interceptor's silent
   * refresh, and — critically — it is often already EXPIRED at the moment a page is refreshed. A
   * value captured once at component construction would answer "no authorities" for the whole
   * lifetime of that component, which is what used to hide the admin menus after a reload.
   *
   * <p>Memoised on the token string so the repeated calls a template makes cost one string compare
   * rather than a base64 decode and a JSON parse each time.
   *
   * @returns the granted authority strings, empty when there is no usable token
   */
  private grantedAuthorities(): string[] {
    const token = localStorage.getItem(Key.TOKEN) ?? '';
    try {
      if (!token || this.jwtHelper.isTokenExpired(token)) {
        this.cachedToken = '';
        this.cachedAuthorities = [];
        return this.cachedAuthorities;
      }
      if (token !== this.cachedToken) {
        this.cachedToken = token;
        const claim = this.jwtHelper.decodeToken<{ authorities?: unknown }>(token)?.authorities;
        // The claim is attacker-editable (the client never verifies the signature), so its shape
        // is checked rather than asserted. A bare string would otherwise be kept as-is and every
        // later `granted.includes(...)` would run String.prototype.includes — matching by
        // substring, so a token claiming "UPDATE:USERS" would satisfy a check for "UPDATE:USER".
        this.cachedAuthorities = Array.isArray(claim) ? claim.filter((entry): entry is string => typeof entry === 'string') : [];
      }
      return this.cachedAuthorities;
    } catch {
      // An unreadable token throws out of the helper rather than decoding to nothing. Templates
      // call this during change detection, so letting it escape takes down the whole render pass
      // — the user gets a blank screen instead of a page with the privileged controls withheld.
      // Grant nothing, and drop the memo so a subsequent good token is not shadowed by it.
      this.cachedToken = '';
      this.cachedAuthorities = [];
      return this.cachedAuthorities;
    }
  }

  /**
   * Ends the user's session by clearing both JWT tokens from localStorage.
   *
   * <p>GET responses are cached by the browser's native HTTP cache now (backend-driven via
   * {@code Cache-Control: private, no-cache} + ETag — see
   * {@code HttpCacheHeadersFilter}/POST-SUBMISSION-UPGRADES.md #3), which always revalidates with
   * the server before reusing a response, so there is no client-side cache to evict here the way
   * {@code HttpCacheService#evictAll()} previously had to be called by hand. The one gap that
   * revalidation alone cannot close — a same-tab, different-user ETag collision — is handled
   * server-side instead: {@code SessionController#logout} answers with
   * {@code Clear-Site-Data: "cache"}, which purges the browser's HTTP cache for this origin as
   * part of the POST below, before a different user can ever sign in on the same tab.
   */
  logOut() {
    // Tell the server FIRST, while the access token still exists to authenticate the call —
    // clearing localStorage beforehand would leave nothing to prove who is signing out, and the
    // request would come back 401 with the session still live.
    //
    // Fire-and-forget on purpose. The local half of signing out must happen whether or not the
    // network call succeeds: a user who clicks "log out" on a shared machine and hits a dead
    // backend must still end up signed out locally, not stranded holding valid tokens because a
    // request failed. The error branch is deliberately silent — there is no action the user could
    // take, and a toast reading "logout failed" on a screen that has just logged them out is
    // alarming and untrue.
    this.http
      .post<CustomHttpResponseInterface<void>>(`${this.server}/user/sessions/logout`, {})
      .subscribe({
        // Intentionally empty: the local sign-out below must happen regardless, and there is no
        // action the user could take about a failed fire-and-forget revoke. Named rather than a
        // bare {} so the silence reads as a decision instead of an oversight.
        error: () => undefined,
      });
    localStorage.removeItem(Key.TOKEN);
    localStorage.removeItem(Key.REFRESH_TOKEN);
  }

  /**
   * Normalizes HTTP errors into a single Observable<never> so all callers
   * receive a consistent Error instance regardless of whether the failure
   * was a client-side network event or a structured server error response.
   *
   * @param error - the HttpErrorResponse from Angular's HttpClient
   * @returns Observable that immediately errors with a human-readable message
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage: string;

    if (error.error instanceof ErrorEvent) {
      errorMessage = `An error occurred: ${error.error.message}`;
    } else {
      if (error.error?.reason) {
        errorMessage = error.error.reason as string;
        // console.log(error.error);
        // console.log(errorMessage);
        // console.log(error);
      } else {
        errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
      }
    }
    console.error(errorMessage);

    return throwError(() => new Error(errorMessage));
  }
}
