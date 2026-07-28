import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Key } from '../enumeration/key.enumeration';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, filter, switchMap, take } from 'rxjs/operators';
import { UserService } from '../service/user.service';
import { ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';

/**
 * The settled result of a refresh attempt, broadcast to every request that parked itself
 * behind the in-flight refresh.
 *
 * <p>Failure is modelled as a *value* rather than an RxJS error because the channel carrying it
 * is a long-lived {@link BehaviorSubject} shared by the whole application. Calling
 * {@code subject.error()} would terminate that subject permanently, so the first failed refresh
 * of the session would leave every later refresh with a dead notification channel. Emitting a
 * failure marker lets each waiter re-raise the error on its own stream while the shared subject
 * stays usable for the next cycle.
 */
type RefreshOutcome =
  | { readonly settled: 'success'; readonly response: CustomHttpResponseInterface<ProfileInterface> }
  | { readonly settled: 'failure'; readonly error: unknown };

// Module-level state: persists across requests so concurrent 401s share one refresh.
let isTokenRefreshing = false;
const refreshTokenSubject = new BehaviorSubject<RefreshOutcome | null>(null);

/**
 * Test-only hook that returns the shared refresh state to its initial values.
 *
 * <p>The state above is module-level by design — one refresh must be shared by every concurrent
 * request — but that also means it outlives any individual {@code TestBed}. Without this, a spec
 * that leaves a refresh in flight silently changes the branch the *next* spec takes, turning an
 * unrelated failure into a cascade. Production code must never call this: clearing the flag while
 * a refresh is genuinely in flight would let a second refresh start and rotate the token out from
 * under the first.
 */
export function __resetTokenRefreshStateForTests(): void {
  isTokenRefreshing = false;
  refreshTokenSubject.next(null);
}

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
   *
   * Matched against whole path *segments*, not as substrings of the raw URL. A plain
   * {@code url.includes('login')} also matches a protected request that merely mentions one of
   * these words in a query value — {@code /customer/search?name=login} — and such a request would
   * then be sent with no Authorization header at all, come back 401, and (being on the
   * pass-through branch) not even attempt a refresh. Trimming the query string and comparing
   * segments keeps the decision tied to the endpoint being called rather than to user input.
   */
  const publicRoutes = ['login', 'register', 'verify', 'resetpassword', 'refresh'];
  const pathSegments = req.url.split(/[?#]/)[0].split('/');

  if (publicRoutes.some((route) => pathSegments.includes(route))) {
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
 * refreshTokenSubject until the active refresh settles, then either retries with
 * the new token or re-raises the refresh failure — preventing a thundering-herd
 * of parallel refresh calls.
 *
 * <p><b>Why the failure branch also notifies.</b> Both outcomes must be broadcast, not just
 * success. A refresh that fails while other requests are parked behind it would otherwise leave
 * them subscribed to a subject that never emits again: no value, no error, no completion. The
 * user sees spinners that never resolve and is never redirected to log in, because nothing on
 * those streams ever reaches the error handler that would sign them out.
 *
 * @param req         - the original request that received the 401
 * @param next        - the next handler used to retry the request
 * @param userService - injected UserService used to call the refresh endpoint
 * @returns an Observable that emits the retried request's response
 */
function handleRefreshToken(req: HttpRequest<unknown>, next: HttpHandlerFn, userService: UserService): Observable<HttpEvent<unknown>> {
  if (!isTokenRefreshing) {
    // console.log('Refreshing Token...');
    isTokenRefreshing = true;
    refreshTokenSubject.next(null);
    return userService.refreshToken$().pipe(
      switchMap((response) => {
        // DEBUG ONLY — DO NOT SHIP ENABLED: prints credentials/PII to the console.
        // console.log('Token Refresh Response:', response);
        isTokenRefreshing = false;
        refreshTokenSubject.next({ settled: 'success', response });
        // DEBUG ONLY — DO NOT SHIP ENABLED: prints credentials/PII to the console.
        // console.log('New Token:', response.data!.access_token);
        // console.log('Sending original request:', req);
        return next(addAuthorizationTokenHeader(req, response.data!.access_token));
      }),
      catchError((error) => {
        isTokenRefreshing = false;
        localStorage.removeItem(Key.TOKEN);
        localStorage.removeItem(Key.REFRESH_TOKEN);
        // Release the parked requests before propagating, so they fail alongside this one
        // instead of hanging on a subject that will never emit again.
        refreshTokenSubject.next({ settled: 'failure', error });
        return throwError(() => error);
      }),
    );
  }
  // Refresh already in flight — wait for it to settle, then retry or fail with it.
  return refreshTokenSubject.pipe(
    filter((outcome): outcome is RefreshOutcome => outcome !== null),
    take(1),
    switchMap((outcome) =>
      outcome.settled === 'success'
        ? next(addAuthorizationTokenHeader(req, outcome.response.data!.access_token))
        : throwError(() => outcome.error),
    ),
  );
}
