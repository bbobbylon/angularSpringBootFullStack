import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { AccountType, NewPasswordFormInterface, ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { SessionsDataInterface, TotpEnableInterface, TotpSetupInterface, TotpStatusInterface } from '../interface/security.interface';
import { UserInterface } from '../interface/user.interface';
import { Key } from '../enumeration/key.enumeration';
import { JwtHelperService } from '@auth0/angular-jwt';
import { HttpCacheService } from './http-cache.service';
import { environment } from '../../environments/environment';

/**
 * Central HTTP service for all user-related API calls.
 *
 * Each method returns a typed Observable wrapping the server's standard
 * CustomHttpResponseInterface envelope. Errors are normalised by handleError
 * into a single Error observable so callers can handle failures uniformly.
 * Token storage side-effects (reading/writing localStorage) live here rather
 * than in components so the interceptor and components share one source of truth.
 */
@Injectable({
  providedIn: 'root',
})
export class UserService {
  private http = inject(HttpClient);
  private readonly httpCache = inject(HttpCacheService);
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
      .pipe(tap(console.log), catchError(this.handleError));

  verifyAccount$ = (key: string, type: AccountType): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/verify/${type}/${key}`)
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Fetches the currently authenticated user's profile from the backend.
   * The request is automatically decorated with the access token by the token interceptor.
   *
   * @returns Observable emitting a ProfileInterface response containing the user object
   */
  profile$ = (): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http.get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/profile`).pipe(tap(console.log), catchError(this.handleError));

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
  userEvents$ = (page: number, size: number = 10): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/events?page=${page}&size=${size}`)
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Submits updated profile fields for the authenticated user.
   *
   * @param user - the updated user data to persist
   * @returns Observable emitting a ProfileInterface response with the updated user
   */
  update$ = (user: UserInterface): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update`, user)
      .pipe(tap(console.log), catchError(this.handleError));

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
          console.log('Received refresh token response:', response);
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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(catchError(this.handleError));

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
      .pipe(catchError(this.handleError));

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
      .pipe(catchError(this.handleError));

  /**
   * Fetches authenticator status ({@code GET /user/totp/status}, authenticated) for the
   * Security Center: enabled flag plus how many recovery codes remain unused.
   *
   * @returns Observable of the API envelope carrying {@link TotpStatusInterface} fields
   */
  totpStatus$ = (): Observable<CustomHttpResponseInterface<TotpStatusInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<TotpStatusInterface>>(`${this.server}/user/totp/status`)
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
      .pipe(catchError(this.handleError));

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
      .pipe(catchError(this.handleError));

  /**
   * "Log out everywhere else" ({@code DELETE /user/sessions}): revokes every session
   * except the one this browser is using.
   *
   * @returns Observable of the API envelope with the refreshed session list
   */
  revokeOtherSessions$ = (): Observable<CustomHttpResponseInterface<SessionsDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<SessionsDataInterface>>(`${this.server}/user/sessions`)
      .pipe(catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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

  isAuthenticated = (): boolean =>
    !!this.jwtHelper.decodeToken<string>(localStorage.getItem(Key.TOKEN) ?? '') && !this.jwtHelper.isTokenExpired(localStorage.getItem(Key.TOKEN) ?? '');

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
    const token = localStorage.getItem(Key.TOKEN) ?? '';
    if (!token || this.jwtHelper.isTokenExpired(token)) return false;
    const decoded = this.jwtHelper.decodeToken<{ authorities?: string[] }>(token);
    const granted = decoded?.authorities ?? [];
    return authorities.some((authority) => granted.includes(authority));
  };

  /**
   * Ends the user's session by clearing both JWT tokens from localStorage AND
   * evicting the in-memory HTTP cache.
   *
   * The cache eviction is essential for correctness and security. {@link HttpCacheService}
   * (populated by {@code cacheInterceptor}) keys GET responses by URL and is not cleared
   * on logout by default; the subsequent {@code /user/login} POST is in the interceptor's
   * {@code bypassRoutes}, so it never triggers the usual mutation-based eviction either.
   * Without this call, a different user signing in within the same SPA session (no full
   * page reload) could be served the previous user's cached {@code /user/profile} data —
   * a cross-session data leak that directly violates the app's zero-trust posture.
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
      .subscribe({ error: () => {} });
    localStorage.removeItem(Key.TOKEN);
    localStorage.removeItem(Key.REFRESH_TOKEN);
    this.httpCache.evictAll();
  }

  /**
   * Normalises HTTP errors into a single Observable<never> so all callers
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
        console.log(error.error);
        console.log(errorMessage);
        console.log(error);
      } else {
        errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
      }
    }
    console.error(errorMessage);

    return throwError(() => new Error(errorMessage));
  }
}
