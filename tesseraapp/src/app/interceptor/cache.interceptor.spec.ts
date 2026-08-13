import { HttpEvent, HttpEventType, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { cacheInterceptor } from './cache.interceptor';
import { HttpCacheService } from '../service/http-cache.service';

/**
 * Specs for {@link cacheInterceptor} — the client-side GET response cache.
 *
 * <p>The invalidation rule is the one thing worth getting wrong here: any non-GET request must
 * evict <em>everything</em>, not just the URL it targets, because the cache has no way to know
 * which cached GETs a given mutation actually affected. A narrower eviction would be the more
 * "correct-looking" implementation and the more dangerous one — it would silently serve one
 * user's page a customer or invoice list from before another user's write.
 *
 * <p>Exercised directly rather than through {@code HttpClient}/{@code HttpTestingController}, the
 * same rationale {@code token.interceptor.spec.ts} documents: the interceptor's contract is fully
 * expressed by what it hands to {@code next} and what it does with the stream that comes back, so
 * a stub handler tests exactly that. {@link TestBed#runInInjectionContext} is what makes the
 * interceptor's {@code inject(HttpCacheService)} resolve against the mock below.
 */
describe('cacheInterceptor', () => {
  /** Test double for the cache the interceptor reads and writes. */
  let cache: { get: ReturnType<typeof vi.fn>; put: ReturnType<typeof vi.fn>; evictAll: ReturnType<typeof vi.fn> };
  /** Records every request handed downstream, in order. */
  let handled: HttpRequest<unknown>[];
  /** Swappable behaviour for the stub handler; a plain 200 unless a test overrides it. */
  let server: (request: HttpRequest<unknown>) => Observable<HttpEvent<unknown>>;

  /** The stub {@code HttpHandlerFn}: records the request, then defers to {@link server}. */
  const next: HttpHandlerFn = (request) => {
    handled.push(request);
    return server(request);
  };

  const ok = (): Observable<HttpEvent<unknown>> => of(new HttpResponse({ status: 200, body: { ok: true } }));

  /** Runs the interceptor for the given request, inside an injection context. */
  const intercept = (request: HttpRequest<unknown>): Observable<HttpEvent<unknown>> =>
    TestBed.runInInjectionContext(() => cacheInterceptor(request, next));

  /** Subscribes and returns whatever the stream emitted (these specs' observables are synchronous). */
  const collect = (stream: Observable<HttpEvent<unknown>>): HttpEvent<unknown> | undefined => {
    let emitted: HttpEvent<unknown> | undefined;
    stream.subscribe((value) => (emitted = value));
    return emitted;
  };

  beforeEach(() => {
    handled = [];
    server = ok;
    cache = { get: vi.fn().mockReturnValue(undefined), put: vi.fn(), evictAll: vi.fn() };

    TestBed.configureTestingModule({
      providers: [{ provide: HttpCacheService, useValue: cache }],
    });
  });

  describe('bypass routes', () => {
    it.each(['verify', 'login', 'register', 'refresh', 'resetpassword', 'new/password'])(
      'passes a request whose URL contains "%s" straight through with no cache interaction at all',
      (segment) => {
        collect(intercept(new HttpRequest('GET', `/user/${segment}`)));

        expect(handled).toHaveLength(1);
        expect(cache.get).not.toHaveBeenCalled();
        expect(cache.put).not.toHaveBeenCalled();
        expect(cache.evictAll).not.toHaveBeenCalled();
      },
    );

    it('bypasses even when the same URL has a cached entry — auth flows must never see stale data', () => {
      // If a cache hit were checked first, a stale password-reset response could be replayed
      // instead of hitting the server, which is exactly the "actively harmful" case the
      // interceptor's own docs call out.
      cache.get.mockReturnValue(new HttpResponse({ status: 200 }));

      collect(intercept(new HttpRequest('GET', '/user/verify/account/some-key')));

      expect(handled).toHaveLength(1);
      expect(cache.get).not.toHaveBeenCalled();
    });
  });

  describe('mutating requests', () => {
    it.each(['POST', 'PUT', 'PATCH', 'DELETE'] as const)(
      '%s evicts the entire cache rather than just the request URL',
      (method) => {
        collect(intercept(new HttpRequest(method, '/customer/update', {})));

        expect(cache.evictAll).toHaveBeenCalledTimes(1);
        expect(handled).toHaveLength(1);
      },
    );

    it('never checks for or stores a cache entry on a mutation', () => {
      collect(intercept(new HttpRequest('POST', '/customer/create', {})));

      expect(cache.get).not.toHaveBeenCalled();
      expect(cache.put).not.toHaveBeenCalled();
    });

    it('treats a GET download URL as a mutation for caching purposes — evict and pass through', () => {
      // Binary bodies are excluded from the cache even though the verb is GET; the interceptor
      // recognises this by URL shape, not by method, so the eviction gate has to check both.
      collect(intercept(new HttpRequest('GET', '/customer/download/report')));

      expect(cache.evictAll).toHaveBeenCalledTimes(1);
      expect(cache.get).not.toHaveBeenCalled();
    });
  });

  describe('cache hit', () => {
    it('returns the cached response without ever calling next()', () => {
      const cached = new HttpResponse({ status: 200, body: { cached: true } });
      cache.get.mockReturnValue(cached);

      const result = collect(intercept(new HttpRequest('GET', '/customer/list?page=0')));

      expect(result).toBe(cached);
      expect(handled).toHaveLength(0);
    });
  });

  describe('cache miss', () => {
    it('forwards the request and stores the final HttpResponse under the request URL', () => {
      const response = new HttpResponse({ status: 200, body: { id: 1 } });
      server = () => of(response);

      const result = collect(intercept(new HttpRequest('GET', '/customer/get/1')));

      expect(result).toBe(response);
      expect(cache.put).toHaveBeenCalledExactlyOnceWith('/customer/get/1', response);
    });

    it('does not cache intermediate pipeline events that are not the final HttpResponse', () => {
      // The HTTP pipeline emits HttpSentEvent/HttpHeaderResponse/etc. before the final response.
      // Caching one of those would later be "replayed" as if it were a real response body.
      const sentEvent = { type: HttpEventType.Sent } as HttpEvent<unknown>;
      server = () => of(sentEvent);

      collect(intercept(new HttpRequest('GET', '/customer/get/1')));

      expect(cache.put).not.toHaveBeenCalled();
    });
  });
});
