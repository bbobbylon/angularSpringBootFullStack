import { expect, test as setup } from '@playwright/test';

import { STORAGE_STATE } from '../playwright.config';
import { SEED_USERS, loginAs, readTokens, suppressPasskeyPrompt } from './support/app';

/**
 * Signs in once and caches the authenticated browser state for every other spec to reuse.
 *
 * <p>Runs as a Playwright *dependency* project, so it completes before the {@code chromium} project
 * starts and its failure fails the run immediately rather than as a cascade of confusing timeouts
 * inside unrelated specs.
 *
 * <p><b>Why a shared session instead of logging in per test:</b> {@code RateLimitFilter} rations
 * {@code /user/login} to 10 requests per minute per client IP, and the entire suite shares one IP.
 * See the {@code projects} comment in {@code playwright.config.ts}.
 *
 * <p><b>Why the application admin:</b> this is the session specs inherit by default, so it needs to
 * be able to reach every screen under test. Specs asserting that a *lower* privilege level is
 * correctly refused must sign in as that user explicitly — inheriting an admin session and then
 * expecting a 403 would silently prove nothing.
 */
setup('authenticate as the seeded application admin', async ({ page }) => {
  await suppressPasskeyPrompt(page);

  await loginAs(page, SEED_USERS.appAdmin.email);

  // Assert the tokens actually landed before persisting. Without this the setup could "succeed"
  // by navigating away from /login for some unrelated reason and hand every downstream spec a
  // storage state with no credentials in it — which would fail them all, far from the real cause.
  const { token, refreshToken } = await readTokens(page);
  expect(token, 'access token should be persisted after login').toBeTruthy();
  expect(refreshToken, 'refresh token should be persisted after login').toBeTruthy();

  await expect(page).toHaveURL(/\/$/);

  await page.context().storageState({ path: STORAGE_STATE });
});
