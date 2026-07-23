import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { UserService } from '../service/user.service';

// TODO: add guards for admin roles

/**
 * Route guard that blocks unauthenticated users from accessing protected routes.
 *
 * Checks whether the user holds a valid, non-expired JWT token via
 * {@link UserService#isAuthenticated}. If not, redirects to {@code /login}
 * instead of allowing navigation to proceed.
 *
 * Wire this up in your route config as:
 * {@code canActivate: [authenticationGuard]}
 */
export const authenticationGuard: CanActivateFn = () => {
  const userService = inject(UserService);
  const router = inject(Router);

  if (userService.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login']);
};
