import { expect, test } from '@playwright/test';

/**
 * {@code RateLimitFilter}'s two-tier per-IP throttle, exercised end to end.
 *
 * <p>This spec exists because of a trade made elsewhere. The suite runs every request from a single
 * client IP — inside Docker the whole host collapses to one bridge-gateway address — so a normal
 * run looks to the filter exactly like one client issuing a sustained burst, which is precisely the
 * shape it exists to reject. It duly rejected it, and unrelated tests started failing on bare 429s.
 * The fix was to raise the ceilings for the E2E stack alone (see {@code .env.e2e.example}).
 *
 * <p>Raising a security control's threshold for the tests that are supposed to verify it would
 * normally be how coverage quietly disappears. So the limits were made configurable rather than
 * conditional, and this spec drives the auth tier past the raised ceiling on purpose. The control is
 * verified at the value the E2E stack actually runs with, which is strictly more coverage than
 * existed before — previously nothing asserted that a 429 was ever returned at all.
 *
 * <p><b>This spec runs last, and that is enforced.</b> It is the only one that deliberately drains
 * a resource every other test shares — the filter buckets per client IP, and inside Docker the whole
 * host is one IP, so exhausting the auth tier exhausts it suite-wide. `playwright.config.ts` gives
 * this file its own project depending on `chromium` for exactly that reason.
 *
 * <p>It was originally left in the main project, where path order happened to place it between
 * {@code auth.spec.ts} and {@code seam.spec.ts} — and it broke seam: a second session could not sign
 * in, and since the login form maps every non-200 to the same generic message (the no-enumeration
 * property {@code auth.spec.ts} asserts), the 429 was indistinguishable from a wrong password and
 * the test just waited out its timeout.
 */

/** An auth-tier path per {@code RateLimitFilter.AUTH_PATHS}, and the cheapest one to abuse. */
const AUTH_ENDPOINT = '/user/login';

/**
 * Must exceed {@code RATE_LIMIT_AUTH_CAPACITY} (120 in the E2E stack) with headroom for the
 * greedy refill trickling tokens back in mid-burst, but stay bounded so a misconfigured limiter
 * fails fast with a clear message instead of looping.
 */
const MAX_ATTEMPTS = 400;

/**
 * Deliberately not a seeded account. Every attempt here is a failed sign-in that writes an audit
 * event, and pointing several hundred of them at a real demo account would pollute that account's
 * login history — which {@code LoginRiskServiceImpl} reads — for every later run.
 */
const NOBODY = {
  email: 'rate-limit-probe@example.invalid',
  password: 'not-a-real-password',
};

test.describe('rate limiting', () => {
  test.describe.configure({ mode: 'serial' });

  test('a sustained burst against an auth endpoint is throttled with 429 and Retry-After', async ({
    request,
  }) => {
    let throttled: Awaited<ReturnType<typeof request.post>> | undefined;
    let attempts = 0;

    // Sequential, not parallel. Concurrent requests race each other through
    // `tryConsumeAndReturnRemaining`, so the attempt number that first sees a 429 becomes
    // non-deterministic and the failure message stops meaning anything.
    while (attempts < MAX_ATTEMPTS && !throttled) {
      attempts += 1;
      const response = await request.post(AUTH_ENDPOINT, { data: NOBODY });
      if (response.status() === 429) throttled = response;
    }

    expect(
      throttled,
      `sent ${attempts} sign-in attempts from one IP without ever being throttled — ` +
        'RateLimitFilter is not enforcing the auth tier',
    ).toBeDefined();

    // Retry-After is the part a well-behaved client actually consumes. A 429 without it tells a
    // caller to back off but not for how long, which in practice means it retries immediately.
    const retryAfter = throttled!.headers()['retry-after'];
    expect(retryAfter, '429 responses must carry Retry-After').toBeDefined();
    expect(Number(retryAfter)).toBeGreaterThan(0);
    expect(Number(retryAfter)).toBeLessThanOrEqual(61);

    // The rejection still comes back as the standard HttpResponse envelope. The filter sits at
    // @Order(-200), outside Spring Security and outside @RestControllerAdvice, so it has to build
    // that envelope by hand — which is exactly the kind of thing that drifts from the shape every
    // controller returns.
    const body = await throttled!.json();
    expect(body.statusCode).toBe(429);
    expect(body.status).toBe('TOO_MANY_REQUESTS');
    expect(body.path).toBe(AUTH_ENDPOINT);
    expect(String(body.message)).toMatch(/rate limit/i);
  });

  test('throttling the auth tier leaves the rest of the app served', async ({ request }) => {
    // The previous test has just exhausted the auth bucket for this IP. Auth paths and everything
    // else use separate ConcurrentHashMaps with separate capacities, so a brute-force attempt
    // against /user/login must not take the application offline for the same client — if these
    // shared one bucket, an attacker could deny service to themselves and, far worse, to every
    // other user behind the same NAT or proxy.
    const response = await request.get('/');

    expect(
      response.status(),
      'exhausting the auth-tier bucket also blocked non-auth traffic — the two tiers are sharing a bucket',
    ).toBe(200);
  });
});
