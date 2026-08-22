import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { OrganizationCatalogInterface } from '../interface/organization.interface';
import { environment } from '../../environments/environment';

/**
 * HTTP service for the organization administration endpoints
 * (FUTURE-ENHANCEMENTS.md §3.2 "Self-service organization management").
 *
 * Talks to the backend's {@code OrganizationController} under {@code /admin/organization},
 * which is gated server-side by the {@code UPDATE:ORGANIZATION} authority — see
 * {@code SecurityConfig}. That controller enforces two distinct authorization families
 * per-endpoint (unscoped-tier-only catalog mutation vs. membership mutation additionally open
 * to a {@code ROLE_ORGANIZATION_ADMIN} acting on their own organization), but from here every
 * call either succeeds or surfaces a 403 through the same {@link handleError} path every other
 * admin service in this app uses.
 */
@Injectable({
  providedIn: 'root',
})
export class OrganizationService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Lists the organizations the caller may see ({@code GET /admin/organization}): the full
   * catalog for an unscoped tier, or only the organizations they actively belong to otherwise.
   *
   * @returns Observable of the API envelope carrying the in-scope organizations
   */
  organizations$ = (): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization`)
      .pipe(catchError(this.handleError));

  /**
   * Creates a new organization ({@code POST /admin/organization}). Refused server-side below the
   * unscoped tiers.
   *
   * @param name - the organization's display name
   * @returns Observable of the API envelope carrying the created organization and refreshed catalog
   */
  createOrganization$ = (name: string): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization`, { name })
      .pipe(catchError(this.handleError));

  /**
   * Renames an organization ({@code PATCH /admin/organization/:id/name}). Refused server-side
   * below the unscoped tiers.
   *
   * @param id   - the organization's database primary key
   * @param name - the replacement display name
   * @returns Observable of the API envelope carrying the renamed organization and refreshed catalog
   */
  renameOrganization$ = (id: number, name: string): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${id}/name`, { name })
      .pipe(catchError(this.handleError));

  /**
   * Activates or deactivates an organization ({@code PATCH /admin/organization/:id/status}) —
   * the retirement lever; there is no delete endpoint (see {@code OrganizationInterface}'s
   * Javadoc for why). Refused server-side below the unscoped tiers.
   *
   * @param id     - the organization's database primary key
   * @param status - {@code 'ACTIVE'} or {@code 'INACTIVE'}
   * @returns Observable of the API envelope carrying the updated organization and refreshed catalog
   */
  setOrganizationStatus$ = (id: number, status: 'ACTIVE' | 'INACTIVE'): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${id}/status`, { status })
      .pipe(catchError(this.handleError));

  /**
   * Lists the active members of one organization ({@code GET /admin/organization/:id/members}).
   * Refused server-side unless the caller is an unscoped tier or a
   * {@code ROLE_ORGANIZATION_ADMIN} actively belonging to that organization.
   *
   * @param organizationId - the organization whose members to list
   * @returns Observable of the API envelope carrying the organization's active members
   */
  members$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/members`)
      .pipe(catchError(this.handleError));

  /**
   * Adds (or reactivates a previously removed) member of an organization
   * ({@code POST /admin/organization/:id/members/:userId}). Same authorization rule as
   * {@link members$}.
   *
   * @param organizationId - the organization to add the member to
   * @param userId         - the user to add
   * @returns Observable of the API envelope (no payload beyond the success message)
   */
  addMember$ = (organizationId: number, userId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/members/${userId}`,
        {},
      )
      .pipe(catchError(this.handleError));

  /**
   * Removes a member from an organization by deactivating their membership
   * ({@code DELETE /admin/organization/:id/members/:userId}). Same authorization rule as
   * {@link members$}.
   *
   * @param organizationId - the organization to remove the member from
   * @param userId         - the user to remove
   * @returns Observable of the API envelope (no payload beyond the success message)
   */
  removeMember$ = (organizationId: number, userId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/members/${userId}`,
      )
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
