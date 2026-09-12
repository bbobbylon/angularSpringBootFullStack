/**
 * Mirrors the backend's {@code ApiKeyDTO} (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A) — every
 * field of an API key except the secret itself. {@code revoked} is a Lombok primitive-boolean
 * getter ({@code isRevoked()}), and Jackson strips the {@code is} prefix, so the JSON key is
 * {@code "revoked"} — see {@code UserInterface}'s doc comment for the same caveat on
 * {@code notLocked}.
 */
export interface ApiKeyInterface {
  id: number;
  userId: number;
  name: string;
  keyPrefix: string;
  createdAt: Date;
  createdBy?: number;
  lastUsedAt?: Date;
  expiresAt?: Date;
  revoked: boolean;
}

/**
 * The data payload returned by every {@code /admin/serviceaccounts/:id/apikeys/**} endpoint.
 * {@code rawKey} is present <b>only</b> on the response to {@code POST .../apikeys} — the one
 * moment the plaintext key exists outside the database — and is never sent again afterward, not
 * even on a subsequent list fetch of the very key it belongs to.
 */
export interface ApiKeysDataInterface {
  rawKey?: string;
  apiKeys?: ApiKeyInterface[];
}
