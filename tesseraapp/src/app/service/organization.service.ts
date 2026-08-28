import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { OrganizationCatalogInterface, OrganizationInvitePreviewInterface } from '../interface/organization.interface';
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
   * unscoped tiers. {@code options} covers the org-setup payload (2026-08-28) — profile, tenant
   * UUID, MFA policy, feature flags, customers to attach, and an opt-in creation confirmation
   * email — all optional, mirroring the backend's {@code OrganizationForm}; omit entirely for the
   * plain "just a name" create.
   *
   * @param name    - the organization's display name
   * @param options - the optional org-setup fields
   * @returns Observable of the API envelope carrying the created organization and refreshed catalog
   */
  createOrganization$ = (
    name: string,
    options?: {
      description?: string;
      contactEmail?: string;
      website?: string;
      tenantUuid?: string;
      mfaAllowedMethods?: string[];
      featureFlags?: string[];
      customerIds?: number[];
      sendConfirmationEmail?: boolean;
    },
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization`, { name, ...options })
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
   * @param orgRole        - the capacity to grant ({@code ORG_ADMIN}/{@code ORG_MEMBER}/
   *                         {@code ORG_VIEWER}), or omit for the server default ({@code ORG_MEMBER})
   * @returns Observable of the API envelope (no payload beyond the success message)
   */
  addMember$ = (
    organizationId: number,
    userId: number,
    orgRole?: string,
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/members/${userId}`,
        {},
        orgRole ? { params: { orgRole } } : {},
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
   * Reassigns a member's capacity within one organization
   * ({@code PATCH /admin/organization/:id/members/:userId/role}) — the promote/demote lever for
   * {@code userorganizations.org_role}, distinct from {@link AdminUserService#updateUserRole$}
   * which reassigns the member's <em>global</em> role. Same authorization rule as
   * {@link members$}, plus a server-side ceiling: the caller can never grant a capacity above
   * their own in this organization, and the last active {@code ORG_ADMIN} cannot be demoted.
   *
   * @param organizationId - the organization the membership belongs to
   * @param userId         - the member being reassigned
   * @param orgRole        - the capacity to grant ({@code ORG_ADMIN}/{@code ORG_MEMBER}/
   *                         {@code ORG_VIEWER})
   * @returns Observable of the API envelope (no payload beyond the success message)
   */
  setMemberOrgRole$ = (
    organizationId: number,
    userId: number,
    orgRole: string,
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/members/${userId}/role`,
        {},
        { params: { orgRole } },
      )
      .pipe(catchError(this.handleError));

  /**
   * Updates an organization's profile fields ({@code PATCH /admin/organization/:id/profile}).
   * Refused server-side below the unscoped tiers, same rule as {@link renameOrganization$}. Each
   * field independently clears to {@code null} server-side when omitted or blank — callers pass
   * exactly what the form submitted.
   *
   * @param id           - the organization's database primary key
   * @param description  - free-form description, or blank/undefined to clear it
   * @param contactEmail - organization contact email, or blank/undefined to clear it
   * @param website      - organization website, or blank/undefined to clear it
   * @returns Observable of the API envelope carrying the updated organization
   */
  updateOrganizationProfile$ = (
    id: number,
    description?: string,
    contactEmail?: string,
    website?: string,
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${id}/profile`, {
        description,
        contactEmail,
        website,
      })
      .pipe(catchError(this.handleError));

  /**
   * Sets an organization's external tenant UUID — exactly once
   * ({@code PATCH /admin/organization/:id/tenant-uuid}). Refused server-side below the unscoped
   * tiers, same rule as {@link updateOrganizationProfile$}, and refused if the organization
   * already has one set — there is no "change" call.
   *
   * @param id         - the organization's database primary key
   * @param tenantUuid - the UUID to set
   * @returns Observable of the API envelope carrying the updated organization
   */
  setTenantUuid$ = (id: number, tenantUuid: string): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${id}/tenant-uuid`, { tenantUuid })
      .pipe(catchError(this.handleError));

  /**
   * Updates an organization's enforcement-relevant settings — MFA-allowed-methods policy and
   * feature-flag labels ({@code PATCH /admin/organization/:id/settings}). Refused server-side
   * below the unscoped tiers, same rule as {@link updateOrganizationProfile$}.
   *
   * <p>Both arguments are always sent as the caller's full, explicit replacement value (an empty
   * array clears that setting back to "not configured") — unlike the backend's own
   * {@code null}-means-"leave unchanged" contract, this app's Settings tab always has the current
   * value loaded before it lets an admin submit, so there is never a partial update to express.
   *
   * @param id                - the organization's database primary key
   * @param mfaAllowedMethods - the full replacement MFA policy; empty clears it
   * @param featureFlags      - the full replacement feature-flag labels; empty clears them
   * @returns Observable of the API envelope carrying the updated organization
   */
  updateOrganizationSettings$ = (
    id: number,
    mfaAllowedMethods: string[],
    featureFlags: string[],
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${id}/settings`, {
        mfaAllowedMethods,
        featureFlags,
      })
      .pipe(catchError(this.handleError));

  /**
   * Lists the customers attached to one organization ({@code GET
   * /admin/organization/:id/customers}) — the read side of {@link createOrganization$}'s
   * {@code customerIds} attachment. Same authorization rule as {@link members$}.
   *
   * @param organizationId - the organization whose customers to list
   * @returns Observable of the API envelope carrying the organization's attached customers
   */
  orgCustomers$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/customers`)
      .pipe(catchError(this.handleError));

  /**
   * Lists the invoices belonging to one organization's attached customers ({@code GET
   * /admin/organization/:id/invoices}). Same authorization rule as {@link members$}.
   *
   * @param organizationId - the organization whose invoices to list
   * @returns Observable of the API envelope carrying the organization's invoices
   */
  orgInvoices$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/invoices`)
      .pipe(catchError(this.handleError));

  /**
   * Fetches one organization's KPI tiles ({@code GET /admin/organization/:id/stats}) for the
   * dashboard-style Organizations page's card grid. Same authorization rule as {@link members$}.
   *
   * @param organizationId - the organization to summarize
   * @returns Observable of the API envelope carrying the organization's stats
   */
  orgStats$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/stats`)
      .pipe(catchError(this.handleError));

  /**
   * Fetches one page of an organization's audit trail, newest first
   * ({@code GET /admin/organization/:id/events}). Same authorization rule as {@link members$}.
   *
   * @param organizationId - the organization whose activity to retrieve
   * @param page           - zero-based page index (defaults to 0)
   * @param size           - rows per page (defaults to 20)
   * @returns Observable of the API envelope carrying the page of events and a total count
   */
  orgEvents$ = (organizationId: number, page = 0, size = 20): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/events?page=${page}&size=${size}`,
      )
      .pipe(catchError(this.handleError));

  /**
   * Creates a single-use invite for an organization ({@code POST
   * /admin/organization/:id/invites}). Same authorization rule as {@link members$}, plus a
   * server-side role-tier ceiling: the invite can never grant a role the creator could not
   * otherwise assign directly.
   *
   * @param organizationId - the organization the invite joins its redeemer to
   * @param roleName       - the role granted on redemption; omit for the server default (ROLE_USER)
   * @param ttlHours       - how many hours the invite remains redeemable; omit for the server default (168)
   * @returns Observable of the API envelope carrying the created invite and the refreshed active-invite list
   */
  createInvite$ = (
    organizationId: number,
    roleName?: string,
    ttlHours?: number,
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/invites`, {
        roleName,
        ttlHours,
      })
      .pipe(catchError(this.handleError));

  /**
   * Lists an organization's outstanding invites ({@code GET /admin/organization/:id/invites}).
   * Same authorization rule as {@link members$}.
   *
   * @param organizationId - the organization whose invites to list
   * @returns Observable of the API envelope carrying the organization's active invites
   */
  activeInvites$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/invites`)
      .pipe(catchError(this.handleError));

  /**
   * Revokes an outstanding invite before it is redeemed ({@code DELETE
   * /admin/organization/:id/invites/:inviteId}). Same authorization rule as {@link members$}.
   *
   * @param organizationId - the organization the invite belongs to
   * @param inviteId       - the invite to revoke
   * @returns Observable of the API envelope carrying the organization's refreshed active invites
   */
  revokeInvite$ = (organizationId: number, inviteId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/invites/${inviteId}`,
      )
      .pipe(catchError(this.handleError));

  /**
   * Previews an invite's organization name ({@code GET /user/organization/invite/:code}) so the
   * join page can ask "Join {name}?" before the user commits. Unlike every other method on this
   * service, this hits {@code /user/organization/**} — reachable by any authenticated user, not
   * just an administrator, since the person opening a shared invite link is by definition not yet
   * a member of the organization they're joining. Resolves to the same generic error for an
   * unknown or expired code either way (NFR-SEC-7).
   *
   * @param code - the invite code from the join link
   * @returns Observable of the API envelope carrying the organization's name
   */
  previewInvite$ = (code: string): Observable<CustomHttpResponseInterface<OrganizationInvitePreviewInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationInvitePreviewInterface>>(`${this.server}/user/organization/invite/${code}`)
      .pipe(catchError(this.handleError));

  /**
   * Redeems an invite ({@code POST /user/organization/invite/:code/redeem}): joins the caller to
   * the invite's organization with its granted role, then consumes the invite so it cannot be
   * redeemed twice. Same {@code /user/organization/**} reachability as {@link previewInvite$}.
   *
   * @param code - the invite code from the join link
   * @returns Observable of the API envelope carrying the organization the caller just joined
   */
  redeemInvite$ = (code: string): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/user/organization/invite/${code}/redeem`, {})
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
