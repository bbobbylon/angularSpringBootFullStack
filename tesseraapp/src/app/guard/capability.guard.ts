import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { UserService } from '../service/user.service';
import { NotificationsService } from '../service/notifications-service';

/**
 * Route guard for pages that require a specific capability rather than staff status
 * (ROADMAP §2 — capability-level RBAC gating).
 *
 * <h3>Why this exists alongside {@link adminGuard}</h3>
 * `adminGuard` answers one fixed question — "is this a staff-grade account?" — which is exactly
 * right for {@code /users}, {@code /roles}, {@code /billing}, and {@code /analytics}, where the
 * whole page is administrative. It is the wrong question for {@code /customer/new}: creating a
 * customer needs {@code UPDATE:CUSTOMER}, an authority {@code ROLE_MODERATOR} holds without being
 * staff at all. Gating that route with `adminGuard` would lock out a role the system deliberately
 * created; leaving it ungated (the state before this guard) means a read-only account that types
 * the URL gets a complete form and discovers the truth only when the submit returns 403.
 *
 * <p>So this guard takes the required authorities from the route instead of hard-coding them:
 * ```ts
 * {
 *   path: 'customer/new',
 *   canActivate: [authenticationGuard, capabilityGuard],
 *   data: { requiredAuthorities: ['UPDATE:CUSTOMER', 'UPDATE:USER'], deniedAction: 'create customers' },
 *   ...
 * }
 * ```
 * The list is an OR, matching the backend's {@code hasAnyAuthority(...)} — the route should
 * demand exactly what the endpoint behind it demands and nothing stricter, or the UI starts
 * refusing requests the server would have allowed.
 *
 * <h3>Behaviour on denial</h3>
 * Identical to `adminGuard` by design: a named, non-enumerating toast ("You don't have permission
 * to <em>create customers</em> — contact your administrator.") followed by a bounce home. Users
 * should not be able to tell which of the two guards stopped them; a difference in wording or
 * destination would be a difference they would have to learn for no benefit. The message names a
 * capability only — never a record, an account, or a count — so it cannot become an enumeration
 * channel.
 *
 * <h3>Fail-closed on a missing declaration</h3>
 * A route that wires up this guard but forgets {@code requiredAuthorities} is denied rather than
 * allowed. The alternative — treating "nothing declared" as "no restriction" — would turn a typo
 * into a silently unguarded route, and the whole point of a guard is that its failure mode is
 * visible. A route that genuinely needs no capability should simply not list this guard.
 *
 * <p>NFR-SEC-4: like every client-side check here, this is a usability aid. The authorities come
 * from a token the user controls; the backend re-derives them from the database on every request.
 */
export const capabilityGuard: CanActivateFn = (route) => {
  const userService = inject(UserService);
  const router = inject(Router);
  const notifications = inject(NotificationsService);

  if (!userService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }

  const required = (route.data?.['requiredAuthorities'] as string[] | undefined) ?? [];
  if (required.length > 0 && userService.hasAnyAuthority(...required)) {
    return true;
  }

  const action = (route.data?.['deniedAction'] as string | undefined) ?? 'access this area';
  notifications.onWarning(`You don't have permission to ${action} — contact your administrator.`);
  return router.createUrlTree(['/']);
};
