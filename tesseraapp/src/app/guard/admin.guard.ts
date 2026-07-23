import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { UserService } from '../service/user.service';

/**
 * Route guard for the administrative Users dashboard (SRS FR-ADMIN-5).
 *
 * Allows navigation only when the access token carries a staff-grade authority
 * ({@code UPDATE:USER} or {@code UPDATE:ROLE}) — the same authorities SecurityConfig
 * demands for {@code /admin/**} on the backend. Unauthenticated users go to
 * {@code /login}; authenticated users without the authority are bounced to the home
 * page rather than shown a broken view full of 403s.
 *
 * Per NFR-SEC-4 this guard is a usability aid, not a security boundary: the backend
 * independently enforces the same authorities at the URL and method level, so bypassing
 * this guard only changes what renders, never what data the API will return.
 *
 * Wire up as: {@code canActivate: [authenticationGuard, adminGuard]}.
 */
export const adminGuard: CanActivateFn = () => {
  const userService = inject(UserService);
  const router = inject(Router);

  if (!userService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }
  if (userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')) {
    return true;
  }
  return router.createUrlTree(['/']);
};
