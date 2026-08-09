/**
 * Shape of the user object returned by the backend's {@code UserDTO}.
 *
 * Field names must exactly match the JSON keys Jackson serialises — a mismatch
 * means the field will always be {@code undefined} in Angular.  One non-obvious
 * case: Lombok generates {@code isNotLocked()} for a {@code boolean isNotLocked}
 * field, and Jackson strips the {@code is} prefix from boolean getters, so the
 * JSON key is {@code "notLocked"} (not {@code "isNotLocked"}).
 */
export interface UserInterface {
  id: number;
  username: string;
  email: string;
  phoneNumber: string;
  firstName?: string;
  lastName?: string;
  address?: string;
  title?: string;
  bio?: string;
  imageUrl?: string;
  enabled: boolean;
  notLocked: boolean;
  using2FA: boolean;
  /**
   * True when a confirmed authenticator-app (TOTP) second factor is active
   * (SRS FR-MFA-4). The login screen branches on this BEFORE {@code using2FA}:
   * TOTP supersedes the SMS code path when both are enabled, matching the
   * backend's precedence in {@code UserController#login}.
   */
  usingTotp: boolean;
  /**
   * True when at least one passkey (WebAuthn credential) is registered. Informational only —
   * unlike {@code usingTotp}, the login screen never branches on this, because passkey sign-in
   * is usernameless/discoverable.
   */
  usingPasskey: boolean;
  createdAt: Date;
  roleName: string;
  permissions: string;
  /**
   * Raw stamped value: {@code null} for a password-registered account, or
   * {@code "FEDERATED_<PROVIDER>"} for one created via federated sign-in. Prefer
   * {@link userType} for display — this is the underlying fact it derives from.
   */
  origin?: string;
  /**
   * Admin-facing user-type badge (P2-1): {@code 'INTERNAL' | 'EXTERNAL' | 'FEDERATED'}. Only
   * populated by the admin endpoints (`/admin/user/**`) — absent elsewhere, so callers outside
   * the admin surface should not assume it is set.
   */
  userType?: string;
}
