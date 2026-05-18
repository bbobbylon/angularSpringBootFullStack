import { HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { HttpCacheService } from '../service/http-cache.service';

/**
 * cacheInterceptor — Angular functional HTTP interceptor for GET response caching.
 *
 * Sits in the HTTP pipeline between the application and the server. For every
 * outgoing {@link HttpRequest}, this interceptor decides whether to:
 *   1. Bypass the cache entirely (auth routes and mutating requests).
 *   2. Return a previously cached {@link HttpResponse} immediately from memory, or
 *   3. Forward the request to the server and store the response for next time.
 *
 * The cache itself is managed by {@link HttpCacheService}, which holds responses
 * in a {@code Record<string, HttpResponse>} keyed by request URL.
 *
 * <h3>Pipeline position</h3>
 * Registered before {@code tokenInterceptor} in {@code app.config.ts} via
 * {@code provideHttpClient(withInterceptors([cacheInterceptor, tokenInterceptor]))}.
 * A cache hit returns an Observable immediately without ever calling {@code next()},
 * so {@code tokenInterceptor} is not invoked and no Authorization header is attached
 * to a request that never leaves the browser.
 *
 * <h3>What is and is not cached</h3>
 * Only GET requests to non-auth, non-download endpoints are cached.
 * Any mutating request (POST, PUT, DELETE, PATCH) evicts the entire cache, so the
 * next GET always fetches fresh data from the server.
 *
 * @param req  - the outgoing HTTP request
 * @param next - the next handler in the pipeline; calling it forwards the request
 * @returns an Observable of the HTTP event stream — either a cached response or
 *          a live server response
 */
// TODO: Move caching to the backend (Cache-Control / ETag headers or a server-side store like Redis) and delete this interceptor.
export const cacheInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
  const httpCache = inject(HttpCacheService);

  /**
   * Auth and session routes that must bypass the cache entirely.
   *
   * These endpoints either do not return cacheable data (login returns a token,
   * not a resource) or are used during flows where stale data would be actively
   * harmful (password reset, account verification). They pass straight through
   * to the next interceptor without any cache interaction.
   */
  const bypassRoutes = ['verify', 'login', 'register', 'refresh', 'resetpassword', 'new/password'];
  if (bypassRoutes.some((route) => req.url.includes(route))) {
    return next(req);
  }

  /**
   * Any non-GET request or file download evicts the entire cache and passes through.
   *
   * A POST, PUT, or DELETE means the server's data has changed, so any cached GET
   * response for any URL is now potentially stale. Evicting everything is the safest
   * strategy — the trade-off is an extra network round-trip on the next GET, but it
   * guarantees the UI always shows the latest state after a mutation.
   *
   * Downloads are excluded from caching even if they are GET requests because binary
   * response bodies are large and would inflate memory usage for no practical benefit.
   */
  if (req.method !== 'GET' || req.url.includes('download')) {
    httpCache.evictAll();
    return next(req);
  }

  /**
   * Cache hit: return the stored response immediately as a synchronously
   * completing Observable via {@code of()}.
   *
   * {@code of(value)} wraps a plain value in an Observable that emits once and
   * completes — the caller receives it exactly as if it came from the server.
   * Because we return here without calling {@code next()}, the request never
   * reaches the server or any downstream interceptors.
   */
  const cachedResponse = httpCache.get(req.url) as HttpResponse<unknown> | undefined;
  if (cachedResponse) {
    console.log('Found a Response in the Cache', cachedResponse);
    httpCache.logCache();
    return of(cachedResponse);
  }

  /**
   * Cache miss: forward the request to the server and store the response.
   *
   * Delegates to {@link storeCacheResponse}, which uses the RxJS {@code tap}
   * operator to store the response as a side effect without altering the
   * Observable stream the caller receives.
   */
  return storeCacheResponse(req, next, httpCache);
};

/**
 * Forwards a cache-miss GET request to the server and stores the response in
 * {@link HttpCacheService} so later identical requests can be served from
 * memory.
 *
 * Uses {@code tap} rather than {@code map} because the goal is a side effect
 * (storing the response) not a transformation — the response is passed through
 * to the caller unchanged. {@code tap} does not alter the stream; it simply
 * "peeks" at each emission and runs the provided callback.
 *
 * The {@code response instanceof HttpResponse} guard is necessary because the
 * HTTP pipeline emits multiple event types ({@code HttpSentEvent},
 * {@code HttpHeaderResponse}, {@code HttpResponse}, etc.). We only want to cache
 * the final complete response, not intermediate progress events.
 *
 * <h3>The {@code req.method !== 'DELETE'} guard</h3>
 * A defensive safety check. In practice, DELETE requests are already intercepted
 * and returned early by the non-GET gate in {@link cacheInterceptor}, so a DELETE
 * can never reach this function. The guard is kept here so that if the routing
 * logic above ever changes, DELETE responses can never accidentally be cached.
 *
 * @param req       - the original GET request that missed the cache
 * @param next      - the next handler in the pipeline; forwarding the request here
 *                    sends it through {@code tokenInterceptor} and on to the server
 * @param httpCache - the injected cache service used to store the response
 * @returns an Observable of the HTTP event stream, with caching as a side effect
 */
function storeCacheResponse(req: HttpRequest<unknown>, next: HttpHandlerFn, httpCache: HttpCacheService): Observable<HttpEvent<unknown>> {
  return next(req).pipe(
    tap((response) => {
      if (response instanceof HttpResponse && req.method !== 'DELETE') {
        console.log('Caching the Response', response);
        httpCache.put(req.url, response as HttpResponse<never>);
      }
    }),
  );
}
