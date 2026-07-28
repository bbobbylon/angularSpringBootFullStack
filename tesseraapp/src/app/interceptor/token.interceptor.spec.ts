import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { __resetTokenRefreshStateForTests, tokenInterceptor } from './token.interceptor';
import { UserService } from '../service/user.service';
import { Key } from '../enumeration/key.enumeration';
import { ProfileInterface } from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { installMemoryLocalStorage, restoreLocalStorage } from '../testing/local-storage';

/**
 * Specs for {@link tokenInterceptor} — the silent refresh-on-401 path.
 *
 * <p>This is the piece of client code with the widest blast radius: it sits in front of every
 * authenticated request in the application, and it owns the decision of whether a 401 means
 * "retry with a fresh token" or "this session is over". Getting that wrong does not produce a
 * localized bug — it either signs the whole application out mid-task or, worse, leaves it hanging
 * with no error to surface, which is the harder failure to notice because nothing looks broken
 * except that nothing finishes.
 *
 * <p>The interceptor is exercised directly rather than through {@code HttpClient} and
 * {@code HttpTestingController}. Its contract is expressed entirely in terms of the two arguments
 * it receives — what it hands to {@code next}, and what it does with the stream that comes back —
 * so calling it with a stub handler tests exactly that, without the testing backend's own
 * request-matching semantics sitting in between. {@link TestBed#runInInjectionContext} is what
 * makes the interceptor's {@code inject(UserService)} resolve against the providers below.
 *
 * <p>The stub {@code next} models a server rather than a fixed script: requests bearing the stale
 * token are rejected with 401, requests bearing the rotated one succeed. Tests therefore read as
 * statements about token state instead of about call ordering, and a retry that reuses the old
 * token fails the way the real backend would.
 *
 * <p>Note the module-level refresh state the interceptor keeps (deliberately — one refresh must be
 * shared across concurrent 401s). It outlives {@code TestBed}, so it is reset between specs; see
 * {@link __resetTokenRefreshStateForTests}.
 */
describe('tokenInterceptor', () => {
  const STALE_TOKEN = 'stale.access.token';
  const FRESH_TOKEN = 'fresh.access.token';
  const STALE_REFRESH = 'stale.refresh.token';

  /** Test double for the service that owns the refresh call. */
  let userService: { refreshToken$: ReturnType<typeof vi.fn> };
  /** Records every request handed downstream, in order, including retries. */
  let handled: HttpRequest<unknown>[];
  /** Swappable behaviour for the stub handler; reassigned per scenario. */
  let server: (request: HttpRequest<unknown>) => Observable<HttpEvent<unknown>>;

  /** The stub {@code HttpHandlerFn}: records the request, then defers to {@link server}. */
  const next: HttpHandlerFn = (request) => {
    handled.push(request);
    return server(request);
  };

  /** A 200 with a trivial body; the interceptor never inspects successful payloads. */
  const ok = (): Observable<HttpEvent<unknown>> => of(new HttpResponse({ status: 200, body: { ok: true } }));

  /** The rejection the backend produces for an expired or unknown bearer token. */
  const unauthorized = (): Observable<HttpEvent<unknown>> =>
    throwError(() => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }));

  /**
   * A server that rejects exactly the given token and accepts anything else — the shape of a
   * backend that has rotated its view of the session out from under the client.
   */
  const rejecting =
    (rejectedToken: string) =>
    (request: HttpRequest<unknown>): Observable<HttpEvent<unknown>> =>
      request.headers.get('Authorization') === `Bearer ${rejectedToken}` ? unauthorized() : ok();

  /** Builds the envelope {@code refreshToken$} resolves to, carrying a rotated token pair. */
  const refreshEnvelope = (accessToken: string): CustomHttpResponseInterface<ProfileInterface> =>
    ({
      data: { access_token: accessToken, refresh_token: 'fresh.refresh.token' },
    }) as CustomHttpResponseInterface<ProfileInterface>;

  /** Runs the interceptor for a GET against the given URL, inside an injection context. */
  const intercept = (url: string): Observable<HttpEvent<unknown>> =>
    TestBed.runInInjectionContext(() => tokenInterceptor(new HttpRequest('GET', url), next));

  /** The Authorization header on the nth request that reached the handler. */
  const authOn = (index: number): string | null => handled[index].headers.get('Authorization');

  /**
   * Subscribes and captures the outcome synchronously.
   *
   * <p>Every observable in these specs is synchronous ({@code of}/{@code throwError}, or a
   * {@link Subject} driven by the test), so an outcome still {@code pending} after the test has
   * emitted everything it intends to is a genuine hang rather than a timing artefact — which is
   * precisely the condition one of the specs below asserts against.
   */
  const collect = (stream: Observable<HttpEvent<unknown>>) => {
    const outcome: { state: 'pending' | 'next' | 'error'; value?: HttpEvent<unknown>; error?: unknown } = {
      state: 'pending',
    };
    stream.subscribe({
      next: (value) => {
        outcome.state = 'next';
        outcome.value = value;
      },
      error: (error: unknown) => {
        outcome.state = 'error';
        outcome.error = error;
      },
    });
    return outcome;
  };

  beforeEach(() => {
    __resetTokenRefreshStateForTests();
    // The test environment ships an inert localStorage placeholder; see the helper's docs.
    installMemoryLocalStorage();
    localStorage.setItem(Key.TOKEN, STALE_TOKEN);
    localStorage.setItem(Key.REFRESH_TOKEN, STALE_REFRESH);

    handled = [];
    server = ok;
    userService = { refreshToken$: vi.fn() };

    TestBed.configureTestingModule({
      providers: [{ provide: UserService, useValue: userService }],
    });
  });

  afterEach(() => {
    restoreLocalStorage();
  });

  describe('attaching the token', () => {
    it('sends the stored access token on a protected request', () => {
      collect(intercept('/user/profile'));

      expect(handled).toHaveLength(1);
      expect(authOn(0)).toBe(`Bearer ${STALE_TOKEN}`);
    });

    it('leaves public routes completely untouched', () => {
      // Not merely "no token" — the same request object, unmodified. The login POST must not
      // carry a stale Authorization header from a previous session, which the backend would
      // reject before ever reading the credentials in the body.
      for (const url of ['/user/login', '/user/register', '/user/verify/totp', '/user/resetpassword/a@b.test', '/user/refresh/token']) {
        handled = [];
        collect(intercept(url));

        expect(handled[0].headers.has('Authorization'), `${url} should carry no token`).toBe(false);
      }
    });

    it('does not treat a protected request as public because a query value says "login"', () => {
      // The public-route test is against path segments, not the raw URL. Under a substring match
      // this search goes out unauthenticated, 401s, and — being on the pass-through branch —
      // never even attempts a refresh, so the user sees an inexplicable failure for one search
      // term and no other.
      collect(intercept('/customer/search?name=login'));

      expect(authOn(0)).toBe(`Bearer ${STALE_TOKEN}`);
      expect(userService.refreshToken$).not.toHaveBeenCalled();
    });

    it('still recognises a public route behind an absolute API base URL', () => {
      // environment.apiUrl is absolute in some builds; segment matching must survive the scheme
      // and host rather than only working for relative paths.
      collect(intercept('http://localhost:8080/user/login'));

      expect(handled[0].headers.has('Authorization')).toBe(false);
    });
  });

  describe('errors that are not 401', () => {
    it('propagates a 500 without attempting a refresh', () => {
      server = () => throwError(() => new HttpErrorResponse({ status: 500 }));

      const outcome = collect(intercept('/user/profile'));

      expect(outcome.state).toBe('error');
      expect(userService.refreshToken$).not.toHaveBeenCalled();
    });

    it('propagates a 403 without attempting a refresh', () => {
      // 403 means the token is valid and the authority is missing. Refreshing would mint an
      // identical set of authorities and retry into the same wall, turning one clear denial into
      // two requests and a rotated token pair for nothing.
      server = () => throwError(() => new HttpErrorResponse({ status: 403 }));

      const outcome = collect(intercept('/customer/list'));

      expect(outcome.state).toBe('error');
      expect(userService.refreshToken$).not.toHaveBeenCalled();
      expect(localStorage.getItem(Key.TOKEN)).toBe(STALE_TOKEN);
    });
  });

  describe('refresh on 401', () => {
    it('refreshes once and retries the original request with the new token', () => {
      server = rejecting(STALE_TOKEN);
      userService.refreshToken$.mockReturnValue(of(refreshEnvelope(FRESH_TOKEN)));

      const outcome = collect(intercept('/user/profile'));

      expect(userService.refreshToken$).toHaveBeenCalledTimes(1);
      expect(handled).toHaveLength(2);
      expect(authOn(1)).toBe(`Bearer ${FRESH_TOKEN}`);
      expect(outcome.state).toBe('next');
    });

    it('replays the original method, URL and body on the retry', () => {
      // The retry is a clone with one header swapped. If it were rebuilt instead, a PATCH would
      // silently lose its payload and the user's edit would vanish on a token boundary.
      server = rejecting(STALE_TOKEN);
      userService.refreshToken$.mockReturnValue(of(refreshEnvelope(FRESH_TOKEN)));
      const body = { firstName: 'Ada' };

      TestBed.runInInjectionContext(() =>
        tokenInterceptor(new HttpRequest('PATCH', '/user/update', body), next),
      ).subscribe({ error: () => undefined });

      expect(handled[1].method).toBe('PATCH');
      expect(handled[1].url).toBe('/user/update');
      expect(handled[1].body).toBe(body);
    });

    it('clears both tokens and surfaces the error when the refresh itself fails', () => {
      server = rejecting(STALE_TOKEN);
      const refreshFailure = new HttpErrorResponse({ status: 401 });
      userService.refreshToken$.mockReturnValue(throwError(() => refreshFailure));

      const outcome = collect(intercept('/user/profile'));

      expect(outcome.state).toBe('error');
      expect(outcome.error).toBe(refreshFailure);
      // Both must go. Leaving the refresh token behind would let the next 401 start another
      // doomed refresh cycle, and leaving the access token behind keeps isAuthenticated() true
      // so the guard never redirects to /login.
      expect(localStorage.getItem(Key.TOKEN)).toBeNull();
      expect(localStorage.getItem(Key.REFRESH_TOKEN)).toBeNull();
      expect(handled).toHaveLength(1);
    });

    it('treats a refresh response with no token payload as a failed refresh', () => {
      // A 200 whose envelope carries no data is the shape a misconfigured gateway returns. The
      // interceptor must not retry with the string "Bearer undefined" and call that a session.
      server = rejecting(STALE_TOKEN);
      userService.refreshToken$.mockReturnValue(of({} as CustomHttpResponseInterface<ProfileInterface>));

      const outcome = collect(intercept('/user/profile'));

      expect(outcome.state).toBe('error');
      expect(localStorage.getItem(Key.TOKEN)).toBeNull();
      expect(handled).toHaveLength(1);
    });

    it('starts a fresh refresh cycle after an earlier one failed', () => {
      // The in-flight flag is module-level. If a failure left it stuck true, every subsequent 401
      // for the lifetime of the tab would park behind a refresh that already finished — the user
      // signs back in and the application still cannot complete a request.
      server = rejecting(STALE_TOKEN);
      userService.refreshToken$.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 401 })));
      collect(intercept('/user/profile'));

      localStorage.setItem(Key.TOKEN, STALE_TOKEN);
      userService.refreshToken$.mockReturnValueOnce(of(refreshEnvelope(FRESH_TOKEN)));
      const outcome = collect(intercept('/customer/list'));

      expect(userService.refreshToken$).toHaveBeenCalledTimes(2);
      expect(outcome.state).toBe('next');
    });
  });

  describe('concurrent 401s', () => {
    /** Drives the in-flight refresh by hand so a second request can arrive mid-flight. */
    let refresh: Subject<CustomHttpResponseInterface<ProfileInterface>>;

    beforeEach(() => {
      refresh = new Subject<CustomHttpResponseInterface<ProfileInterface>>();
      server = rejecting(STALE_TOKEN);
      userService.refreshToken$.mockReturnValue(refresh);
    });

    it('issues one refresh for many simultaneous 401s and retries them all', () => {
      const first = collect(intercept('/user/profile'));
      const second = collect(intercept('/customer/list'));
      const third = collect(intercept('/user/events?page=0'));

      // All three have 401'd; none has resolved, because the single refresh is still in flight.
      expect(handled).toHaveLength(3);
      expect(userService.refreshToken$).toHaveBeenCalledTimes(1);
      expect([first, second, third].map((outcome) => outcome.state)).toEqual(['pending', 'pending', 'pending']);

      refresh.next(refreshEnvelope(FRESH_TOKEN));

      expect([first, second, third].map((outcome) => outcome.state)).toEqual(['next', 'next', 'next']);
      // Retries only — the three originals plus one retry each.
      expect(handled).toHaveLength(6);
      expect(handled.slice(3).map((request) => request.headers.get('Authorization'))).toEqual([
        `Bearer ${FRESH_TOKEN}`,
        `Bearer ${FRESH_TOKEN}`,
        `Bearer ${FRESH_TOKEN}`,
      ]);
      // One refresh, not three: a thundering herd here rotates the refresh-token family
      // repeatedly and the losing rotations invalidate the session outright.
      expect(userService.refreshToken$).toHaveBeenCalledTimes(1);
    });

    it('fails the parked requests too when the shared refresh fails', () => {
      // The regression this file exists for. The parked requests wait on a subject that the
      // failure branch previously never emitted on: no value, no error, no completion. Their
      // spinners run forever, no error handler ever fires, and the user is never signed out —
      // the application simply stops finishing work with nothing on screen to explain why.
      const first = collect(intercept('/user/profile'));
      const parked = collect(intercept('/customer/list'));

      expect(userService.refreshToken$).toHaveBeenCalledTimes(1);
      expect(parked.state).toBe('pending');

      const failure = new HttpErrorResponse({ status: 401 });
      refresh.error(failure);

      expect(first.state).toBe('error');
      expect(parked.state).toBe('error');
      expect(parked.error).toBe(failure);
      // No retry was attempted with a token that was never issued.
      expect(handled).toHaveLength(2);
    });

    it('leaves the shared channel usable after a failed refresh', () => {
      // Modelling failure as a value rather than subject.error() matters here: erroring the
      // shared BehaviorSubject would kill it permanently, so the first failed refresh of the
      // session would leave every later refresh with a dead notification channel.
      collect(intercept('/user/profile'));
      collect(intercept('/customer/list'));
      refresh.error(new HttpErrorResponse({ status: 401 }));

      localStorage.setItem(Key.TOKEN, STALE_TOKEN);
      const laterRefresh = new Subject<CustomHttpResponseInterface<ProfileInterface>>();
      userService.refreshToken$.mockReturnValue(laterRefresh);

      const leader = collect(intercept('/user/profile'));
      const follower = collect(intercept('/customer/list'));
      laterRefresh.next(refreshEnvelope(FRESH_TOKEN));

      expect(leader.state).toBe('next');
      expect(follower.state).toBe('next');
    });

    it('does not hand a parked request the token from a previous refresh', () => {
      // The shared channel is a BehaviorSubject, so it retains the last value it saw. A request
      // that parks during a *new* refresh must not be released immediately with the token from
      // the last one — that token is exactly the one the server has already rejected.
      const firstRefresh = new Subject<CustomHttpResponseInterface<ProfileInterface>>();
      userService.refreshToken$.mockReturnValue(firstRefresh);
      collect(intercept('/user/profile'));
      firstRefresh.next(refreshEnvelope('first.rotation'));

      server = rejecting('first.rotation');
      localStorage.setItem(Key.TOKEN, 'first.rotation');
      const secondRefresh = new Subject<CustomHttpResponseInterface<ProfileInterface>>();
      userService.refreshToken$.mockReturnValue(secondRefresh);

      collect(intercept('/user/profile'));
      const parked = collect(intercept('/customer/list'));

      expect(parked.state).toBe('pending');

      secondRefresh.next(refreshEnvelope('second.rotation'));

      expect(parked.state).toBe('next');
      expect(handled[handled.length - 1].headers.get('Authorization')).toBe('Bearer second.rotation');
    });
  });
});
