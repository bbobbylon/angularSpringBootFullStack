import { expect, test } from '@playwright/test';

import {
  DEMO_PASSWORD,
  SEED_USERS,
  loginAs,
  loginErrorText,
  readTokens,
  suppressPasskeyPrompt,
  verifySeedContract,
} from './support/app';

/**
 * End-to-end coverage of the sign-in path — the one flow where the SPA, the servlet filter chain,
 * Spring Security, and the database all have to agree, and therefore the flow where a seam break
 * is both most likely and most damaging.
 *
 * <p>Every test in this file runs *unauthenticated*, opting out of the shared signed-in state the
 * other specs inherit. Each one spends a request from the 10-per-minute {@code /user/login} budget
 * (see {@code playwright.config.ts}), which is why this file stays deliberately small: it covers
 * the properties that can only be proven end-to-end, and leaves everything else to the 584 backend
 * and 174 frontend unit tests that can assert it far more cheaply.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test.describe('sign-in', () => {
  test('the login page renders its credential form', async ({ page }) => {
    // Deliberately first and deliberately trivial: if the stack came up wrong — wrong profile, no
    // static bundle, database unreachable — this fails with an obvious message before any richer
    // test fails for a reason that looks like a product bug.
    await verifySeedContract(page);
  });

  test('a seeded account signs in and receives both tokens', async ({ page }) => {
    await suppressPasskeyPrompt(page);
    await loginAs(page, SEED_USERS.appAdmin.email);

    await expect(page).toHaveURL(/\/$/);

    // The tokens are the actual proof. Landing on "/" only shows the router moved; these show the
    // full round trip completed — controller minted a JWT, the response envelope was shaped the way
    // the SPA expects, and UserService persisted both halves.
    const { token, refreshToken } = await readTokens(page);
    expect(token).toBeTruthy();
    expect(refreshToken).toBeTruthy();

    // A JWT, not an opaque string — three dot-separated base64url segments.
    expect(token).toMatch(/^[\w-]+\.[\w-]+\.[\w-]+$/);
  });

  /**
   * The user-enumeration guarantee, proven through the real UI.
   *
   * <p>This is the single most valuable test in the suite. The property — "a failed sign-in must
   * never reveal whether the account exists" — is a standing project rule, and it already has a
   * backend guard ({@code UserControllerLoginEnumerationTest}). What no existing test covers is
   * whether the *rendered* message is identical, and the rendered message is the one an attacker
   * actually sees. A backend that returns identical strings can still leak through the SPA if the
   * two cases take different error branches and render different copy.
   *
   * <p>Note it asserts the two messages equal *each other*, not equal some hardcoded literal. That
   * keeps the test correct when the wording is changed or translated — it is pinning the security
   * property, not the copy, and a test that pinned the copy would have to be edited (and could be
   * edited wrongly) every time the text moved.
   */
  test('an unknown email and a wrong password fail identically', async ({ page }) => {
    await suppressPasskeyPrompt(page);

    await page.goto('/login');
    const credentials = page.locator('form').filter({ has: page.locator('#email') });

    // Case 1: the account exists, the password does not match.
    await credentials.locator('#email').fill(SEED_USERS.guest.email);
    await credentials.locator('#password').fill('definitely-not-the-right-password');
    await credentials.locator('button[type="submit"]').click();
    const wrongPasswordMessage = await loginErrorText(page);

    // Case 2: no such account at all.
    await page.goto('/login');
    const retry = page.locator('form').filter({ has: page.locator('#email') });
    await retry.locator('#email').fill(`nobody.here${SEED_USERS.guest.email.slice(SEED_USERS.guest.email.indexOf('@'))}`);
    await retry.locator('#password').fill(DEMO_PASSWORD);
    await retry.locator('button[type="submit"]').click();
    const unknownEmailMessage = await loginErrorText(page);

    expect(
      unknownEmailMessage,
      'a failed sign-in must not reveal whether the account exists — the two messages differ, ' +
        'which lets an attacker enumerate valid accounts',
    ).toBe(wrongPasswordMessage);

    // And neither may leak the address back to the caller, which would be the same disclosure by
    // a different route ("no account for alice@…" is an oracle even if both branches say it).
    expect(wrongPasswordMessage.toLowerCase()).not.toContain(SEED_USERS.guest.email.toLowerCase());

    // Nothing is issued on a failed attempt.
    const { token, refreshToken } = await readTokens(page);
    expect(token).toBeNull();
    expect(refreshToken).toBeNull();
  });

  test('a protected route redirects an anonymous visitor to login', async ({ page }) => {
    // The authenticationGuard is client-side, so this proves the guard is wired — the server-side
    // half of the same property is covered by the API-level assertions in seam.spec.ts.
    await page.goto('/customers');
    await expect(page).toHaveURL(/\/login/);
  });

  /**
   * Covers the passkey interstitial on a context that has *not* had it suppressed.
   *
   * <p>Worth a dedicated test precisely because every other spec hides it: without this, the branch
   * that the majority of the suite deliberately routes around would have no coverage at all, and a
   * regression in it would be invisible.
   */
  test('a first-time browser is offered the passkey prompt after signing in', async ({ page }) => {
    await loginAs(page, SEED_USERS.moderator.email);

    await expect(page).toHaveURL(/\/welcome-passkey/);
  });
});
