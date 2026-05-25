import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Key } from '../enumeration/key.enumeration';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, filter, switchMap, take } from 'rxjs/operators';
import { UserService } from '../service/user.service';
import { ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';

// Module-level state: persists across requests so concurrent 401s share one refresh.
let isTokenRefreshing = false;
const refreshTokenSubject = new BehaviorSubject<CustomHttpResponseInterface<ProfileInterface> | null>(null);

/**
 * tokenInterceptor — Angular functional HTTP interceptor.
 *
 * An interceptor is a middleware function that sits in the HTTP pipeline
 * between the application and the server. Every request made with Angular's
 * HttpClient passes through this function before it leaves the browser, and
 * every response passes back through it on the way to the caller.
 *
 * This interceptor's responsibility is to automatically attach the JWT access
 * token to the Authorization header of every request targeting a protected
 * endpoint. On a 401 response it attempts a silent token refresh and retries
 * the original request once. If the refresh itself fails, tokens are cleared
 * and the error propagates so the app can redirect to log in.
 *
 * Registered globally in app.config.ts via:
 *   provideHttpClient(withInterceptors([tokenInterceptor]))
 *
 * @param req  - the outgoing HTTP request
 * @param next - the next handler in the pipeline; calling it forwards the request
 * @returns an Observable of the HTTP event stream for this request
 */
export const tokenInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
  const userService = inject(UserService);

  /**
   * Routes that must NOT have a token attached.
   *
   * These are public endpoints the user hits before or without a session:
   *   - login / register / resetpassword: no token exists yet
   *   - verify: account/2FA verification links are public
   *   - refresh: handled separately; sends the refresh
   *                                        token, not the access token
   *
   * Every other route is considered protected and will receive the token.
   */
  const publicRoutes = ['login', 'register', 'verify', 'resetpassword', 'refresh'];

  if (publicRoutes.some((route) => req.url.includes(route))) {
    return next(req);
  }

  return next(addAuthorizationTokenHeader(req, localStorage.getItem(Key.TOKEN))).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return handleRefreshToken(req, next, userService);
      }
      return throwError(() => error);
    }),
  );
};

/**
 * Creates a clone of the outgoing request with the Authorization header injected.
 *
 * HttpRequest objects are intentionally immutable — Angular forbids mutating
 * a request in-flight to prevent accidental side effects across the pipeline.
 * request.clone() produces a new request object with all the same properties,
 * overriding only what you specify. Here we override setHeaders to inject the
 * Bearer token without touching anything else (URL, body, method, etc.).
 *
 * @param request - the original outgoing HTTP request
 * @param token   - the JWT access token retrieved from localStorage; may be
 *                  null if the user has never logged in or has cleared storage
 * @returns a cloned HttpRequest carrying the Authorization: Bearer <token> header
 */
function addAuthorizationTokenHeader(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Attempts a silent token refresh when a 401 is received.
 *
 * If no refresh is already in flight, calls userService.refreshToken$() which
 * stores the new tokens in localStorage via its own tap operator. On success the
 * original request is retried with the new access token. On failure both tokens
 * are cleared and the error propagates.
 *
 * If a refresh is already in flight (concurrent 401s), this call waits on
 * refreshTokenSubject until the active refresh emits a response, then retries
 * with the token from that response — preventing a thundering-herd of parallel
 * refresh calls.
 *
 * @param req         - the original request that received the 401
 * @param next        - the next handler used to retry the request
 * @param userService - injected UserService used to call the refresh endpoint
 * @returns an Observable that emits the retried request's response
 */
function handleRefreshToken(req: HttpRequest<unknown>, next: HttpHandlerFn, userService: UserService): Observable<HttpEvent<unknown>> {
  if (!isTokenRefreshing) {
    console.log('Refreshing Token...');
    isTokenRefreshing = true;
    refreshTokenSubject.next(null);
    return userService.refreshToken$().pipe(
      switchMap((response) => {
        console.log('Token Refresh Response:', response);
        isTokenRefreshing = false;
        refreshTokenSubject.next(response);
        console.log('New Token:', response.data!.access_token);
        console.log('Sending original request:', req);
        return next(addAuthorizationTokenHeader(req, response.data!.access_token));
      }),
      catchError((error) => {
        isTokenRefreshing = false;
        localStorage.removeItem(Key.TOKEN);
        localStorage.removeItem(Key.REFRESH_TOKEN);
        return throwError(() => error);
      }),
    );
  }
  // Refresh already in flight — wait for the subject to emit a real response, then retry.
  return refreshTokenSubject.pipe(
    filter((response): response is CustomHttpResponseInterface<ProfileInterface> => response !== null),
    take(1),
    switchMap((response) => next(addAuthorizationTokenHeader(req, response.data!.access_token))),
  );
}
