import { UserInterface } from './user.interface';

/**
 * Interfaces for the Account Security Center (plan.md M4/M5): authenticator-app
 * enrollment and the sessions & devices panel. Field names mirror the JSON keys the
 * backend's {@code TotpController} and {@code SessionController} serialize — a mismatch
 * means silent {@code undefined}s in Angular, so treat the backend as the contract.
 */

/** Payload of {@code POST /user/totp/setup}: everything the enrollment wizard renders. */
export interface TotpSetupInterface {
  /** Base32 secret for manual entry into the authenticator app. */
  secret: string;
  /** The otpauth:// provisioning URI (encoded inside the QR code). */
  otpauthUri: string;
  /** Server-rendered QR as a data:image/png;base64 URI — drop straight into <img src>. */
  qrCode: string;
}

/** Payload of {@code POST /user/totp/enable}: the one-time recovery code reveal. */
export interface TotpEnableInterface {
  user: UserInterface;
  /** Plaintext recovery codes, shown exactly once — the backend stores only hashes. */
  recoveryCodes: string[];
}

/** Payload of {@code GET /user/totp/status}. */
export interface TotpStatusInterface {
  enabled: boolean;
  recoveryCodesRemaining: number;
}

/**
 * One live refresh session (one device/browser login) from {@code GET /user/sessions}.
 * {@code family} is the stable session identity across token rotations and the handle
 * used to revoke it.
 */
export interface SessionInterface {
  family: string;
  /** Parsed "OS - Browser - Device" string captured at login/refresh. */
  device: string;
  ipAddress: string;
  createdAt: Date;
  /** Stamped on every token rotation — effectively "last seen". */
  lastUsedAt: Date;
  expiresAt: Date;
}

/** Payload of the sessions list/revoke endpoints. */
export interface SessionsDataInterface {
  sessions: SessionInterface[];
  /**
   * The family of the session THIS request rode on (decoded from the access token's
   * {@code sid} claim server-side) so the UI can badge the current row and explain
   * why it survives "log out everywhere else".
   */
  currentFamily: string;
}

/**
 * One identity provider connected to the signed-in account (ROADMAP §1.4).
 *
 * Mirrors the backend {@code FederatedIdentityService.ProviderLink}. Deliberately carries no
 * provider subject: that identifier is the durable key the find-or-create lookup matches on, the
 * UI has no use for it, and so it never leaves the database.
 */
export interface ProviderLinkInterface {
  /** Registration id — {@code 'google'}, {@code 'github'}, or {@code 'microsoft'}. */
  provider: string;
  /** ISO timestamp of when the connection was made. */
  linkedAt: string;
}

/** The {@code data} block of the connected-accounts responses. */
export interface ProviderLinksDataInterface {
  providers: ProviderLinkInterface[];
}

/**
 * One registered passkey (WebAuthn credential), as returned by {@code GET /user/webauthn/list}
 * and bundled into the admin user-detail response. Deliberately carries no WebAuthn credential id
 * or public key — {@code id} is this row's own database primary key, the only handle the frontend
 * ever needs to revoke it.
 */
export interface PasskeyInterface {
  id: number;
  /** User-supplied nickname at registration, e.g. "MacBook Touch ID". */
  deviceName?: string;
  /** Comma-joined authenticator transports ('internal' | 'hybrid' | 'usb' | 'nfc' | 'ble'). */
  transports?: string;
  createdAt: Date;
  /** Null until the passkey has been used to sign in at least once. */
  lastUsedAt?: Date;
}

/** The {@code data} block of the passkey list/register/revoke endpoints. */
export interface PasskeysDataInterface {
  passkeys: PasskeyInterface[];
}
