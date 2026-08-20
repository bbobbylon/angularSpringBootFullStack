import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { UserService } from './user.service';
import { Key } from '../enumeration/key.enumeration';
import { jwtGranting, jwtWith } from '../testing/jwt';
import { installMemoryLocalStorage, restoreLocalStorage } from '../testing/local-storage';

/**
 * Specs for {@link UserService#hasAnyAuthority} — the token-decoding surface behind every RBAC
 * decision the client makes.
 *
 * <p>Scope is deliberately narrow: this file covers authority extraction only, not the service's
 * HTTP methods. That surface is small but unusually load-bearing. {@code adminGuard},
 * {@code capabilityGuard}, {@code *appHasAuthority} and the navbar all funnel through it, and
 * templates call it on *every change detection pass* — so its cost and its failure mode are both
 * multiplied by the whole application rather than confined to one caller.
 *
 * <p>Two properties get most of the attention here.
 *
 * <p><b>The memo must track the token, not outlive it.</b> The result is cached against the token
 * string it was derived from, because re-decoding on every change detection pass is real work.
 * But the token underneath rotates — the interceptor's silent refresh replaces it mid-session —
 * and a memo that survives that rotation answers with the authorities of a token that is no
 * longer in storage. This is the same class of bug as the one that used to hide the admin menus
 * after a page reload.
 *
 * <p><b>It must not throw.</b> The decoder raises on input it cannot parse rather than reporting
 * it, and a throw raised during change detection does not degrade one button — it takes down the
 * render pass, so the user gets a blank page instead of a page with privileged controls withheld.
 */
describe('UserService authority decoding', () => {
  let service: UserService;

  beforeEach(() => {
    // The test environment ships an inert localStorage placeholder; see the helper's docs.
    installMemoryLocalStorage();

    // A testing backend rather than the real one: logOut() fires a best-effort revoke, and this
    // captures it instead of attempting an actual network call.
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    // Resolved per spec, so each one starts with an empty authority memo.
    service = TestBed.inject(UserService);
  });

  afterEach(() => {
    restoreLocalStorage();
  });

  describe('reading the authorities claim', () => {
    it('grants an authority the token carries', () => {
      localStorage.setItem(Key.TOKEN, jwtGranting(['READ:USER', 'UPDATE:USER']));

      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(true);
    });

    it('withholds an authority the token does not carry', () => {
      localStorage.setItem(Key.TOKEN, jwtGranting(['READ:USER']));

      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);
    });

    it('treats multiple arguments as OR, mirroring the backend rule', () => {
      // Matches Spring Security's hasAnyAuthority. Reading this as AND would lock a moderator out
      // of pages the server serves them without complaint.
      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:CUSTOMER']));

      expect(service.hasAnyAuthority('UPDATE:USER', 'UPDATE:CUSTOMER')).toBe(true);
      expect(service.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')).toBe(false);
    });

    it('withholds everything when asked for nothing', () => {
      // A caller that spreads an empty array — a route whose requiredAuthorities is missing —
      // must fail closed rather than sail through on a vacuous "some".
      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER']));

      expect(service.hasAnyAuthority()).toBe(false);
    });

    it('matches authorities exactly, not by prefix', () => {
      // 'UPDATE:USER' must not be satisfied by holding 'UPDATE:USERGROUP', and READ must never
      // stand in for UPDATE. Substring matching here would quietly widen every gate in the app.
      localStorage.setItem(Key.TOKEN, jwtGranting(['READ:USER']));

      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);
      expect(service.hasAnyAuthority('READ:USERGROUP')).toBe(false);
    });
  });

  describe('tokens that grant nothing', () => {
    it('grants nothing when storage is empty', () => {
      expect(service.hasAnyAuthority('READ:USER')).toBe(false);
    });

    it('grants nothing from an expired token, however privileged', () => {
      // The claim is still readable in an expired token. Honouring it would leave the admin
      // dashboard rendered for a session the server stopped accepting some time ago.
      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER', 'UPDATE:ROLE'], -60));

      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);
    });

    it('grants nothing from a token with no authorities claim', () => {
      // Federated first-login tokens and the TOTP challenge envelope both fit this shape.
      localStorage.setItem(Key.TOKEN, jwtWith({ claims: { sub: 'someone@tessera.test' } }));

      expect(() => service.hasAnyAuthority('READ:USER')).not.toThrow();
      expect(service.hasAnyAuthority('READ:USER')).toBe(false);
    });

    it('grants nothing when the authorities claim is not an array of strings', () => {
      localStorage.setItem(Key.TOKEN, jwtWith({ claims: { authorities: 'UPDATE:USER' } }));

      expect(() => service.hasAnyAuthority('UPDATE:USER')).not.toThrow();
      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);
    });
  });

  describe('unreadable tokens', () => {
    /** The shapes a corrupted storage entry realistically takes. */
    const corrupt: Record<string, string> = {
      'not a JWT at all': 'not-a-jwt',
      'two segments': 'aaaa.bbbb',
      'four segments': 'aaaa.bbbb.cccc.dddd',
      'undecodable payload': 'aaaa.!!!!.cccc',
      'payload that is not JSON': `aaaa.${btoa('plain text').replace(/=+$/, '')}.cccc`,
      'whitespace': '   ',
    };

    for (const [shape, token] of Object.entries(corrupt)) {
      it(`grants nothing, without throwing, for ${shape}`, () => {
        localStorage.setItem(Key.TOKEN, token);

        // The assertion that matters is the absence of a throw. Templates evaluate this during
        // change detection, so an exception here is not a withheld button — it is a blank page.
        expect(() => service.hasAnyAuthority('READ:USER')).not.toThrow();
        expect(service.hasAnyAuthority('READ:USER')).toBe(false);
      });
    }

    it('recovers once a good token replaces a corrupt one', () => {
      // The memo is keyed on the token string. If a corrupt token were allowed to leave that key
      // populated, the next good token could be shadowed by it and the user would stay stripped
      // of every authority until a full reload.
      localStorage.setItem(Key.TOKEN, 'not-a-jwt');
      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);

      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER']));

      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(true);
    });
  });

  describe('memoisation across token rotation', () => {
    it('reflects a token swapped in by the interceptor mid-session', () => {
      // Exactly what a silent refresh does: same tab, same service instance, new token in
      // storage. A memo that survived this would report the old token's authorities indefinitely.
      localStorage.setItem(Key.TOKEN, jwtGranting(['READ:USER']));
      expect(service.hasAnyAuthority('UPDATE:ROLE')).toBe(false);

      localStorage.setItem(Key.TOKEN, jwtGranting(['READ:USER', 'UPDATE:ROLE']));

      expect(service.hasAnyAuthority('UPDATE:ROLE')).toBe(true);
    });

    it('drops authorities the moment a rotation removes them', () => {
      // The direction that matters for correctness rather than convenience: an administrator
      // demoted to a plain user must stop seeing admin controls on the next evaluation, not on
      // the next full page load.
      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER', 'UPDATE:ROLE']));
      expect(service.hasAnyAuthority('UPDATE:ROLE')).toBe(true);

      localStorage.setItem(Key.TOKEN, jwtGranting(['READ:USER']));

      expect(service.hasAnyAuthority('UPDATE:ROLE')).toBe(false);
    });

    it('stops granting when a memoised token expires underneath it', () => {
      // Expiry is the one input that changes without the token string changing, so the memo key
      // alone cannot detect it — the expiry check has to run before the cache is consulted.
      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER']));
      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(true);

      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER'], -1));

      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);
    });

    it('grants nothing after logOut clears storage', () => {
      localStorage.setItem(Key.TOKEN, jwtGranting(['UPDATE:USER']));
      localStorage.setItem(Key.REFRESH_TOKEN, jwtWith());
      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(true);

      service.logOut();

      // The revoke request is left unflushed on the testing backend on purpose: logOut is
      // fire-and-forget, and the local half must hold whether or not the server ever answers.
      expect(localStorage.getItem(Key.TOKEN)).toBeNull();
      expect(service.hasAnyAuthority('UPDATE:USER')).toBe(false);
    });
  });
});
