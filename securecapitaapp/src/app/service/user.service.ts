import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { UserInterface } from '../interface/user.interface';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  private http = inject(HttpClient);
  private readonly server: string = 'http://localhost:8080';

  verifyCode$ = (email: string, code: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/verify/code/${email}/${code}`)
      .pipe(tap(console.log), catchError(this.handleError));

  login$ = (email: string, password: string): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/login`, { email, password })
      .pipe(tap(console.log), catchError(this.handleError));

  // this method is how we are mapping the response from the server to the interface. We are going to be taking the exact values that the server returns, put it into an observable so that we can use it in various areas of our HTML templates. This is important to note, and we must make sure that we are mapping it properly. The HTTPResponseInterface is taking ProfileInterface as a generic type, which means that the data property of the HTTPResponseInterface will be of type ProfileInterface. This is important to note because it allows us to have a consistent structure for our HTTP responses while still being able to specify the type of data that we are expecting from the server. By using generics, we can ensure that our code is more flexible and reusable, as we can easily change the type of data we are working with without having to modify the structure of our HTTP responses. In this case, we are expecting the server to return a profile object that contains user information and tokens, and by specifying ProfileInterface as the generic type, we can ensure that our code is properly typed and can handle the response correctly.
  profile$ = (): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .get<
        CustomHttpResponseInterface<ProfileInterface>
      >(`${this.server}/user/profile`, { headers: new HttpHeaders().set('Authorization', 'Bearer eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJCT0JCWUxPTl9MTEMiLCJhdWQiOiJCT0JTX01BTkFHRU1FTlQiLCJpYXQiOjE3NzgxMjIwODEsInN1YiI6IjciLCJhdXRob3JpdGllcyI6WyJSRUFEOlVTRVIiLCJSRUFEOkNVU1RPTUVSIl0sImV4cCI6MTc3ODEzNTg4MX0.MHNcYmZSonrZ1JmQZ6tIkQD_clBtHajy3Nn1NdGTG-XZViOyGXCJWY0LE2998A0TUeVKiUB5RABuVp-PjbUl4w') })
      .pipe(tap(console.log), catchError(this.handleError));
  update$ = (user: UserInterface): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
    this.http
      .patch<
        CustomHttpResponseInterface<ProfileInterface>
      >(`${this.server}/user/update`, user, { headers: new HttpHeaders().set('Authorization', 'Bearer eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJCT0JCWUxPTl9MTEMiLCJhdWQiOiJCT0JTX01BTkFHRU1FTlQiLCJpYXQiOjE3NzgxMjIwODEsInN1YiI6IjciLCJhdXRob3JpdGllcyI6WyJSRUFEOlVTRVIiLCJSRUFEOkNVU1RPTUVSIl0sImV4cCI6MTc3ODEzNTg4MX0.MHNcYmZSonrZ1JmQZ6tIkQD_clBtHajy3Nn1NdGTG-XZViOyGXCJWY0LE2998A0TUeVKiUB5RABuVp-PjbUl4w') })
      .pipe(tap(console.log), catchError(this.handleError));
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
