import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { authenticationGuard } from './authentication.guard';
import { UserService } from '../service/user.service';
import { Key } from '../enumeration/key.enumeration';
import { jwtWith } from '../testing/jwt';
import { installMemoryLocalStorage, restoreLocalStorage } from '../testing/local-storage';

/**
 * Specs for {@link authenticationGuard} — the gate in front of every signed-in route.
 *
 * <p>Unlike {@code adminGuard} and {@code capabilityGuard}, which decide *what* a signed-in user
 * may reach, this one decides whether there is a session at all. It is the last thing standing
 * between an expired token and a page that renders as if the user were still signed in, and it is
 * the thing that must reliably land them on {@code /login} rather than somewhere ambiguous.
 *
 * <p>The file is in two halves. The first drives the guard against a {@link UserService} double,
 * pinning the guard's own contract. The second wires up the *real* service over real
 * {@code localStorage} contents, because the failure this guard is most exposed to does not
 * originate in the guard: {@code isAuthenticated()} decodes a JWT, and the decoder throws on input
 * it cannot parse instead of reporting it. A throw at that point escapes the guard entirely — the
 * router navigation aborts, no redirect happens, and the user is left staring at whatever was on
 * screen. Only an end-to-end test over real storage catches that, so both halves are kept.
 */
describe('authenticationGuard', () => {
  let router: Router;

  /** Invokes the guard in an injection context; it is never asynchronous. */
  const runGuard = (): boolean | UrlTree =>
    TestBed.runInInjectionContext(() => authenticationGuard(null!, null!)) as boolean | UrlTree;

  describe('contract', () => {
    let userService: { isAuthenticated: ReturnType<typeof vi.fn> };

    beforeEach(() => {
      userService = { isAuthenticated: vi.fn() };

      TestBed.configureTestingModule({
        providers: [provideRouter([]), { provide: UserService, useValue: userService }],
      });

      router = TestBed.inject(Router);
    });

    it('admits a user with a live session', () => {
      userService.isAuthenticated.mockReturnValue(true);

      expect(runGuard()).toBe(true);
    });

    it('redirects to /login rather than returning a bare false', () => {
      userService.isAuthenticated.mockReturnValue(false);

      const result = runGuard();

      // A bare `false` cancels the navigation and leaves the user on the previous page with no
      // explanation — on a fresh page load, that is a blank screen. A UrlTree redirects.
      expect(result).toBeInstanceOf(UrlTree);
      expect(router.serializeUrl(result as UrlTree)).toBe('/login');
    });
  });

  describe('against real token storage', () => {
    beforeEach(() => {
      installMemoryLocalStorage();

      TestBed.configureTestingModule({
        // The real UserService, so the decode path is the one that actually ships.
        providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
      });

      router = TestBed.inject(Router);
    });

    afterEach(() => {
      restoreLocalStorage();
    });

    /** Asserts the guard produced the /login redirect rather than admitting or throwing. */
    const expectRedirectToLogin = () => {
      const result = runGuard();
      expect(result).toBeInstanceOf(UrlTree);
      expect(router.serializeUrl(result as UrlTree)).toBe('/login');
    };

    it('admits a well-formed, unexpired token', () => {
      localStorage.setItem(Key.TOKEN, jwtWith({ expiresInSeconds: 3600 }));

      expect(runGuard()).toBe(true);
    });

    it('redirects when storage is empty', () => {
      expectRedirectToLogin();
    });

    it('redirects on an expired token', () => {
      localStorage.setItem(Key.TOKEN, jwtWith({ expiresInSeconds: -60 }));

      expectRedirectToLogin();
    });

    it('redirects on a token that is not a JWT at all', () => {
      // The decoder throws on anything that does not split into three parts — it does not return
      // null. Unguarded, that throw propagates out of the guard and aborts the navigation, so the
      // user is never sent anywhere; the application just stops responding to the click.
      localStorage.setItem(Key.TOKEN, 'not-a-jwt');

      expect(() => runGuard()).not.toThrow();
      expectRedirectToLogin();
    });

    it('redirects on a truncated token', () => {
      // How a token realistically becomes corrupt: storage written partially, or trimmed by a
      // quota eviction. It still contains a dot, so it survives a naive "looks like a JWT" check.
      const whole = jwtWith({ expiresInSeconds: 3600 });
      localStorage.setItem(Key.TOKEN, whole.slice(0, whole.indexOf('.') + 8));

      expect(() => runGuard()).not.toThrow();
      expectRedirectToLogin();
    });

    it('redirects when the payload is three parts of nonsense', () => {
      localStorage.setItem(Key.TOKEN, 'aaa.bbb!.ccc');

      expect(() => runGuard()).not.toThrow();
      expectRedirectToLogin();
    });

    it('admits a token carrying no exp claim, and this is a known sharp edge', () => {
      // Documented, not endorsed. The JWT helper reports a token with no `exp` as *not expired*,
      // so such a token authenticates forever on the client. Our own TokenProvider always sets
      // exp, so this cannot arise from a token we issued — but a hand-crafted one parks the user
      // in a session the server will 401 on every request, with no client-side expiry to end it.
      // Tightening this to require exp is a deliberate behaviour change, left for its own commit.
      localStorage.setItem(Key.TOKEN, jwtWith({ claims: { authorities: ['READ:USER'] }, omitExp: true }));

      expect(runGuard()).toBe(true);
    });
  });
});
