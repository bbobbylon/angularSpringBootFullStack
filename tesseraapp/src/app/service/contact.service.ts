import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { environment } from '../../environments/environment';

/** The body {@code POST /contact} accepts. */
export interface ContactFormInterface {
  name: string;
  email: string;
  subject: string;
  message: string;
}

/**
 * HTTP service for the public Contact Us submission ({@code POST /contact}, no auth required).
 *
 * <p>Deliberately its own tiny service rather than folded into {@link UserService}: the route is
 * unauthenticated and unrelated to the user/session lifecycle every method there assumes, mirroring
 * why {@code AnalyticsService} and {@code AdminUserService} are split from it rather than merged in.
 */
@Injectable({
  providedIn: 'root',
})
export class ContactService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Submits a Contact Us form ({@code POST /contact}, public). The backend dispatches the email
   * asynchronously and always reports success once accepted — see {@code ContactController}.
   *
   * @param form - the visitor's name, email, subject, and message
   * @returns Observable of the API envelope (no data payload, just a confirmation message)
   */
  submit$ = (form: ContactFormInterface): Observable<CustomHttpResponseInterface<unknown>> =>
    this.http
      .post<CustomHttpResponseInterface<unknown>>(`${this.server}/contact`, form)
      .pipe(catchError(this.handleError));

  /**
   * Normalizes HTTP errors into a single Observable<never> so callers receive a consistent
   * Error instance, mirroring {@code UserService#handleError}.
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
