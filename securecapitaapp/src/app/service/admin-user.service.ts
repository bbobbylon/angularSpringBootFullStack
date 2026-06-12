import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { AdminUserDetailInterface, AdminUserListInterface } from '../interface/admin.interface';

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
  private readonly server: string = 'http://localhost:8080';

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
      .pipe(tap(console.log), catchError(this.handleError));

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
