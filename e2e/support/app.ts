import { expect, type Page } from '@playwright/test';

/**
 * Shared vocabulary for the end-to-end suite: the seeded accounts, the browser-storage keys the
 * SPA actually uses, and the handful of navigation helpers every spec needs.
 *
 * <p>Everything here is a deliberate mirror of a value that lives in the application, and each
 * mirror is annotated with its source of truth. A mirror that drifts is worse than no mirror, so
 * {@link verifySeedContract} exists to fail loudly and early rather than let a renamed storage key
 * or a changed demo password surface as a dozen confusing timeouts.
 */

/**
 * The password every seeded account shares — mirrors {@code DemoDataSeeder.DEMO_PASSWORD}.
 *
 * <p>Not a secret in any meaningful sense: {@code DemoDataSeeder} is {@code @Profile("dev")}, so
 * these accounts only ever exist on a developer machine or in this throwaway E2E stack.
 */
export const DEMO_PASSWORD = 'TesseraDemo@1';

/**
 * The domain every seeded account belongs to — mirrors {@code Constants.DEMO_EMAIL_DOMAIN}.
 *
 * <p>Load-bearing beyond mere addressing: {@code EmailServiceImpl#isSuppressedRecipient} drops any
 * outbound mail to this domain at the single dispatch choke point. That is what makes the suite
 * safe to run repeatedly (no inbox is ever touched) and simultaneously what makes email-dependent
 * flows — step-up codes, password-reset links — untestable end-to-end without a mail catcher.
 */
export const DEMO_EMAIL_DOMAIN = '@tessera.dev';

/**
 * The six role-representative accounts {@code DemoDataSeeder} inserts on a dev-profile boot.
 *
 * <p>Ordered least- to most-privileged, which is also the order the seeder writes them. Specs
 * should pick the *least* privileged account that can exercise what they are testing: a test that
 * signs in as the application admin proves nothing about whether the authority rules work.
 */
export const SEED_USERS = {
  guest: { email: `alice.guest${DEMO_EMAIL_DOMAIN}`, role: 'ROLE_GUEST' },
  moderator: { email: `bob.mod${DEMO_EMAIL_DOMAIN}`, role: 'ROLE_MODERATOR' },
  helpDesk: { email: `carol.help${DEMO_EMAIL_DOMAIN}`, role: 'ROLE_HELP_DESK_ADMIN' },
  orgAdmin: { email: `dave.org${DEMO_EMAIL_DOMAIN}`, role: 'ROLE_ORGANIZATION_ADMIN' },
  admin: { email: `eve.admin${DEMO_EMAIL_DOMAIN}`, role: 'ROLE_ADMIN' },
  appAdmin: { email: `frank.app${DEMO_EMAIL_DOMAIN}`, role: 'ROLE_APPLICATION_ADMIN' },
} as const;

/**
 * Browser-storage keys — mirrors the {@code Key} enum in
 * {@code tesseraapp/src/app/enumeration/key.enumeration.ts}.
 *
 * <p>The bracketed-prefix format is unusual enough that it is worth stating plainly: these are the
 * literal string values, not names. Copying the enum *member* name here would produce keys that
 * never match anything and tests that fail for a reason unrelated to the code under test.
 */
export const STORAGE_KEYS = {
  token: '[KEY] TOKEN',
  refreshToken: '[REFRESH] REFRESH_TOKEN',
  passkeyPromptDismissed: '[KEY] PASSKEY_PROMPT_DISMISSED',
} as const;

/**
 * Suppresses the one-time post-login "Add a passkey?" interstitial for this browser context.
 *
 * <p><b>Why this is needed at all.</b> {@code LoginComponent#navigateHome} routes to
 * {@code /welcome-passkey} instead of {@code /} whenever {@code shouldPromptForPasskey} returns
 * true, and that helper returns true when the browser supports WebAuthn and the account has no
 * passkey and the dismissal flag is unset. Headless Chromium *does* expose
 * {@code window.PublicKeyCredential}, and every seeded account is password-only, so a fresh
 * Playwright context hits all three conditions — meaning the natural-looking assertion "after
 * login the URL is /" fails on correct application behaviour.
 *
 * <p>Setting the same flag the real "Maybe later" button sets ({@code dismissPasskeyPrompt}) puts
 * the browser in the state of a returning user, which is the state the vast majority of specs
 * actually mean to be in. The interstitial itself is covered explicitly, on a deliberately
 * un-suppressed context, in {@code auth.spec.ts}.
 *
 * <p>Runs via {@code addInitScript} so it lands before the Angular bundle evaluates on every
 * navigation in the context — setting it after {@code goto} would be too late for the very first
 * login. The try/catch guards the case where the script evaluates on a document that has no
 * accessible storage yet (e.g. {@code about:blank}), where touching {@code localStorage} throws.
 */
export async function suppressPasskeyPrompt(page: Page): Promise<void> {
  await page.addInitScript(
    ([key, value]) => {
      try {
        window.localStorage.setItem(key, value);
      } catch {
        /* no accessible storage on this document — nothing to suppress */
      }
    },
    [STORAGE_KEYS.passkeyPromptDismissed, '1'] as const,
  );
}

/**
 * Fills and submits the credentials form, then waits for the SPA to leave {@code /login}.
 *
 * <p>Scopes the submit to the form that owns {@code #email}. The login page renders three separate
 * forms (credentials, organization-SSO discovery, and the federated/passkey buttons), so an
 * unscoped {@code button[type=submit]} lookup is ambiguous — and ambiguity here resolves
 * differently depending on which optional blocks are rendered, which would make the suite's
 * behaviour depend on whether OAuth providers happen to be configured.
 *
 * <p>Waits on the URL rather than on any particular landing page, because the destination is
 * legitimately conditional (see {@link suppressPasskeyPrompt}). Callers assert the destination
 * they actually care about.
 */
export async function loginAs(page: Page, email: string, password = DEMO_PASSWORD): Promise<void> {
  await page.goto('/login');

  const credentials = page.locator('form').filter({ has: page.locator('#email') });
  await credentials.locator('#email').fill(email);
  await credentials.locator('#password').fill(password);
  await credentials.locator('button[type="submit"]').click();

  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30_000 });
}

/** Reads the persisted access + refresh tokens the way the SPA's own services would. */
export async function readTokens(
  page: Page,
): Promise<{ token: string | null; refreshToken: string | null }> {
  return page.evaluate(
    ([tokenKey, refreshKey]) => ({
      token: window.localStorage.getItem(tokenKey),
      refreshToken: window.localStorage.getItem(refreshKey),
    }),
    [STORAGE_KEYS.token, STORAGE_KEYS.refreshToken] as const,
  );
}

/**
 * Reads the text of the login form's error alert.
 *
 * <p>Trimmed and whitespace-collapsed because the message is rendered across a multi-line template
 * with an icon sibling — the raw {@code textContent} carries indentation that would defeat the
 * exact-equality comparison the user-enumeration test depends on.
 */
export async function loginErrorText(page: Page): Promise<string> {
  const alert = page.locator('.alert.sc-alert').first();
  await expect(alert).toBeVisible({ timeout: 20_000 });
  return (await alert.innerText()).replace(/\s+/g, ' ').trim();
}

/**
 * Asserts the assumptions this whole suite rests on, so a drifted mirror fails once with a clear
 * message instead of many times with opaque timeouts.
 *
 * <p>Called from the first spec rather than from global setup deliberately: a failure here should
 * read as a failing test with a diff, not as an infrastructure crash before any test runs.
 */
export async function verifySeedContract(page: Page): Promise<void> {
  await page.goto('/login');
  await expect(page.locator('#email')).toBeVisible();
  await expect(page.locator('#password')).toBeVisible();
}
