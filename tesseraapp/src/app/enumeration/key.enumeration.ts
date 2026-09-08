/**
 * `localStorage` key names, centralized so the storage key string is never retyped at each call
 * site. `TOKEN`/`REFRESH_TOKEN` are read/written by `user.service.ts` and
 * `token.interceptor.ts` (attaching the access token to every request and rotating both on a
 * 401) and cleared on logout; `PASSKEY_PROMPT_DISMISSED` is read by the post-login passkey-nudge
 * UI. Testing note: `testing/local-storage.ts` provides the real backing store in specs, since
 * the Vitest test environment's own `localStorage` is an inert placeholder.
 */
export enum Key {
  TOKEN = '[KEY] TOKEN',
  REFRESH_TOKEN = '[REFRESH] REFRESH_TOKEN',
  /** Set once a user adds a passkey OR dismisses the post-login prompt, so it is never repeated. */
  PASSKEY_PROMPT_DISMISSED = '[KEY] PASSKEY_PROMPT_DISMISSED',
}
