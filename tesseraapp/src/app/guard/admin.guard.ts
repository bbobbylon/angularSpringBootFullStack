import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { UserService } from '../service/user.service';
import { NotificationsService } from '../service/notifications-service';

/**
 * Route guard for the administrative Users dashboard (SRS FR-ADMIN-5).
 *
 * Allows navigation only when the access token carries a staff-grade authority
 * ({@code UPDATE:USER} or {@code UPDATE:ROLE}) — the same authorities SecurityConfig
 * demands for {@code /admin/**} on the backend. Unauthenticated users go to
 * {@code /login}; authenticated users without the authority are bounced to the home
 * page rather than shown a broken view full of 403s.
 *
 * <h3>Permission-denied UX (ROADMAP §2)</h3>
 * A silent redirect leaves the user guessing why the page vanished. Before bouncing,
 * the guard raises a specific, non-enumerating toast via {@link NotificationsService}
 * — e.g. "You don't have permission to <em>manage users</em> — contact your
 * administrator." The blocked capability is read from the route's
 * {@code data.deniedAction} string (see {@code app.routes.ts}), so this single guard
 * produces a route-appropriate message across {@code /users}, {@code /roles},
 * {@code /billing}, and {@code /analytics} without per-route duplication. The action
 * text names a <em>capability</em>, never whether any specific record exists, keeping
 * it in line with the app-wide user-enumeration rule.
 *
 * Per NFR-SEC-4 this guard is a usability aid, not a security boundary: the backend
 * independently enforces the same authorities at the URL and method level, so bypassing
 * this guard only changes what renders, never what data the API will return.
 *
 * Wire up as: {@code canActivate: [authenticationGuard, adminGuard]} and, per route,
 * {@code data: { deniedAction: 'manage users' }}.
 */
export const adminGuard: CanActivateFn = (route) => {
  const userService = inject(UserService);
  const router = inject(Router);
  const notifications = inject(NotificationsService);

  if (!userService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }
  if (userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')) {
    return true;
  }

  // Authenticated but under-privileged: name the blocked capability so the user knows
  // what to ask their administrator for, then bounce home. Falls back to a generic
  // phrase if a route forgot to declare its deniedAction.
  const action = (route.data?.['deniedAction'] as string | undefined) ?? 'access this area';
  notifications.onWarning(`You don't have permission to ${action} — contact your administrator.`);
  return router.createUrlTree(['/']);
};
