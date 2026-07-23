import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';

/**
 * A lightweight in-memory cache for Angular {@link HttpResponse} objects.
 *
 * Stores full HTTP responses keyed by a string (typically the request URL) so
 * that repeated calls to the same endpoint can be served from memory instead of
 * hitting the network again.  This is the **cache-aside** pattern: the caller
 * checks the cache first via {@link get}, and if nothing is found it makes the
 * real HTTP call and stores the result via {@link put}.
 *
 * <h3>Where it will be used</h3>
 * This service has been introduced by the instructor but is not yet wired into
 * the rest of the app. It is intended to be injected into an HTTP interceptor
 * or a data service so that GET responses can be reused across navigation events
 * without redundant network requests.
 *
 * <h3>Why {@code HttpResponse<never>}</h3>
 * The generic parameter {@code never} signals that this cache layer is
 * body-agnostic — it stores and returns the raw response object without
 * inspecting or transforming the body.  Individual consumers are responsible
 * for casting to their own typed response when they retrieve a cached value.
 */
// TODO: Move caching to the backend (Cache-Control / ETag headers or a server-side store like Redis) and delete this service.
@Injectable({
  providedIn: 'root',
})
export class HttpCacheService {
  /**
   * Angular's HTTP client, available if a subclass or future method needs
   * to make an outbound request directly from this service.
   */
  protected readonly http = inject(HttpClient);

  /**
   * The backing store for all cached responses.
   *
   * Keys are caller-defined strings — conventionally the full request URL
   * (e.g. {@code 'http://localhost:8080/customer/list?page=0'}).
   * Values are the complete {@link HttpResponse} objects returned by Angular's
   * {@link HttpClient}, stored without modification.
   */
  private httpResponseCache: Record<string, HttpResponse<never>> = {};

  /**
   * Stores an HTTP response in the cache under the given key.
   *
   * Call this immediately after a successful HTTP request so later
   * calls to the same endpoint can be answered from memory via {@link get}.
   *
   * @param key          - the cache key, typically the full request URL
   * @param httpResponse - the complete response object returned by {@link HttpClient}
   */
  put = (key: string, httpResponse: HttpResponse<never>): void => {
    console.log('Caching our HTTP Response: ', httpResponse);
    this.httpResponseCache[key] = httpResponse;
  };

  /**
   * Retrieves a cached HTTP response by key.
   *
   * Returns {@code undefined} (a cache miss) if no entry exists for the given
   * key — the caller should then make a real HTTP request and call {@link put}
   * to populate the cache.
   *
   * @param key - the cache key used when the response was stored via {@link put}
   * @returns the cached {@link HttpResponse}, or {@code undefined} on a miss
   */
  get = (key: string): HttpResponse<never> | null | undefined => {
    console.log('Getting ' + key);
    return this.httpResponseCache[key];
  };

  /**
   * Removes a single entry from the cache.
   *
   * Call this after a mutating request (POST, PUT, DELETE) that invalidates a
   * specific cached response, so the next GET for that key fetches fresh data.
   *
   * @param key - the cache key of the entry to remove
   * @returns {@code true} if the entry was present and deleted, {@code false} otherwise
   * //evict = (key: string): boolean => delete this.httpResponseCache[key]
   */

  /**
   * Clears the entire cache, removing all stored responses.
   *
   * Use this on logout or when a bulk operation makes the entire cached
   * dataset stale and a full refresh is required.
   */
  evictAll = (): void => {
    console.log('Clearing the entire cache');
    this.httpResponseCache = {};
  };

  /**
   * Logs the full contents of the cache to the browser console.
   *
   * Debug utility only — use this during development to inspect what is
   * currently stored before deciding whether to evict or keep entries.
   */
  logCache = (): void => {
    console.log(this.httpResponseCache);
  };
}
