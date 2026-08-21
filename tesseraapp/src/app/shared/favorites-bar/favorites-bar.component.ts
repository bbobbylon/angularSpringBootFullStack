import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { FavoriteService } from '../../service/favorite.service';
import { UserService } from '../../service/user.service';
import { DestinationDefinition, findDestination } from '../navigable-destinations';

/**
 * The pinned-destinations quick-access strip (FUTURE-ENHANCEMENTS.md §3.3), mounted once inside
 * {@code NavbarComponent} so it renders directly below the top nav on every protected page — the
 * same set of templates that already include {@code <app-navbar>}.
 *
 * <p>Deliberately mounted inside the navbar rather than alongside the always-mounted
 * {@code app-command-palette}/{@code app-footer} in {@code AppComponent}: this component's
 * construction moment is the signal it relies on to know a signed-in page just became active (see
 * the constructor doc). The command palette instead self-gates per interaction because it truly is
 * always mounted and has no such moment to lean on.
 *
 * <p>Resolves each pinned {@code destinationId} against {@code navigable-destinations.ts} — the
 * same registry {@link CommandPaletteComponent} builds its entries from — so a route, icon, or
 * label change made there is picked up here for free. Pinning/unpinning happens through the star
 * on each palette result (FUTURE-ENHANCEMENTS.md §3.3 "decisions taken up front"); this component
 * is read-only navigation.
 */
@Component({
  selector: 'app-favorites-bar',
  standalone: true,
  imports: [TranslocoDirective],
  templateUrl: './favorites-bar.component.html',
  styleUrl: './favorites-bar.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FavoritesBarComponent {
  private readonly favoriteService = inject(FavoriteService);
  private readonly userService = inject(UserService);
  private readonly router = inject(Router);

  constructor() {
    // Unlike the command palette (mounted once in AppComponent for the whole app lifetime, so it
    // must re-check isAuthenticated() on every interaction), this component only ever mounts
    // inside NavbarComponent, which every protected feature template includes and no public
    // screen does. Construction time is therefore already a safe proxy for "signed in" — no
    // separate authentication gate is needed here, and FavoriteService.load() is idempotent
    // besides, so re-mounting across protected pages costs nothing extra.
    this.favoriteService.load();
  }

  /**
   * Pinned destinations that still resolve in the registry and remain visible under the caller's
   * *current* authorities — a pin surviving a role change that stripped its required authority is
   * hidden, not surfaced to 403 on click (FUTURE-ENHANCEMENTS.md §3.3 decision: hidden, not
   * removed, so a temporary role change never silently destroys someone's setup).
   */
  protected readonly pins = computed<DestinationDefinition[]>(() => {
    const ids = this.favoriteService.favorites() ?? [];
    return ids
      .map((id) => findDestination(id))
      .filter((destination): destination is DestinationDefinition => !!destination)
      .filter(
        (destination) =>
          !destination.requiredAuthorities ||
          this.userService.hasAnyAuthority(...destination.requiredAuthorities),
      );
  });

  /** Navigates to a pinned destination. */
  protected navigate(path: string): void {
    void this.router.navigate([path]);
  }
}
