import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { ServiceAccountsDataInterface } from '../interface/serviceaccount.interface';
import { ApiKeysDataInterface } from '../interface/apikey.interface';
import { environment } from '../../environments/environment';

/**
 * HTTP service for administering service accounts and their API keys
 * (FUTURE-ENHANCEMENTS.md §3.1 "P2-3 — Machine-to-machine API access", Option A).
 *
 * Talks to the backend's {@code AdminServiceAccountController} under
 * {@code /admin/serviceaccounts}, gated server-side by the same {@code UPDATE:USER}/
 * {@code UPDATE:ROLE} authority as {@link AdminUserService} — kept as its own service rather than
 * folded into {@code AdminUserService} because a service account, while stored as an ordinary
 * {@code users} row, is administered through a distinct backend controller with its own nested
 * API-key resource, the same reasoning {@link OrganizationSsoService} documents for splitting off
 * from {@code OrganizationService}.
 */
@Injectable({
  providedIn: 'root',
})
export class AdminServiceAccountService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Lists every service account ({@code GET /admin/serviceaccounts}).
   *
   * @returns Observable of the API envelope carrying {@code serviceAccounts}
   */
  list$ = (): Observable<CustomHttpResponseInterface<ServiceAccountsDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ServiceAccountsDataInterface>>(`${this.server}/admin/serviceaccounts`)
      .pipe(catchError(this.handleError));

  /**
   * Creates a new service account ({@code POST /admin/serviceaccounts}). The backend rejects a
   * {@code role} above the creating admin's own tier ({@code RoleType#canAssign}) regardless of
   * what the role picker offers.
   *
   * @param name - a caller-chosen display name, e.g. {@code "CI pipeline"}
   * @param role - the role to assign, e.g. {@code "ROLE_MODERATOR"}
   * @returns Observable of the API envelope carrying the created {@code serviceAccount}
   */
  create$ = (name: string, role: string): Observable<CustomHttpResponseInterface<ServiceAccountsDataInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ServiceAccountsDataInterface>>(`${this.server}/admin/serviceaccounts`, { name, role })
      .pipe(catchError(this.handleError));

  /**
   * Deactivates a service account ({@code DELETE /admin/serviceaccounts/:id}) — sets
   * {@code enabled = false} so it can no longer authenticate with any of its API keys, revoked or
   * not. Not a hard delete, and reversible: the account is still an ordinary row in the Users
   * directory, so {@code AdminUserService#updateAccountSettings$} can re-enable it from there.
   *
   * @param id - the service account's {@code users.id}
   * @returns Observable of the API envelope carrying the refreshed {@code serviceAccounts} list
   */
  deactivate$ = (id: number): Observable<CustomHttpResponseInterface<ServiceAccountsDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<ServiceAccountsDataInterface>>(`${this.server}/admin/serviceaccounts/${id}`)
      .pipe(catchError(this.handleError));

  /**
   * Lists a service account's API keys — active, revoked, and expired
   * ({@code GET /admin/serviceaccounts/:id/apikeys}).
   *
   * @param id - the service account's {@code users.id}
   * @returns Observable of the API envelope carrying {@code apiKeys}
   */
  listApiKeys$ = (id: number): Observable<CustomHttpResponseInterface<ApiKeysDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ApiKeysDataInterface>>(`${this.server}/admin/serviceaccounts/${id}/apikeys`)
      .pipe(catchError(this.handleError));

  /**
   * Issues a new API key for a service account ({@code POST /admin/serviceaccounts/:id/apikeys}).
   * The response's {@code rawKey} is the <b>only</b> time the plaintext key is ever sent by the
   * server — the caller must display and let the admin copy it before moving on.
   *
   * @param id        - the service account's {@code users.id}
   * @param name      - a caller-chosen label, e.g. {@code "CI pipeline"}
   * @param expiresAt - optional {@code YYYY-MM-DDTHH:mm:ss} expiry; omit for a key that never expires
   * @returns Observable of the API envelope carrying {@code rawKey} and the refreshed {@code apiKeys} list
   */
  issueApiKey$ = (id: number, name: string, expiresAt?: string): Observable<CustomHttpResponseInterface<ApiKeysDataInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ApiKeysDataInterface>>(`${this.server}/admin/serviceaccounts/${id}/apikeys`, { name, expiresAt })
      .pipe(catchError(this.handleError));

  /**
   * Revokes one of a service account's API keys
   * ({@code DELETE /admin/serviceaccounts/:id/apikeys/:keyId}).
   *
   * @param id    - the service account's {@code users.id}
   * @param keyId - the key's id; the backend refuses this call if it does not belong to {@code id}
   * @returns Observable of the API envelope carrying the refreshed {@code apiKeys} list
   */
  revokeApiKey$ = (id: number, keyId: number): Observable<CustomHttpResponseInterface<ApiKeysDataInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<ApiKeysDataInterface>>(`${this.server}/admin/serviceaccounts/${id}/apikeys/${keyId}`)
      .pipe(catchError(this.handleError));

  /**
   * Normalizes HTTP errors into a single {@code Observable<never>} so every caller receives a
   * consistent {@code Error} instance — same contract as {@code AdminUserService#handleError}.
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
