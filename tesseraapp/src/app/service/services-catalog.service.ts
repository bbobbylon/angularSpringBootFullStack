import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { ServicesInterface } from '../interface/services.interface';
import { UserInterface } from '../interface/user.interface';
import { environment } from '../../environments/environment';

/** The {@code data} block of an admin catalog list response. */
export interface ServicesListDataInterface {
  user: UserInterface;
  services: ServicesInterface[];
}

/** The {@code data} block of the public (unauthenticated) catalog response — no {@code user}. */
export interface PublicServicesListDataInterface {
  services: ServicesInterface[];
}

/** The {@code data} block of a single-service admin response. */
export interface ServiceDataInterface {
  user: UserInterface;
  service: ServicesInterface;
}

/**
 * HTTP service for administering the services catalog under {@code /admin/services/**}
 * (ROADMAP §2 — "Create / manage services").
 *
 * <p><b>Why it is separate from {@link CustomerService}.</b> Browsing the catalog and administering
 * it are different operations with different audiences. An authenticated user raising an invoice
 * reads the catalog through {@code GET /customer/invoice/new} (see {@code CustomerService}), an
 * anonymous visitor reads it through {@code GET /services/public} (see {@link listPublic$}), and
 * only staff may change it. Pointing the write operations at {@code /admin/services/**} means
 * SecurityConfig's existing {@code /admin/**} matcher enforces {@code UPDATE:USER}/{@code
 * UPDATE:ROLE} on the server, so the SPA hiding the buttons is a convenience rather than the
 * control.
 *
 * <p>One genuine difference in what the admin path returns: it includes <em>retired</em> services,
 * which both read-only paths deliberately omit. An administrator needs to see them (to reinstate
 * one, or to know why it is missing from the invoice form); a visitor or invoicing user must not be
 * offered something the business no longer sells.
 */
@Injectable({
  providedIn: 'root',
})
export class ServicesCatalogService {
  private readonly http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Lists the whole catalog, retired entries included.
   *
   * @returns Observable emitting the envelope carrying {@code user} and {@code services}
   */
  list$ = (): Observable<CustomHttpResponseInterface<ServicesListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ServicesListDataInterface>>(`${this.server}/admin/services/list`)
      .pipe(catchError(this.handleError));

  /**
   * Lists the active catalog for an unauthenticated visitor — {@code GET /services/public}.
   *
   * <p>No {@code Authorization} header is sent or required; {@code CustomAuthFilter} skips this
   * path entirely (it is in {@code Constants.PUBLIC_ROUTES}), so a stale or absent token never
   * turns into an error here the way it would on an authenticated endpoint.
   *
   * @returns Observable emitting the envelope carrying {@code services} (active only, no {@code user})
   */
  listPublic$ = (): Observable<CustomHttpResponseInterface<PublicServicesListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<PublicServicesListDataInterface>>(`${this.server}/services/public`)
      .pipe(catchError(this.handleError));

  /**
   * Adds a service to the catalog.
   *
   * @param service - the name, description and price to create; any id is ignored server-side
   * @returns Observable emitting the envelope carrying the persisted {@code service}
   */
  create$ = (service: Partial<ServicesInterface>): Observable<CustomHttpResponseInterface<ServiceDataInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ServiceDataInterface>>(`${this.server}/admin/services/create`, service)
      .pipe(catchError(this.handleError));

  /**
   * Edits an existing catalog entry.
   *
   * <p>Note this does not restate history: invoices already raised keep the name and price they
   * captured at the time, which is why correcting a typo here is safe.
   *
   * @param serviceId - the entry to edit
   * @param service - the submitted name, description and price
   * @returns Observable emitting the envelope carrying the updated {@code service}
   */
  update$ = (serviceId: number, service: Partial<ServicesInterface>): Observable<CustomHttpResponseInterface<ServiceDataInterface>> =>
    this.http
      .put<CustomHttpResponseInterface<ServiceDataInterface>>(`${this.server}/admin/services/update/${serviceId}`, service)
      .pipe(catchError(this.handleError));

  /**
   * Retires or reinstates a catalog entry.
   *
   * @param serviceId - the entry to change
   * @param active - true to offer the service, false to retire it
   * @returns Observable emitting the envelope carrying the updated {@code service}
   */
  setActive$ = (serviceId: number, active: boolean): Observable<CustomHttpResponseInterface<ServiceDataInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<ServiceDataInterface>>(`${this.server}/admin/services/${serviceId}/active/${active}`, {})
      .pipe(catchError(this.handleError));

  /**
   * Normalizes HTTP errors into a single Observable<never>, matching the other services.
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
