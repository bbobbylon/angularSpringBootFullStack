/**
 * Represents a role record returned by the backend.
 *
 * Included in the {@code GET /user/profile} and all Authorization-tab PATCH
 * responses so the frontend can populate the role selector without a separate
 * request. The {@code permission} field is a comma-delimited string of
 * authority keys (e.g. {@code 'READ:USER,UPDATE:USER'}) that drive both the
 * Spring Security authority set and the frontend {@code hasPermission} check
 * in {@code ProfileComponent}.
 */
export interface RolesInterface {
  /** The database primary key of the role. */
  id?: number;
  /** The role name used by the backend (e.g. {@code 'ROLE_USER'}, {@code 'ROLE_ADMIN'}). */
  name?: string;
  /** Comma-delimited permission string (e.g. {@code 'READ:USER,UPDATE:USER,DELETE:USER'}). */
  permission?: string;
}
