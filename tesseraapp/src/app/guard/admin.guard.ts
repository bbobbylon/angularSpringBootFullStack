import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { UserService } from '../service/user.service';
import { NotificationsService } from '../service/notifications-service';
import { TranslocoService } from '@jsverse/transloco';

/**
 * Builds the localized "you don't have permission to X" sentence for a blocked route.
 *
 * <p>Shared by {@link adminGuard} and {@code capabilityGuard} so the two guards cannot drift into
 * saying the same thing two different ways — a user who is stopped at one and then the other must
 * read one consistent message.
 *
 * <h3>Two ways a route can name its capability</h3>
 * {@code data.deniedActionKey} is a translation key ({@code 'permissions.actions.manageUsers'}) and
 * is preferred. {@code data.deniedAction} is a plain English phrase and remains supported as a
 * fallback, so a route that has not been migrated still produces a correct English message rather
 * than rendering a raw key at the user. That fallback is deliberate: a half-migrated route table is
 * the normal state during an incremental translation, and the failure mode has to be "this one
 * sentence is still English", not "this sentence is now `permissions.actions.undefined`".
 *
 * <p>Transloco returns the key itself when a translation is missing, so the result is compared
 * against the key to detect that case.
 *
 * @param route - the activated route snapshot carrying the capability declaration
 * @param transloco - the translation service
 * @returns the complete, localized message
 */
export function deniedMessageFor(route: ActivatedRouteSnapshot, transloco: TranslocoService): string {
  const key = route.data?.['deniedActionKey'] as string | undefined;
  const literal = route.data?.['deniedAction'] as string | undefined;

  let action: string;
  if (key) {
    const translated = transloco.translate(key);
    action = translated === key ? (literal ?? transloco.translate('permissions.actions.generic')) : translated;
  } else {
    action = literal ?? transloco.translate('permissions.actions.generic');
  }

  return transloco.translate('permissions.denied', { action });
}

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
  const transloco = inject(TranslocoService);

  if (!userService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }
  if (userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')) {
    return true;
  }

  // Authenticated but under-privileged: name the blocked capability so the user knows
  // what to ask their administrator for, then bounce home. The message is localized
  // (ROADMAP §2 — i18n) and falls back to a generic phrase if a route declares nothing.
  notifications.onWarning(deniedMessageFor(route, transloco));
  return router.createUrlTree(['/']);
};
