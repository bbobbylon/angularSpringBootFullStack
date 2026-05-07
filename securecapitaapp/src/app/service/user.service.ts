import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { UserInterface } from '../interface/user.interface';
import { Key } from '../enumeration/key.enumeration';

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
        tap(response => {
          console.log('Received refresh token response:', response);
          localStorage.removeItem(Key.TOKEN);
          localStorage.removeItem(Key.REFRESH_TOKEN);
          localStorage.setItem(Key.TOKEN, response.data.access_token);
          localStorage.setItem(Key.REFRESH_TOKEN, response.data.refresh_token);
        }),
        catchError(this.handleError),
      );

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
