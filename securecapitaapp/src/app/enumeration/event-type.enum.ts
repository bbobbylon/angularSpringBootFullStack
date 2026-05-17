// Mirrors the backend EventType Java enum. All values must be kept even if not
// currently referenced in the UI — the backend can send any of these in event history responses.
export enum EventType {
  LOGIN_ATTEMPT = 'LOGIN_ATTEMPT',
  LOGIN_ATTEMPT_FAILURE = 'LOGIN_ATTEMPT_FAILURE',
  LOGIN_ATTEMPT_SUCCESS = 'LOGIN_ATTEMPT_SUCCESS',
  PROFILE_UPDATE = 'PROFILE_UPDATE',
  PROFILE_PICTURE_UPDATE = 'PROFILE_PICTURE_UPDATE',
  ROLE_UPDATE = 'ROLE_UPDATE',
  ACCOUNT_SETTINGS_UPDATE = 'ACCOUNT_SETTINGS_UPDATE',
  PASSWORD_UPDATE = 'PASSWORD_UPDATE',
  MFA_UPDATE = 'MFA_UPDATE',
}
