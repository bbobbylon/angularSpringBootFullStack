import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { UserInterface } from '../interface/user.interface';
import { Key } from '../enumeration/key.enumeration';
import { JwtHelperService } from '@auth0/angular-jwt';

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
  private jwtHelper = new JwtHelperService();
  private readonly server: string = 'http://localhost:8080';

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

  register$ = (user: UserInterface & { password: string }): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/register`, user)
      .pipe(tap(console.log), catchError(this.handleError));

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
          localStorage.setItem(Key.TOKEN, response.data.access_token);
          localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
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
        localStorage.setItem(Key.TOKEN, response.data.access_token);
        localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
      }),
      catchError(this.handleError),
    );

  /**
   * Reassigns the authenticated user's role to the given role name.
   * The backend returns the refreshed user and the full roles list so the
   * Authorization tab can update its selector without an additional request.
   *
   * @param roleName - the target role name (e.g. {@code 'ROLE_ADMIN'})
   * @returns Observable of the API envelope containing the updated user and roles
   */
  updateUserRole$ = (roleName: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/update/role/${roleName}`, {})
      .pipe(tap(console.log), catchError(this.handleError));

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

  isAuthenticated = (): boolean =>
    this.jwtHelper.decodeToken<string>(localStorage.getItem(Key.TOKEN)) && !this.jwtHelper.isTokenExpired(localStorage.getItem(Key.TOKEN));

  logOut() {
    localStorage.removeItem(Key.TOKEN);
    localStorage.removeItem(Key.REFRESH_TOKEN);
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
