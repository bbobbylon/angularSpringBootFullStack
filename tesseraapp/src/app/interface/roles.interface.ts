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
  /**
   * Present and {@code false} only for a catalog-only role {@code RoleType} does not (yet)
   * recognize (backend's {@code @JsonInclude(NON_DEFAULT)} means {@code true} and "field
   * absent" are indistinguishable — treat both as assignable). UI hint only: the backend's
   * {@code RoleType.canAssign} already fails closed on an unrecognized name regardless of
   * what this flag says.
   */
  assignable?: boolean;
  /**
   * ISO-8601 timestamp this role assignment auto-reverts to {@code ROLE_USER} at, or absent
   * for an unlimited assignment. Only ever populated on a role attached to one specific user
   * (e.g. {@code selectedUser}'s role in the admin detail view) — never on the catalog list.
   */
  expiresAt?: string;
}

/**
 * The data payload returned by the Role CRUD catalog endpoints
 * ({@code POST/PATCH/DELETE /admin/role[/id]}). {@code role} is the created/updated row;
 * absent on delete, which only returns the refreshed {@code roles} catalog.
 */
export interface RoleCatalogInterface {
  role?: RolesInterface;
  roles?: RolesInterface[];
}
