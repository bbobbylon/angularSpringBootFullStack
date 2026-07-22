import { EventType } from '../enumeration/event-type.enum';

/**
 * Display metadata for a single audit event type.
 *
 * Consumed by every surface that renders {@link UserEventsInterface} rows
 * (profile activity log, Security Center history panel, admin user-detail view)
 * so icon, label, and badge colour are defined in one place.
 */
export interface EventDisplay {
  /** Short human-readable label shown in the badge. */
  label: string;
  /** Bootstrap Icons class name (e.g. {@code 'bi-check-circle-fill'}). */
  icon: string;
  /** Bootstrap background utility class for the badge (e.g. {@code 'bg-success'}). */
  badgeClass: string;
}

/**
 * Maps an {@link EventType} constant (or raw string from the API) to the
 * {@link EventDisplay} metadata rendered in all activity log tables.
 *
 * Components expose this as a {@code protected readonly} member so Angular
 * templates can call it directly without a Pipe declaration:
 * {@code getEventDisplay(event.type).label}
 *
 * @param type - an EventType enum value or its string representation
 * @returns the matching display metadata, or a generic fallback for unknown types
 */
export function getEventDisplay(type: EventType | string): EventDisplay {
  switch (type as EventType) {
    case EventType.LOGIN_ATTEMPT:
      return { label: 'Login Attempt', icon: 'bi-box-arrow-in-right', badgeClass: 'bg-warning text-dark' };
    case EventType.LOGIN_ATTEMPT_SUCCESS:
      return { label: 'Login Successful', icon: 'bi-check-circle-fill', badgeClass: 'bg-success' };
    case EventType.LOGIN_ATTEMPT_FAILURE:
      return { label: 'Login Failed', icon: 'bi-x-circle-fill', badgeClass: 'bg-danger' };
    case EventType.FEDERATED_LOGIN:
      return { label: 'Federated Login', icon: 'bi-box-arrow-in-right', badgeClass: 'bg-success' };
    case EventType.PROFILE_UPDATE:
      return { label: 'Profile Updated', icon: 'bi-person-fill-gear', badgeClass: 'bg-primary' };
    case EventType.PROFILE_PICTURE_UPDATE:
      return { label: 'Photo Updated', icon: 'bi-camera-fill', badgeClass: 'bg-primary' };
    case EventType.PASSWORD_UPDATE:
      return { label: 'Password Changed', icon: 'bi-key-fill', badgeClass: 'bg-success' };
    case EventType.ROLE_UPDATE:
      return { label: 'Role Changed', icon: 'bi-shield-fill-check', badgeClass: 'bg-info text-dark' };
    case EventType.ACCOUNT_SETTINGS_UPDATE:
      return { label: 'Settings Changed', icon: 'bi-gear-fill', badgeClass: 'bg-warning text-dark' };
    case EventType.MFA_UPDATE:
      return { label: 'MFA Changed', icon: 'bi-phone-fill', badgeClass: 'bg-info text-dark' };
    case EventType.TOTP_ENROLLED:
      return { label: 'Authenticator Added', icon: 'bi-qr-code', badgeClass: 'bg-info text-dark' };
    case EventType.TOTP_DISABLED:
      return { label: 'Authenticator Removed', icon: 'bi-qr-code', badgeClass: 'bg-warning text-dark' };
    case EventType.RECOVERY_CODE_USED:
      return { label: 'Recovery Code Used', icon: 'bi-life-preserver', badgeClass: 'bg-warning text-dark' };
    case EventType.SESSION_REVOKED:
      return { label: 'Session Revoked', icon: 'bi-box-arrow-right', badgeClass: 'bg-warning text-dark' };
    case EventType.TOKEN_REUSE_DETECTED:
      return { label: 'Token Reuse Detected', icon: 'bi-exclamation-triangle-fill', badgeClass: 'bg-danger' };
    default:
      return { label: String(type), icon: 'bi-activity', badgeClass: 'bg-secondary' };
  }
}
