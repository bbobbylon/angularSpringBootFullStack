import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import {
  CustomerListDataInterface,
  InvoiceListDataInterface,
  StatsDataInterface,
} from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { environment } from '../../environments/environment';

/**
 * HTTP service for the admin-only reporting surface under {@code /admin/analytics/**},
 * consumed by the Billing overview ({@code /billing}) and Analytics hub ({@code
 * /analytics}) pages.
 *
 * <p><b>Why this is separate from {@link CustomerService}.</b> The billing and analytics
 * dashboards visualise <em>aggregate financial</em> data that is genuinely admin-only,
 * whereas {@link CustomerService} hits the application-wide {@code /customer/**} endpoints
 * that every authenticated user can reach (home, customers, invoices). Routing these two
 * pages through their own service — which targets {@code /admin/analytics/**} — means the
 * backend enforces a real {@code UPDATE:USER}/{@code UPDATE:ROLE} authority check on the
 * data, so a plain {@code ROLE_USER} who bypasses the SPA's {@code adminGuard} still gets
 * a 403 from the API rather than the rollups. The response envelopes reuse the same data
 * keys as their {@code /customer/**} counterparts, so the components' interfaces and
 * computed signals are unchanged — only the URL (and its enforcement) differs.
 *
 * <p>Errors are normalised by {@link handleError} into a single error Observable, matching
 * {@link CustomerService}, so callers handle failures uniformly via {@code catchError}.
 */
@Injectable({
  providedIn: 'root',
})
export class AnalyticsService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Fetches the admin KPI summary: system-wide totals and the per-status customer
   * breakdown. Backs the Billing overview's scorecards.
   *
   * @returns Observable emitting a {@link StatsDataInterface} response ({@code user} + {@code stats})
   */
  summary$ = (): Observable<CustomHttpResponseInterface<StatsDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<StatsDataInterface>>(`${this.server}/admin/analytics/summary`)
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Fetches a paginated page of customers for the Analytics hub's growth/acquisition
   * charts. Same {@code page} shape as {@code CustomerService.customers$}, admin-gated.
   *
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @returns Observable emitting a {@link CustomerListDataInterface} response
   */
  customers$ = (page = 0, size = 20): Observable<CustomHttpResponseInterface<CustomerListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<CustomerListDataInterface>>(
        `${this.server}/admin/analytics/customers?page=${page}&size=${size}`,
      )
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Fetches a paginated page of invoices for the revenue/status charts on both
   * dashboards. Same {@code invoices} shape as {@code CustomerService.invoices$},
   * admin-gated.
   *
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @returns Observable emitting an {@link InvoiceListDataInterface} response
   */
  invoices$ = (page = 0, size = 20): Observable<CustomHttpResponseInterface<InvoiceListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<InvoiceListDataInterface>>(
        `${this.server}/admin/analytics/invoices?page=${page}&size=${size}`,
      )
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Normalises HTTP errors into a single Observable<never> so all callers receive a
   * consistent Error instance, mirroring {@code CustomerService.handleError}.
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
