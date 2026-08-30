import { expect, test } from '@playwright/test';

import { SEED_USERS, loginAs, readTokens, suppressPasskeyPrompt } from './support/app';

/**
 * The frontend↔backend seam — the boundary every other test suite mocks.
 *
 * <p>{@code tokenInterceptor} is unit-tested against a stubbed {@code HttpClient};
 * {@code CustomAuthFilter} is unit-tested against a synthetic request. Both pass while disagreeing
 * with each other about the header name, the scheme prefix, or which routes are exempt. These tests
 * are the only ones in the repository where the real interceptor talks to the real filter chain.
 *
 * <p>Specs here inherit the signed-in state produced by {@code auth.setup.ts}, so they cost nothing
 * against the {@code /user/login} rate-limit budget unless they explicitly sign in again.
 */

/** Authenticated, cheap, and outside {@code RateLimitFilter}'s 10/min auth tier — a good probe. */
const PROBE_ENDPOINT = '/user/profile';

test.describe('frontend ↔ backend seam', () => {
  test('the interceptor attaches the bearer token to API calls', async ({ page }) => {
    const authHeaders: string[] = [];

    page.on('request', (request) => {
      const url = new URL(request.url());
      // Only the app's own API namespace — static assets and i18n JSON are deliberately not
      // expected to carry credentials, and asserting on them would make this test noisy.
      if (url.pathname.startsWith('/user') || url.pathname.startsWith('/admin')) {
        const header = request.headers()['authorization'];
        if (header) authHeaders.push(header);
      }
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    expect(
      authHeaders.length,
      'the SPA made no authenticated API call on the landing page — the interceptor may not be wired',
    ).toBeGreaterThan(0);

    // The scheme prefix is exactly the kind of detail that drifts: CustomAuthFilter strips a
    // literal "Bearer " prefix, so a client that sent a bare token, or "bearer" lowercase, would
    // fail authentication while both halves looked correct in isolation.
    for (const header of authHeaders) {
      expect(header).toMatch(/^Bearer \S+/);
    }
  });

  test('the real filter chain rejects a malformed token', async ({ page }) => {
    // Proves the server genuinely validates rather than merely reading the header. A signature that
    // does not verify must be refused even though the header is perfectly well-formed.
    const response = await page.request.get(PROBE_ENDPOINT, {
      headers: { Authorization: 'Bearer not.a.real.token' },
    });

    expect(response.status()).toBe(401);
  });

  test('an authenticated request to the same endpoint succeeds', async ({ page }) => {
    // The control for the test above: same endpoint, same shape, valid token. Without this pairing
    // a 401 could just mean the endpoint is broken or the path is wrong.
    await page.goto('/');
    const { token } = await readTokens(page);

    const response = await page.request.get(PROBE_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
  });

  /**
   * End-to-end proof of the access-token revocation shipped on 2026-08-29.
   *
   * <p>That work added a session-family check to {@code TokenProvider#isTokenValid}:
   * {@code SessionRepo#isFamilyRevoked} tests the token's {@code sid} claim against
   * {@code refreshsessions.revoked}. Its unit tests mock the repository, so they prove the check is
   * *called* — they cannot prove that a real revoke actually flips the real row that the real check
   * then reads. Three components have to agree for the feature to work at all, and this is the only
   * test that exercises all three together.
   *
   * <p>The scenario is the honest one: a user with two active sessions uses "sign out everywhere
   * else" from session B, and session A's still-unexpired access token must stop working
   * immediately rather than lingering for the remainder of its 30-minute TTL. Before this feature
   * that token stayed valid — which is exactly the gap the backlog described.
   */
  test('revoking other sessions immediately invalidates their access tokens', async ({
    page,
    browser,
  }) => {
    // Session A — the inherited setup session.
    await page.goto('/');
    const { token: tokenA } = await readTokens(page);
    expect(tokenA).toBeTruthy();

    // Confirm A works right now, so a later 401 can only be the revocation.
    const before = await page.request.get(PROBE_ENDPOINT, {
      headers: { Authorization: `Bearer ${tokenA}` },
    });
    expect(before.status()).toBe(200);

    // Session B — a second, independent browser context for the same account. A separate context
    // (not just a second page) is what makes it a genuinely separate session: its own storage, its
    // own login, its own session family.
    // `storageState` MUST be cleared explicitly. `browser.newContext()` inherits the project's
    // `use` options, and this project sets `storageState` to the signed-in state `auth.setup.ts`
    // cached — so the default is not "a fresh context", it is "the same session again".
    //
    // That silently defeated the entire test. Context B came up already authenticated, the SPA's
    // guard bounced it straight off `/login` to the dashboard, `#email` never rendered, and the
    // fill sat there until the test timed out. Worse than the hang: had it got further, A and B
    // would have shared one token and one session family, so "revoke every family except mine"
    // would have spared A's token and the assertion would have failed while reporting that
    // revocation was broken — pointing squarely at working production code.
    //
    // `{ cookies: [], origins: [] }` is the same explicit-empty idiom `auth.spec.ts` uses.
    const contextB = await browser.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const pageB = await contextB.newPage();
    try {
      await suppressPasskeyPrompt(pageB);
      await loginAs(pageB, SEED_USERS.appAdmin.email);
      const { token: tokenB } = await readTokens(pageB);
      expect(tokenB).toBeTruthy();

      // "Sign out everywhere else" — revokes every family except B's own.
      const revoke = await pageB.request.delete('/user/sessions', {
        headers: { Authorization: `Bearer ${tokenB}` },
      });
      expect(revoke.status()).toBe(200);

      // A's token is unexpired and correctly signed, and must now be refused anyway.
      const after = await page.request.get(PROBE_ENDPOINT, {
        headers: { Authorization: `Bearer ${tokenA}` },
      });
      expect(
        after.status(),
        "session A's access token still works after being revoked — the session-family check in " +
          'TokenProvider#isTokenValid is not taking effect end-to-end',
      ).toBe(401);

      // B must survive: "everywhere else" that logs you out too is a different, broken feature.
      const bStillWorks = await pageB.request.get(PROBE_ENDPOINT, {
        headers: { Authorization: `Bearer ${tokenB}` },
      });
      expect(bStillWorks.status()).toBe(200);
    } finally {
      await contextB.close();
    }
  });
});
