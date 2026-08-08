export enum Key {
  TOKEN = '[KEY] TOKEN',
  REFRESH_TOKEN = '[REFRESH] REFRESH_TOKEN',
  /** Set once a user adds a passkey OR dismisses the post-login prompt, so it is never repeated. */
  PASSKEY_PROMPT_DISMISSED = '[KEY] PASSKEY_PROMPT_DISMISSED',
}
