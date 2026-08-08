import { UserInterface } from './user.interface';
import { UserEventsInterface } from './user-events.interface';
import { RolesInterface } from './roles.interface';
import { PasskeyInterface } from './security.interface';

/**
 * The data payload returned by {@code GET /admin/user/list} (SRS FR-ADMIN-1).
 *
 * {@code user} is the calling administrator — every page template feeds it to the
 * shared navbar, mirroring how the customer list responses carry the viewer. The
 * directory rows live under {@code users} with flat pagination metadata (this endpoint
 * does not use Spring's {@code Page<T>} envelope; the backend computes the totals from
 * its JDBC count query). {@code roles} is the full catalogue so the list view can
 * render role badges and the detail view's reassignment selector without extra calls.
 */
export interface AdminUserListInterface {
  user?: UserInterface;
  users?: UserInterface[];
  usersTotalElements?: number;
  usersTotalPages?: number;
  page?: number;
  pageSize?: number;
  roles?: RolesInterface[];
}

/**
 * The data payload returned by {@code GET /admin/user/:id} and both admin PATCH
 * endpoints (SRS FR-ADMIN-2/3/4).
 *
 * {@code user} is the calling administrator (navbar); {@code selectedUser} is the
 * account being managed. {@code events} is the first page of the selected user's
 * audit history — the same {@link UserEventsInterface} rows the profile page shows
 * for one's own account, here surfaced to administrators per FR-ADMIN-2. {@code passkeys}
 * is metadata only (nickname, transports, timestamps) — never the WebAuthn credential id
 * or attestation object, which the backend never returns to any client.
 */
export interface AdminUserDetailInterface {
  user?: UserInterface;
  selectedUser?: UserInterface;
  events?: UserEventsInterface[];
  eventsTotalElements?: number;
  eventsTotalPages?: number;
  roles?: RolesInterface[];
  passkeys?: PasskeyInterface[];
}
