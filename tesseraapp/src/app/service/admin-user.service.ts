import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
// To re-enable the commented `tap(console.log)` calls below, add `tap` back here:
// import { catchError, tap } from 'rxjs/operators';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { AdminUserDetailInterface, AdminUserListInterface } from '../interface/admin.interface';
import { environment } from '../../environments/environment';

/**
 * HTTP service for the administrative user-management endpoints (SRS §4.9).
 *
 * Talks to the backend's {@code AdminUserController} under {@code /admin/user}, which
 * is authority-gated server-side (UPDATE:USER / UPDATE:ROLE) — see SecurityConfig.
 * Kept separate from {@link UserService} so self-service account operations and
 * administrative operations on OTHER users never share a code path; that separation
 * is part of how the app closes the FR-RBAC-4 self-role-elevation gap.
 *
 * Mutations here are PATCH requests, so {@code cacheInterceptor} evicts its whole GET
 * cache on each one — the directory and detail views always refetch fresh state after
 * a role or account-state change.
 */
@Injectable({
  providedIn: 'root',
})
export class AdminUserService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Fetches one page of the user directory, optionally filtered by a search term
   * matched against first/last name and email (FR-ADMIN-1).
   *
   * @param page       - 0-based page index
   * @param searchTerm - free-text filter; empty string lists everyone
   * @param size       - rows per page (backend default and cap apply)
   * @returns Observable of the API envelope carrying users, paging metadata, and roles
   */
  users$ = (page = 0, searchTerm = '', size = 10): Observable<CustomHttpResponseInterface<AdminUserListInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<AdminUserListInterface>>(
        `${this.server}/admin/user/list?page=${page}&size=${size}&searchTerm=${encodeURIComponent(searchTerm)}`,
      )
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches the single-user management view: profile, role, account state, and the
   * first page of that user's audit events (FR-ADMIN-2).
   *
   * @param id - the managed user's primary key
   * @returns Observable of the API envelope carrying selectedUser, events, and roles
   */
  user$ = (id: number): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Reassigns another user's role (FR-ADMIN-3). The backend requires the
   * {@code UPDATE:ROLE} authority and rejects self-targeting, and records the change
   * as an audit event on the target user.
   *
   * @param id       - the managed user's primary key
   * @param roleName - the role to assign (e.g. {@code 'ROLE_MODERATOR'})
   * @returns Observable of the API envelope carrying the refreshed selectedUser
   */
  updateUserRole$ = (id: number, roleName: string): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/role/${roleName}`, {})
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Changes another user's account state — enabled and not-locked flags (FR-ADMIN-4).
   * The backend requires the {@code UPDATE:USER} authority, rejects self-targeting,
   * and records the change as an audit event on the target user. Field names must
   * match {@code SettingsForm.java} for Spring's {@code @RequestBody} binding.
   *
   * @param id       - the managed user's primary key
   * @param settings - object with {@code enabled} and {@code notLocked} booleans
   * @returns Observable of the API envelope carrying the refreshed selectedUser
   */
  updateAccountSettings$ = (id: number, settings: { enabled: boolean; notLocked: boolean }): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/settings`, settings)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches one page of a managed user's audit event history
   * ({@code GET /admin/user/:id/events?page=n}, requires UPDATE:USER or UPDATE:ROLE).
   *
   * Called by the admin user-detail view's pagination controls so subsequent pages
   * can be loaded without re-fetching the full user profile.
   *
   * @param id   - the managed user's primary key
   * @param page - zero-based page index
   * @param size - events per page (default 10)
   * @returns Observable of the API envelope carrying events and pagination metadata
   */
  userEvents$ = (id: number, page = 0, size = 10): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<AdminUserDetailInterface>>(
        `${this.server}/admin/user/${id}/events?page=${page}&size=${size}`,
      )
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Signs a managed user out of every device
   * ({@code DELETE /admin/user/:id/sessions}, requires UPDATE:USER).
   *
   * <p>This is the containment action, and it is distinct from locking the account. Locking stops
   * the <em>next</em> sign-in; it does nothing to sessions already open, because access tokens are
   * verified by signature alone and the holder's refresh token keeps minting new ones for five
   * days. Revoking the refresh families is what actually ends an intrusion — so on a suspected
   * compromise an administrator normally does both.
   *
   * <p>Organization-scoped and self-target-refused server-side; an administrator ending their own
   * sessions does it from their Security Center, which can spare the current device.
   *
   * @param id - the managed user's primary key
   * @returns Observable of the API envelope carrying the refreshed {@code selectedUser}
   */
  revokeSessions$ = (id: number): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/sessions`)
      .pipe(catchError(this.handleError));

  /**
   * Revokes ONE of a managed user's sessions, leaving their other devices signed in
   * ({@code DELETE /admin/user/:id/sessions/:family}, requires UPDATE:USER) — the granular
   * sibling of {@link revokeSessions$}, which ends all of them at once.
   *
   * @param id     - the managed user's primary key
   * @param family - the session (family) to revoke
   * @returns Observable of the API envelope carrying the refreshed selectedUser and remaining sessions
   */
  revokeSession$ = (id: number, family: string): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/sessions/${family}`)
      .pipe(catchError(this.handleError));

  /**
   * Revokes one of a managed user's passkeys — the admin "help reset" action for a lost or
   * compromised device ({@code DELETE /admin/user/:id/passkeys/:credentialId}, requires
   * UPDATE:USER). There is no "regenerate": a passkey's private key never leaves its
   * authenticator, so this forces the user to enroll a fresh one (or fall back to
   * password/TOTP) on their next sign-in.
   *
   * @param id           - the managed user's primary key
   * @param credentialId - the credential's database id (never the WebAuthn credential id itself)
   * @returns Observable of the API envelope carrying the refreshed selectedUser and passkey list
   */
  revokePasskey$ = (id: number, credentialId: number): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/passkeys/${credentialId}`)
      .pipe(catchError(this.handleError));

  /**
   * Revokes ALL of a managed user's passkeys in one action
   * ({@code DELETE /admin/user/:id/passkeys}, requires UPDATE:USER) — the bulk form of
   * {@link revokePasskey$}, for an account where every enrolled device is suspect.
   *
   * @param id - the managed user's primary key
   * @returns Observable of the API envelope carrying the refreshed selectedUser and (empty) passkey list
   */
  revokeAllPasskeys$ = (id: number): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/passkeys`)
      .pipe(catchError(this.handleError));

  /**
   * Force-disables a managed user's authenticator MFA ({@code DELETE /admin/user/:id/totp},
   * requires UPDATE:USER) — the admin recovery path for an account that has lost both its
   * authenticator and every recovery code, and so has no live code to present through the
   * self-service disable flow at all. Unlike {@link revokePasskey$}, there is nothing to pick —
   * one action, the whole authenticator state, gone.
   *
   * @param id - the managed user's primary key
   * @returns Observable of the API envelope carrying the refreshed selectedUser (usingTotp now false)
   */
  resetTotp$ = (id: number): Observable<CustomHttpResponseInterface<AdminUserDetailInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<AdminUserDetailInterface>>(`${this.server}/admin/user/${id}/totp`)
      .pipe(catchError(this.handleError));

  /**
   * Normalises HTTP errors into a single Observable<never> so all callers receive a
   * consistent Error instance — same contract as {@code UserService#handleError}.
   *
   * @param error - the HttpErrorResponse from Angular's HttpClient
   * @returns Observable that immediately errors with a human-readable message
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage: string;
    if (error.error instanceof ErrorEvent) {
      errorMessage = `An error occurred: ${error.error.message}`;
    } else if (error.error?.reason) {
      errorMessage = error.error.reason as string;
    } else {
      errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
