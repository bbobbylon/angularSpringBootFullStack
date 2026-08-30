import { expect, test } from '@playwright/test';

import { readTokens } from './support/app';

/**
 * Security and caching response headers, asserted against the running stack.
 *
 * <p>These are set by servlet filters and Spring Security's header writers, which means they exist
 * only when the whole chain is assembled in the right order. A unit test can assert that
 * {@code SecurityConfig} *declares* a CSP; only a real request can show it survived filter ordering
 * and actually reached the browser. Header regressions are also unusually quiet — nothing breaks
 * visibly, the protection is simply gone.
 */

/** Authenticated, JSON-returning, and inside the filter's API namespace — a valid ETag target. */
const API_ENDPOINT = '/user/profile';

test.describe('response headers', () => {
  test('the SPA shell carries the full security header set', async ({ page }) => {
    const response = await page.request.get('/');
    expect(response.status()).toBe(200);

    const headers = response.headers();

    // Spelled out individually rather than as one blob so a failure names the missing directive.
    const csp = headers['content-security-policy'];
    expect(csp, 'no Content-Security-Policy on the SPA shell').toBeTruthy();
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).toContain("form-action 'self'");

    // script-src must stay hash-pinned. A regression to 'unsafe-inline' would still render a
    // working app while removing most of the XSS protection the policy exists for — the single
    // most consequential silent weakening available here, so it gets its own assertion.
    expect(csp).toContain("script-src 'self' 'sha256-");
    expect(csp, "script-src must not fall back to 'unsafe-inline'").not.toMatch(
      /script-src[^;]*'unsafe-inline'/,
    );

    expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
    expect(headers['x-content-type-options']).toBe('nosniff');
  });

  /**
   * The replacement for the removed client-side {@code cacheInterceptor}.
   *
   * <p>That interceptor cached GET responses in memory keyed by URL with no freshness check, so it
   * could not tell when another user's write had made its copy stale. The responsibility moved to
   * this filter: {@code Cache-Control: private, no-cache} plus an ETag means the browser's own HTTP
   * cache always revalidates with the server before reusing a response.
   *
   * <p>{@code no-cache} is widely misread as "do not store". It means "do not reuse without
   * revalidating" — which, paired with the ETag, is exactly the property the old interceptor
   * lacked, and is why this pair has to be asserted together rather than separately.
   */
  test('API GETs are revalidated rather than blindly cached', async ({ page }) => {
    await page.goto('/');
    const { token } = await readTokens(page);

    const response = await page.request.get(API_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.status()).toBe(200);

    const headers = response.headers();

    // `private` keeps a shared cache (proxy, CDN) from ever storing one caller's authenticated
    // response and serving it to another — the worst-case failure for this endpoint.
    expect(headers['cache-control']).toBe('private, no-cache');
    expect(headers['etag'], 'no ETag — no-cache without a validator forces a full refetch').toBeTruthy();
  });

  test('a matching ETag produces a 304 instead of a second body', async ({ page }) => {
    await page.goto('/');
    const { token } = await readTokens(page);

    const first = await page.request.get(API_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const etag = first.headers()['etag'];
    expect(etag).toBeTruthy();

    // The round trip the ETag exists for: same resource, unchanged, so the server should confirm
    // freshness without resending the payload. This is what makes `no-cache` cheap rather than
    // equivalent to no caching at all.
    const second = await page.request.get(API_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}`, 'If-None-Match': etag },
    });

    expect(second.status()).toBe(304);
  });

  test('auth endpoints are excluded from ETag handling', async ({ page }) => {
    // The filter deliberately bypasses anything matching "login"/"verify"/"refresh"/etc. Codes are
    // single-use, so a 304 could let a client believe a spent code is still current. Asserting the
    // exclusion holds keeps a future change to the bypass list from silently re-including them.
    const response = await page.request.post('/user/login', {
      data: { email: 'nobody@example.invalid', password: 'wrong' },
      failOnStatusCode: false,
    });

    expect(response.headers()['etag']).toBeUndefined();
  });
});
