import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { capabilityGuard } from './capability.guard';
import { UserService } from '../service/user.service';
import { NotificationsService } from '../service/notifications-service';
import { TranslocoService } from '@jsverse/transloco';
import { translocoStub } from '../testing/transloco-stub';

/**
 * Specs for {@link capabilityGuard} — the route-data-driven gate on {@code /customer/new} and
 * {@code /invoice/new} (ROADMAP §2, capability-level RBAC gating).
 *
 * <p>Structured like {@code admin.guard.spec.ts}, and for the same reason: the two guards must
 * be indistinguishable to a user who trips over them. The cases unique to this one are the
 * *parameterisation* — that it asks for the authorities the route declares rather than a fixed
 * pair — and the fail-closed behaviour when a route forgets to declare any.
 *
 * <p>The authority list deserves care in both directions. Demanding too much locks
 * {@code ROLE_MODERATOR} out of a page the backend would happily serve; demanding too little
 * hands a user a form that can only 403. The first case is the one that gets shipped unnoticed,
 * because the people who test the app are usually administrators.
 */
describe('capabilityGuard', () => {
  let userService: { isAuthenticated: ReturnType<typeof vi.fn>; hasAnyAuthority: ReturnType<typeof vi.fn> };
  let notifications: { onWarning: ReturnType<typeof vi.fn> };
  /** Translation double, shared with admin.guard.spec so both guards are held to one behaviour. */
  let transloco: { translate: ReturnType<typeof vi.fn> };
  let router: Router;

  /**
   * Builds the snapshot the router would hand the guard.
   *
   * @param data the route's {@code data} block — {@code requiredAuthorities} and/or {@code deniedAction}
   */
  const routeWith = (data: Record<string, unknown>): ActivatedRouteSnapshot =>
    ({ data }) as unknown as ActivatedRouteSnapshot;

  const state = {} as RouterStateSnapshot;

  const runGuard = (data: Record<string, unknown>): boolean | UrlTree =>
    TestBed.runInInjectionContext(() => capabilityGuard(routeWith(data), state)) as boolean | UrlTree;

  /** The declaration the real {@code /customer/new} route carries. */
  const CUSTOMER_CREATE = {
    requiredAuthorities: ['UPDATE:CUSTOMER', 'UPDATE:USER'],
    deniedAction: 'create customers',
    deniedActionKey: 'permissions.actions.createCustomers',
  };

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

    const result = runGuard(CUSTOMER_CREATE);

    expect(router.serializeUrl(result as UrlTree)).toBe('/login');
    expect(userService.hasAnyAuthority).not.toHaveBeenCalled();
    // "You lack permission" is the wrong thing to tell someone who is simply signed out.
    expect(notifications.onWarning).not.toHaveBeenCalled();
  });

  it('asks for exactly the authorities the route declares', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(true);

    expect(runGuard(CUSTOMER_CREATE)).toBe(true);
    // Spread, not passed as an array — the signature the backend's hasAnyAuthority mirrors.
    expect(userService.hasAnyAuthority).toHaveBeenCalledWith('UPDATE:CUSTOMER', 'UPDATE:USER');
    expect(notifications.onWarning).not.toHaveBeenCalled();
  });

  it('admits a non-staff writer, who must not be locked out of creation pages', () => {
    // ROLE_MODERATOR: holds UPDATE:CUSTOMER, holds no staff authority. Gating these routes with
    // adminGuard instead would have refused this account a page the server serves it happily.
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockImplementation((...authorities: string[]) => authorities.includes('UPDATE:CUSTOMER'));

    expect(runGuard(CUSTOMER_CREATE)).toBe(true);
  });

  it('names the blocked capability before bouncing home', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    const result = runGuard(CUSTOMER_CREATE);

    expect(router.serializeUrl(result as UrlTree)).toBe('/');
    expect(notifications.onWarning).toHaveBeenCalledWith(
      "You don't have permission to create customers — contact your administrator.",
    );
  });

  it('denies a route that declares no required authorities', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(true);

    // Fail closed. A misconfigured route must not become a silently open one — even for a user
    // who would in fact have passed, since the next user might not.
    const result = runGuard({ deniedAction: 'create customers' });

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/');
    expect(userService.hasAnyAuthority).not.toHaveBeenCalled();
  });

  it('falls back to a generic phrase when a route omits deniedAction', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    runGuard({ requiredAuthorities: ['UPDATE:CUSTOMER'] });

    expect(notifications.onWarning).toHaveBeenCalledWith(
      "You don't have permission to access this area — contact your administrator.",
    );
  });

  it('keeps the denial message non-enumerating and free of internal authority strings', () => {
    userService.isAuthenticated.mockReturnValue(true);
    userService.hasAnyAuthority.mockReturnValue(false);

    runGuard(CUSTOMER_CREATE);

    const message: string = notifications.onWarning.mock.calls[0][0];
    // The user is told what they cannot do, never the internal vocabulary used to decide it.
    expect(message).not.toMatch(/UPDATE:|READ:|DELETE:/);
    expect(message.toLowerCase()).not.toContain('role_');
    expect(message).toContain('create customers');
  });
});
