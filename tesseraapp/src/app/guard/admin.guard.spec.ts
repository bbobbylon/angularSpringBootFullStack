import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { adminGuard } from './admin.guard';
import { UserService } from '../service/user.service';
import { NotificationsService } from '../service/notifications-service';
import { TranslocoService } from '@jsverse/transloco';
import { translocoStub } from '../testing/transloco-stub';

/**
 * Specs for {@link adminGuard} — the route guard behind /users, /roles, /billing and
 * /analytics (SRS FR-ADMIN-5).
 *
 * <p>The guard is a usability aid rather than a boundary (NFR-SEC-4 — the backend re-checks
 * every authority on {@code /admin/**}), but a regression here would either strand a legitimate
 * admin or silently swallow the "contact your administrator" feedback added in ROADMAP §2.
 *
 * <p>The denial message is localized (ROADMAP §2 — i18n): the guard resolves the capability
 * phrase through Transloco, preferring a route's {@code deniedActionKey} and falling back to its
 * plain-English {@code deniedAction}. Both paths are covered below, because the fallback is what
 * keeps a half-migrated route table from showing users raw translation keys.
 *
 * <p>The guard is a functional {@code CanActivateFn}, so it is invoked through
 * {@link TestBed#runInInjectionContext} — that is what makes its {@code inject()} calls
 * resolve against the providers configured below. The real {@link Router} is provided (with
 * an empty route table) rather than a stub, because the guard's contract is expressed in
 * {@link UrlTree}s and only the real router can build and serialize them faithfully.
 */
describe('adminGuard', () => {
  /** Test double for the token-inspecting service the guard consults. */
  let userService: { isAuthenticated: ReturnType<typeof vi.fn>; hasAnyAuthority: ReturnType<typeof vi.fn> };
  /** Test double capturing the toast the guard raises on denial. */
  let notifications: { onWarning: ReturnType<typeof vi.fn> };
  /** Translation double — see {@link translocoStub}. */
  let transloco: { translate: ReturnType<typeof vi.fn> };
  let router: Router;

  /**
   * Builds the {@code ActivatedRouteSnapshot} the router would hand the guard.
   *
   * Only {@code data} is read by the guard, so the rest of the (very large) snapshot
   * surface is intentionally omitted and cast away.
   *
   * @param deniedAction the per-route capability phrase, or undefined to omit it entirely
   */
  const routeWith = (deniedAction?: string, deniedActionKey?: string): ActivatedRouteSnapshot =>
    ({
      data: {
        ...(deniedAction ? { deniedAction } : {}),
        ...(deniedActionKey ? { deniedActionKey } : {}),
      },
    }) as unknown as ActivatedRouteSnapshot;

  /** The guard ignores router state; a bare stub keeps the call sites readable. */
  const state = {} as RouterStateSnapshot;

  /** Invokes the guard inside an injection context and normalises the (never-async) result. */
  const runGuard = (deniedAction?: string, deniedActionKey?: string): boolean | UrlTree =>
    TestBed.runInInjectionContext(() => adminGuard(routeWith(deniedAction, deniedActionKey), state)) as boolean | UrlTree;

  beforeEach(() => {
    userService = { isAuthenticated: vi.fn(), hasAnyAuthority: vi.fn() };
    notifications = { onWarning: vi.fn() };
    transloco = translocoStub();

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: UserService, useValue: userService },
        { provide: NotificationsService, useValue: notifications },
        { provide: TranslocoService, useValue: transloco },
      ],
    });

    router = TestBed.inject(Router);
  });

  it('redirects an unauthenticated visitor to /login without a toast', () => {
    userService.isAuthenticated.mockReturnValue(false);

    const result = runGuard('manage users');

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/login');
    // No authority check is needed once we know there is no session at all...
    expect(userService.hasAnyAuthority).not.toHaveBeenCalled();
    // ...and a "you lack permission" toast would be misleading for someone simply logged out.
    expect(notifications.onWarning).not.toHaveBeenCalled();
  });

  it('admits an authenticated user holding a staff-grade authority', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(true);

    expect(runGuard('manage users')).toBe(true);
    // Must ask for exactly the authorities SecurityConfig enforces on /admin/**.
    expect(userService.hasAnyAuthority).toHaveBeenCalledWith('UPDATE:USER', 'UPDATE:ROLE');
    expect(notifications.onWarning).not.toHaveBeenCalled();
  });

  it('names the blocked capability from route data before bouncing home', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    const result = runGuard('manage users');

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/');
    expect(notifications.onWarning).toHaveBeenCalledTimes(1);
    expect(notifications.onWarning).toHaveBeenCalledWith(
      "You don't have permission to manage users — contact your administrator.",
    );
  });

  it('falls back to a generic phrase when a route omits deniedAction', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    const result = runGuard();

    expect(router.serializeUrl(result as UrlTree)).toBe('/');
    expect(notifications.onWarning).toHaveBeenCalledWith(
      "You don't have permission to access this area — contact your administrator.",
    );
  });

  it('prefers a route translation key over the English literal', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    runGuard('manage users', 'permissions.actions.manageUsers');

    expect(transloco.translate).toHaveBeenCalledWith('permissions.actions.manageUsers');
    expect(notifications.onWarning).toHaveBeenCalledWith(
      "You don't have permission to manage users — contact your administrator.",
    );
  });

  it('falls back to the English literal when the key has no translation', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    // Transloco returns the key itself for a missing translation. Rendering that at the user —
    // "You don't have permission to permissions.actions.notYetTranslated" — is the failure this
    // guards against, and it is the normal state of a half-migrated route table.
    runGuard('view billing', 'permissions.actions.notYetTranslated');

    expect(notifications.onWarning).toHaveBeenCalledWith(
      "You don't have permission to view billing — contact your administrator.",
    );
  });

  it('keeps the denial message non-enumerating', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    runGuard('view billing');

    // The app-wide rule: never leak identity, role names, or record existence through an
    // error path. The message may name the *capability* the user lacks and nothing else.
    const message: string = notifications.onWarning.mock.calls[0][0];
    expect(message).not.toMatch(/UPDATE:|READ:|DELETE:/);
    expect(message.toLowerCase()).not.toContain('role_');
    expect(message).toContain('view billing');
  });
});
