import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { OrganizationCatalogInterface } from '../interface/organization.interface';
import { environment } from '../../environments/environment';

/**
 * HTTP service for one organization's external IdP configuration and its claimed SSO domains
 * (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 1).
 *
 * Talks to the backend's {@code OrganizationIdentityProviderController} under
 * {@code /admin/organization/:id/sso}, split out from {@link OrganizationService} because that
 * controller is gated more strictly than the rest of organization administration: every operation
 * here — including plain reads — requires the caller to be an unscoped tier or the
 * {@code ORG_ADMIN} of this specific organization, never bare membership. From here every call
 * either succeeds or surfaces a 403 through the same {@link handleError} path every other admin
 * service in this app uses.
 */
@Injectable({
  providedIn: 'root',
})
export class OrganizationSsoService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Fetches an organization's SSO configuration and its claimed domains together
   * ({@code GET /admin/organization/:id/sso}) — {@code config} is {@code undefined} for an
   * organization that has never configured SSO, which is not an error.
   *
   * @param organizationId - the organization to inspect
   * @returns Observable of the API envelope carrying {@code config} and {@code domains}
   */
  getConfig$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/sso`)
      .pipe(catchError(this.handleError));

  /**
   * Creates or replaces an organization's identity provider configuration, OIDC or SAML
   * ({@code PUT /admin/organization/:id/sso}) — the backend's
   * {@code OrganizationIdentityProviderController} routes on {@code protocol} to
   * {@code upsertOidcConfig}/{@code upsertSamlConfig} and clears whichever protocol's fields are
   * not sent, so switching an organization from OIDC to SAML (or back) is a normal save, not a
   * separate operation.
   *
   * <p>For OIDC, configuring SSO for the first time requires a client secret; editing an existing
   * configuration may omit it to keep the one already stored — see
   * {@code OrganizationIdentityProviderInterface#secretConfigured}'s Javadoc. SAML's metadata URI
   * is not sensitive and is always sent as entered.
   *
   * @param organizationId - the organization being configured
   * @param protocol       - {@code 'OIDC'} or {@code 'SAML'}
   * @param displayName    - shown on the login-page redirect
   * @param issuerUri      - OIDC only: the IdP's issuer URI (its {@code .well-known/openid-configuration} base)
   * @param clientId       - OIDC only: the OAuth2 client id registered with the IdP
   * @param clientSecret   - OIDC only: the OAuth2 client secret, or omit to keep the currently stored one
   * @param metadataUri    - SAML only: the IdP's SAML metadata document location
   * @returns Observable of the API envelope carrying the saved configuration
   */
  upsertConfig$ = (
    organizationId: number,
    protocol: 'OIDC' | 'SAML',
    displayName: string,
    issuerUri?: string,
    clientId?: string,
    clientSecret?: string,
    metadataUri?: string,
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .put<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/sso`, {
        protocol,
        displayName,
        issuerUri,
        clientId,
        clientSecret,
        metadataUri,
      })
      .pipe(catchError(this.handleError));

  /**
   * Activates or deactivates an organization's SSO configuration without deleting it
   * ({@code PATCH /admin/organization/:id/sso/status}).
   *
   * @param organizationId - the organization whose configuration is being toggled
   * @param status         - {@code 'ACTIVE'} or {@code 'INACTIVE'}
   * @returns Observable of the API envelope carrying the updated configuration
   */
  setStatus$ = (
    organizationId: number,
    status: 'ACTIVE' | 'INACTIVE',
  ): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/sso/status`, {
        status,
      })
      .pipe(catchError(this.handleError));

  /**
   * Removes an organization's SSO configuration entirely ({@code DELETE
   * /admin/organization/:id/sso}). Its members fall back to ordinary password or consumer-OAuth
   * login on their next sign-in.
   *
   * @param organizationId - the organization whose configuration is being removed
   * @returns Observable of the API envelope (no payload beyond the success message)
   */
  deleteConfig$ = (organizationId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/sso`)
      .pipe(catchError(this.handleError));

  /**
   * Claims an email domain for this organization's SSO routing
   * ({@code POST /admin/organization/:id/sso/domains}). Refused server-side if the domain is
   * already claimed by another organization (domain uniqueness is global).
   *
   * @param organizationId - the organization claiming the domain
   * @param domain         - the email domain to claim (e.g. {@code "acme.com"})
   * @returns Observable of the API envelope carrying the created domain row
   */
  addDomain$ = (organizationId: number, domain: string): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<OrganizationCatalogInterface>>(`${this.server}/admin/organization/${organizationId}/sso/domains`, {
        domain,
      })
      .pipe(catchError(this.handleError));

  /**
   * Releases a domain from this organization's SSO routing
   * ({@code DELETE /admin/organization/:id/sso/domains/:domainId}).
   *
   * @param organizationId - the organization the domain must currently belong to
   * @param domainId       - the domain row to remove
   * @returns Observable of the API envelope (no payload beyond the success message)
   */
  removeDomain$ = (organizationId: number, domainId: number): Observable<CustomHttpResponseInterface<OrganizationCatalogInterface>> =>
    this.http
      .delete<CustomHttpResponseInterface<OrganizationCatalogInterface>>(
        `${this.server}/admin/organization/${organizationId}/sso/domains/${domainId}`,
      )
      .pipe(catchError(this.handleError));

  /**
   * Normalizes HTTP errors into a single {@code Observable<never>} so every caller receives a
   * consistent {@code Error} instance — same contract as {@code OrganizationService#handleError}.
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
