import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { SecurityOverviewDataInterface } from '../interface/security-overview.interface';
import { environment } from '../../environments/environment';

/**
 * HTTP service for the admin-only security dashboard under {@code /admin/security/**}
 * (SRS FR-TPF-2), consumed by the {@code /security-overview} page.
 *
 * <p><b>Why it targets an /admin/** URL.</b> Same reasoning as {@link AnalyticsService}: routing
 * this page through a URL under {@code /admin/**} means SecurityConfig applies a genuine
 * server-side {@code UPDATE:USER}/{@code UPDATE:ROLE} check to the data itself. A user who
 * bypasses the SPA's {@code adminGuard} reaches a 403, not the platform's failed-login history.
 * That matters more here than on the billing screens — this endpoint returns the email addresses
 * of locked accounts and the IP addresses of flagged sign-ins, which is reconnaissance material if
 * it ever escaped its audience.
 *
 * <p>Deliberately no {@code tap(console.log)} — unlike the older services in this project, which
 * log every response for debugging. Logging this payload would write every flagged sign-in, IP
 * address, and locked account into the browser console, where it persists in the devtools buffer
 * and in any screen recording of a demo. The rest of the app's console noise is harmless; this
 * would not be.
 */
@Injectable({
  providedIn: 'root',
})
export class SecurityDashboardService {
  private readonly http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Fetches the whole dashboard in one request: counters, flagged sign-ins, the login-outcome
   * trend, restricted accounts, MFA adoption, and live session totals.
   *
   * <p>One request rather than six so every panel reflects the same instant — a suspicious login
   * cannot appear in the table while the counter above it still reads zero.
   *
   * @param days - how many days of history to summarise; the server clamps this to 1–90, so an
   *               out-of-range value degrades to the nearest sane window rather than erroring
   * @returns Observable emitting the envelope carrying {@code user} and {@code overview}
   */
  overview$ = (days = 7): Observable<CustomHttpResponseInterface<SecurityOverviewDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<SecurityOverviewDataInterface>>(`${this.server}/admin/security/overview?days=${days}`)
      .pipe(catchError(this.handleError));

  /**
   * Normalises HTTP errors into a single Observable<never>, mirroring the other services so
   * callers handle failures uniformly through {@code catchError}.
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
