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
 * <p>Deliberately no response logging. Writing this payload to the console would put every
 * flagged sign-in, IP address, and locked account into the devtools buffer — and into any screen
 * recording of a demo. The rest of the application no longer logs responses either (the
 * {@code tap(console.log)} that used to sit on every service was removed once it became clear it
 * was emitting access and refresh tokens verbatim), so this is now the house rule rather than an
 * exception to it.
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
   * <p>The two growing tables — flagged sign-ins and restricted accounts — are paged
   * <em>independently</em>. They are separate query parameters rather than one shared `page`
   * because an administrator working down the restricted-accounts list must not have their place
   * reset because they stepped forward through flagged sign-ins in the panel above.
   *
   * @param days - how many days of history to summarise; the server clamps this to 1–90, so an
   *               out-of-range value degrades to the nearest sane window rather than erroring
   * <p>Each table's row count is a separate parameter for the same reason, and the server clamps
   * both to 1–100 — so a value outside that range degrades to the nearest sane page instead of
   * erroring, and the {@code PageInfo} that comes back states the size actually applied rather than
   * the one that was asked for.
   *
   * @param suspiciousPage - 0-based page of the flagged sign-ins table
   * @param suspiciousSize - rows per page for that table; clamped server-side to 1–100
   * @param restrictedPage - 0-based page of the locked/disabled accounts table
   * @param restrictedSize - rows per page for that table; clamped server-side to 1–100
   * @returns Observable emitting the envelope carrying {@code user} and {@code overview}; each
   *          paged table's metadata rides along as a {@code PageInfo} inside {@code overview}
   */
  overview$ = (
    days = 7,
    suspiciousPage = 0,
    suspiciousSize = 50,
    restrictedPage = 0,
    restrictedSize = 50,
  ): Observable<CustomHttpResponseInterface<SecurityOverviewDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<SecurityOverviewDataInterface>>(
        `${this.server}/admin/security/overview?days=${days}` +
          `&suspiciousPage=${suspiciousPage}&suspiciousSize=${suspiciousSize}` +
          `&restrictedPage=${restrictedPage}&restrictedSize=${restrictedSize}`,
      )
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
