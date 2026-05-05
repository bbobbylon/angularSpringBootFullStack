import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { Profile } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  verifyCode$ = (email: string, code: string): Observable<CustomHttpResponseInterface<Profile>> =>
    this.http
      .get<CustomHttpResponseInterface<Profile>>(`${this.server}/user/verify/code/${email}/${code}`)
      .pipe(tap(console.log), catchError(this.handleError));
  private http = inject(HttpClient);

  private readonly server: string = 'http://localhost:8080';

  login$ = (email: string, password: string): Observable<CustomHttpResponseInterface<Profile>> =>
    this.http
      .post<CustomHttpResponseInterface<Profile>>(`${this.server}/user/login`, { email, password })
      .pipe(tap(console.log), catchError(this.handleError));

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage: string;

    if (error.error instanceof ErrorEvent) {
      errorMessage = `An error occurred: ${error.error.message}`;
    } else {
      if (error.error?.reason) {
        errorMessage = error.error.reason as string;
      } else {
        errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
      }
    }
    console.error(errorMessage);

    return throwError(() => new Error(errorMessage));
  }
}
