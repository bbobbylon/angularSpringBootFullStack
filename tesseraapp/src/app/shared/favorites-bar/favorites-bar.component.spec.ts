import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { signal } from '@angular/core';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { FavoritesBarComponent } from './favorites-bar.component';
import { FavoriteService } from '../../service/favorite.service';
import { UserService } from '../../service/user.service';
import { EN_PALETTE } from '../../testing/transloco-stub';

/**
 * Specs for {@link FavoritesBarComponent} — the pinned-destinations strip mounted inside
 * {@code NavbarComponent}.
 *
 * <p>Unlike {@code CommandPaletteComponent} (which resolves its labels through
 * {@code TranslocoService.translate()} imperatively, once per open), this component's template
 * uses the {@code *transloco="let t"} structural directive directly, so its labels stay reactive
 * to a live language switch. That means a bare {@code translate} spy (see {@code transloco-stub.ts})
 * is not enough here — the directive needs a real, working {@code TranslocoService}, which
 * {@link TranslocoTestingModule} provides, backed by an in-memory English dictionary reused from
 * the palette's own stub strings so the two specs cannot silently drift apart.
 *
 * <p>The app is zoneless, but every assertion below either reads state set synchronously during
 * {@code fixture.detectChanges()} or asserts on a mock call rather than a subsequent render, so no
 * extra {@code detectChanges()} calls are needed after the initial one.
 */
describe('FavoritesBarComponent', () => {
  let fixture: ComponentFixture<FavoritesBarComponent>;
  let favoriteService: { favorites: ReturnType<typeof signal<string[] | undefined>>; load: ReturnType<typeof vi.fn> };
  let userService: { hasAnyAuthority: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const bar = (): HTMLElement | null => host().querySelector('.sc-favbar');
  const pinButtons = (): HTMLButtonElement[] => Array.from(host().querySelectorAll<HTMLButtonElement>('.sc-favbar__pin'));
  const pinLabels = (): string[] =>
    pinButtons().map((button) => (button.querySelector('.sc-favbar__label')?.textContent ?? '').trim());

  /**
   * @param opts.favorites the raw ids {@code FavoriteService.favorites()} would report;
   *                       {@code undefined} models "not loaded yet"
   * @param opts.grantedAuthorities authorities the caller's current token carries, for the
   *                                staff-gated destinations (e.g. `analytics`)
   */
  const setup = (opts: { favorites: string[] | undefined; grantedAuthorities?: string[] }): void => {
    const granted = new Set(opts.grantedAuthorities ?? []);
    favoriteService = { favorites: signal(opts.favorites), load: vi.fn() };
    userService = { hasAnyAuthority: vi.fn((...authorities: string[]) => authorities.some((authority) => granted.has(authority))) };
    router = { navigate: vi.fn().mockResolvedValue(true) };

    TestBed.configureTestingModule({
      imports: [
        FavoritesBarComponent,
        TranslocoTestingModule.forRoot({
          langs: { en: { ...EN_PALETTE, 'favoritesBar.ariaLabel': 'Pinned destinations' } },
          translocoConfig: { availableLangs: ['en'], defaultLang: 'en' },
        }),
      ],
      providers: [
        { provide: FavoriteService, useValue: favoriteService },
        { provide: UserService, useValue: userService },
        { provide: Router, useValue: router },
      ],
    });

    fixture = TestBed.createComponent(FavoritesBarComponent);
    fixture.detectChanges();
  };

  it('loads favorites on construction', () => {
    setup({ favorites: [] });

    expect(favoriteService.load).toHaveBeenCalled();
  });

  it('renders nothing while favorites have not loaded yet', () => {
    setup({ favorites: undefined });

    expect(bar()).toBeNull();
  });

  it('renders nothing once loaded with an empty list', () => {
    setup({ favorites: [] });

    expect(bar()).toBeNull();
  });

  it('renders a pin for each resolvable favorite, in order', () => {
    setup({ favorites: ['customers', 'security'] });

    expect(bar()).not.toBeNull();
    expect(pinLabels()).toEqual(['All Customers', 'Security Center']);
  });

  it('silently drops a pinned id that no longer resolves in the registry', () => {
    // A destination can be removed from navigable-destinations.ts after a user pinned it; the bar
    // must not render a broken entry for it, and must not crash trying.
    setup({ favorites: ['customers', 'some-retired-destination'] });

    expect(pinLabels()).toEqual(['All Customers']);
  });

  it('hides a pin whose destination now requires an authority the caller does not hold', () => {
    // 'analytics' requires UPDATE:USER/UPDATE:ROLE. A role change that stripped it must hide the
    // pin rather than leave a dead link that 403s on click (FUTURE-ENHANCEMENTS.md §3.3 decision).
    setup({ favorites: ['customers', 'analytics'], grantedAuthorities: [] });

    expect(pinLabels()).toEqual(['All Customers']);
  });

  it('shows a staff-gated pin once the caller holds the required authority', () => {
    setup({ favorites: ['analytics'], grantedAuthorities: ['UPDATE:USER'] });

    expect(pinLabels()).toEqual(['Analytics Hub']);
  });

  it('navigates to the pinned destination on click', () => {
    setup({ favorites: ['customers'] });

    pinButtons()[0].click();

    expect(router.navigate).toHaveBeenCalledWith(['/customers']);
  });
});
