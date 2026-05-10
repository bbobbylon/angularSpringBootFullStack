import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { CustomerListData, StatsData } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { Key } from '../enumeration/key.enumeration';

/**
 * Central HTTP service for all customer and invoice API calls.
 *
 * Each method returns a typed Observable wrapping the server's standard
 * {@link CustomHttpResponseInterface} envelope. Errors are normalised by
 * {@link handleError} into a single error Observable so callers can handle
 * failures uniformly via {@code catchError}.
 */
@Injectable({
  providedIn: 'root',
})
export class CustomerService {
  private http = inject(HttpClient);
  private readonly server: string = 'http://localhost:8080';

  /**
   * Fetches aggregated dashboard statistics: total customers, invoices, and billed amount.
   *
   * Currently unused — stats are fetched alongside the customer list via {@code customers$}
   * and passed to {@link StatsComponent} via {@code @Input}.
   *
   * TODO: Wire this method into {@link StatsComponent} once the rest of the application is
   *  complete, so the stats panel fetches and refreshes independently of the customer list.
   *
   * @returns Observable emitting a {@link StatsData} response containing the system-wide totals
   */
  stats$ = (): Observable<CustomHttpResponseInterface<StatsData>> =>
    this.http
      .get<CustomHttpResponseInterface<StatsData>>(`${this.server}/customer/stats`)
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Fetches a paginated page of customers.
   *
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @returns Observable emitting a {@link CustomerListData} response containing the page and stats
   */
  customers$ = (page = 0, size = 20): Observable<CustomHttpResponseInterface<CustomerListData>> =>
    //TODO allow sorting, filtering, and infinite scrolling later
    this.http
      .get<CustomHttpResponseInterface<CustomerListData>>(`${this.server}/customer/list?page=${page}&size=${size}`)
      .pipe(tap(console.log), catchError(this.handleError));

  /**
   * Clears the access and refresh tokens from localStorage, ending the user's session.
   */
  logOut() {
    localStorage.removeItem(Key.TOKEN);
    localStorage.removeItem(Key.REFRESH_TOKEN);
  }

  /**
   * Normalises HTTP errors into a single Observable<never> so all callers
   * receive a consistent Error instance regardless of whether the failure
   * was a client-side network event or a structured server error response.
   *
   * @param error - the HttpErrorResponse from Angular's HttpClient
   * @returns Observable that immediately errors with a human-readable message
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage: string;

    if (error.error instanceof ErrorEvent) {
      errorMessage = `An error occurred: ${error.error.message}`;
    } else {
      if (error.error?.reason) {
        errorMessage = error.error.reason as string;
        console.log(error.error);
        console.log(errorMessage);
        console.log(error);
      } else {
        errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
      }
    }
    console.error(errorMessage);

    return throwError(() => new Error(errorMessage));
  }
}
