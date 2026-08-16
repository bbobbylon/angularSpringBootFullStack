import { UserInterface } from './user.interface';

/**
 * One anomaly-flagged sign-in, as returned by {@code GET /admin/security/overview}
 * (SRS FR-TPF-2).
 *
 * Mirrors the backend {@code SuspiciousLoginEntry} record field-for-field. The nullable fields
 * are genuinely nullable and must be rendered as "unknown" rather than filtered out — a flagged
 * sign-in with no recorded device is still a flagged sign-in, and dropping it would quietly
 * shrink the very list an administrator is reading to judge how much is happening.
 */
export interface SuspiciousLoginInterface {
  /** The flagged account's id — used to deep-link to its admin detail page. */
  userId: number;
  firstName: string;
  lastName: string;
  email: string;
  /** {@code "OS - Browser - Device"} parsed from the User-Agent, or null on older rows. */
  device: string | null;
  /** The originating address recorded for the attempt, or null. */
  ipAddress: string | null;
  /**
   * Which signals fired and which step-up was applied, e.g.
   * {@code "a new device → step-up: EMAIL_CODE"}. This is what distinguishes a flagged sign-in
   * that was challenged with an authenticator from one that fell back to an emailed code.
   */
  detail: string | null;
  /** ISO timestamp of the flagged sign-in. */
  createdAt: string;
}

/**
 * An account that currently cannot sign in — locked by brute-force protection, or not enabled.
 *
 * Both states travel together because both present to the help desk as "I can't get in" while
 * needing different remedies; {@link nonLocked} and {@link enabled} are what tell them apart.
 */
export interface RestrictedAccountInterface {
  userId: number;
  firstName: string;
  lastName: string;
  email: string;
  /** False when brute-force protection has locked the account. */
  nonLocked: boolean;
  /** False when the account was never verified, or was disabled administratively. */
  enabled: boolean;
  /** ISO timestamp of the most recent failed sign-in, or null if there has never been one. */
  lastFailureAt: string | null;
}

/** One day of the login-outcome trend. Days with no activity arrive as explicit zeros. */
export interface LoginOutcomeTrendPointInterface {
  /** ISO date ({@code YYYY-MM-DD}). */
  day: string;
  successful: number;
  failed: number;
  suspicious: number;
}

/**
 * Second-factor coverage across the in-scope population.
 *
 * The three groups are mutually exclusive and sum to {@link totalUsers}, so percentages derived
 * from them are guaranteed to total 100 — the backend computes them in a single pass for exactly
 * that reason.
 */
export interface MfaAdoptionInterface {
  totalUsers: number;
  /** Accounts with a confirmed authenticator app. */
  totpUsers: number;
  /** Accounts on SMS MFA and no authenticator. */
  smsUsers: number;
  /** Accounts protected by a password alone — the population FR-TPF-1's email step-up serves. */
  singleFactorUsers: number;
  /** Server-computed share of accounts with any second factor, one decimal place. */
  mfaCoveragePercent: number;
}

/**
 * Pagination metadata for one of the dashboard's two growing tables.
 *
 * Mirrors the backend's {@code SecurityOverview.PageInfo} record. Both tables previously arrived
 * capped by a bare SQL {@code LIMIT} with no total, so the screen showed the newest N rows and said
 * nothing about the rest — on an audit surface, "three flagged sign-ins this week" and "the three
 * most recent of three hundred" demand different responses and rendered identically.
 */
export interface PageInfoInterface {
  /** 0-based index of the page held in the sibling list. */
  page: number;
  /** Rows per page, echoed by the server so the client need not assume its default. */
  size: number;
  /** Total matching rows ignoring pagination — the number that resolves the ambiguity above. */
  totalElements: number;
  /** Total pages at this size; 0 when the table is empty. */
  totalPages: number;
}

/**
 * The complete security dashboard payload (SRS FR-TPF-2).
 *
 * Delivered as one object from one request so every panel describes the same instant; see the
 * backend {@code SecurityOverview} for why six endpoints would have been six different instants.
 */
export interface SecurityOverviewInterface {
  /** How many days of history the counters and trend cover. */
  windowDays: number;
  /**
   * True when these figures are restricted to the caller's organizations (FR-ORG-2).
   *
   * The UI must surface this. A dashboard that looks identical whether it shows the whole
   * platform or one organization's slice invites its most dangerous misreading — an org admin
   * concluding all is quiet when they can only see their own corner of it.
   */
  scoped: boolean;
  /** Event type name → count over the window. Every tracked type is present, at zero if unused. */
  eventCounts: Record<string, number>;
  suspiciousLogins: SuspiciousLoginInterface[];
  /** Pagination metadata for {@link suspiciousLogins} — see {@link PageInfoInterface}. */
  suspiciousLoginsPage: PageInfoInterface;
  /** Oldest first, one entry per day, gap-filled by the server. */
  trend: LoginOutcomeTrendPointInterface[];
  restrictedAccounts: RestrictedAccountInterface[];
  /** Pagination metadata for {@link restrictedAccounts} — see {@link PageInfoInterface}. */
  restrictedAccountsPage: PageInfoInterface;
  mfaAdoption: MfaAdoptionInterface;
  /** Live refresh sessions in scope (not revoked, not superseded, not expired). */
  activeSessions: number;
  /** Distinct accounts holding at least one live session — read against {@link activeSessions}. */
  accountsWithSessions: number;
}

/** The {@code data} block of the security overview response envelope. */
export interface SecurityOverviewDataInterface {
  user: UserInterface;
  overview: SecurityOverviewInterface;
}

/**
 * The admin-tunable anomaly detection overrides (FUTURE-ENHANCEMENTS "Anomaly signal tuning UI"),
 * as returned by {@code GET}/{@code PATCH /admin/security/anomaly-settings}.
 *
 * Mirrors the backend {@code SecuritySettings} model. {@code null} on either override field means
 * "no override on record — the server is using its env-configured default", not "off" or "zero";
 * the settings panel must render that as an explicit "using default" state rather than a blank or
 * unchecked control, which would look like a deliberate override of false/0.
 */
export interface SecuritySettingsInterface {
  id: number;
  /** {@code null} = no override, server uses {@code app.security.anomaly.enabled}. */
  anomalyEnabled: boolean | null;
  /** {@code null} = no override, server uses {@code app.security.anomaly.history-limit}. */
  anomalyHistoryLimit: number | null;
  /** ISO timestamp of the last change, or null if this row has never been edited. */
  updatedAt: string | null;
  /** Id of the administrator who last changed it, or null. */
  updatedBy: number | null;
}

/** The {@code data} block of the anomaly-settings response envelope. */
export interface SecuritySettingsDataInterface {
  user: UserInterface;
  settings: SecuritySettingsInterface;
}
