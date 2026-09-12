/**
 * Mirrors the backend's {@code OAuthClientDTO} (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B) —
 * every field of an OAuth2 client-credentials pair except the secret itself. {@code revoked} is a
 * Lombok primitive-boolean getter ({@code isRevoked()}), and Jackson strips the {@code is} prefix,
 * so the JSON key is {@code "revoked"} — same caveat {@code ApiKeyInterface}'s doc comment
 * documents. Unlike {@link ApiKeyInterface}, {@code clientId} is carried in full rather than as a
 * truncated prefix — it is the public half of the credential pair, not a secret.
 */
export interface OAuthClientInterface {
  id: number;
  userId: number;
  clientId: string;
  name: string;
  createdAt: Date;
  createdBy?: number;
  lastUsedAt?: Date;
  revoked: boolean;
}

/**
 * The data payload returned by every {@code /admin/serviceaccounts/:id/oauthclients/**} endpoint.
 * {@code clientId}/{@code rawClientSecret} are present <b>only</b> on the response to
 * {@code POST .../oauthclients} — the one moment the plaintext secret exists outside the
 * database — and are never sent again afterward, not even on a subsequent list fetch of the very
 * client they belong to (the list's {@code oauthClients[].clientId} is fine to show again since
 * the client_id is public, but no list response ever carries a secret).
 */
export interface OAuthClientsDataInterface {
  clientId?: string;
  rawClientSecret?: string;
  oauthClients?: OAuthClientInterface[];
}
