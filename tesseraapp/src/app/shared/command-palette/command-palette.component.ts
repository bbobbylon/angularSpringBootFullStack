import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  HostListener,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { UserService } from '../../service/user.service';
import { ThemeService } from '../../service/theme.service';
import { CommandPaletteService } from '../../service/command-palette.service';
import { FavoriteService } from '../../service/favorite.service';
import { NotificationsService } from '../../service/notifications-service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoService } from '@jsverse/transloco';
import { findDestination } from '../navigable-destinations';

/**
 * A single actionable entry in the command palette.
 *
 * {@code run} is a thunk so the palette does not need to know whether an entry
 * navigates, toggles a service, or ends the session — it just invokes it and closes.
 */
interface Command {
  /** Stable identity for {@code @for} tracking. */
  readonly id: string;
  /** Primary label shown to the user. */
  readonly label: string;
  /** Secondary descriptor shown on the right; also matched by the search filter. */
  readonly hint: string;
  /** Bootstrap-icon class (e.g. {@code 'bi-people-fill'}). */
  readonly icon: string;
  /** Grouping bucket for the sectioned list. */
  readonly section: string;
  /** Effect to run when the entry is chosen. */
  readonly run: () => void;
}

/**
 * Application-wide command palette (⌘/Ctrl+K), M7 of the UI roadmap.
 *
 * <p>Mounted once, next to the router outlet in {@code AppComponent}, so it is reachable
 * from every route. It self-gates on authentication: the hotkey is ignored unless
 * {@link UserService#isAuthenticated} is true, so it never appears on the public
 * login/verify screens.
 *
 * <p>The command list is rebuilt each time the palette opens from the *current* access
 * token's authorities via {@link UserService#hasAnyAuthority} — the very same predicate
 * the navbar and {@code adminGuard} use. Admin destinations (User Directory, Roles,
 * Billing, Analytics) therefore appear only for staff-grade tokens, keeping the palette's
 * visible surface consistent with the rest of the UI. As everywhere else in this app,
 * that visibility is a usability aid only (NFR-SEC-4); the backend re-checks authorities
 * on every {@code /admin/**} request regardless of what the palette renders.
 *
 * <p>Fully keyboard-driven: ⌘/Ctrl+K toggles, arrows move the highlight (wrapping),
 * Enter runs the active entry, Escape closes. State is held in signals so the OnPush
 * view updates without manual change detection.
 */
@Component({
  selector: 'app-command-palette',
  standalone: true,
  imports: [],
  templateUrl: './command-palette.component.html',
  styleUrl: './command-palette.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommandPaletteComponent {
  private readonly router = inject(Router);
  private readonly userService = inject(UserService);
  private readonly themeService = inject(ThemeService);
  private readonly transloco = inject(TranslocoService);
  private readonly paletteService = inject(CommandPaletteService);
  private readonly favoriteService = inject(FavoriteService);
  private readonly notifications = inject(NotificationsService);

  /** Whether the overlay is currently visible. */
  protected readonly open = signal(false);
  /** Live text in the search box. */
  protected readonly query = signal('');
  /** Index (into {@link results}) of the highlighted entry. */
  protected readonly activeIndex = signal(0);
  /** Full, authority-filtered command set, snapshotted when the palette opens. */
  private readonly baseCommands = signal<Command[]>([]);

  /** The search input, focused programmatically the moment the overlay opens. */
  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  /**
   * The commands matching the current query — a case-insensitive substring match over
   * both the label and the hint. An empty query returns the full set.
   */
  protected readonly results = computed<Command[]>(() => {
    const q = this.query().trim().toLowerCase();
    const all = this.baseCommands();
    if (!q) return all;
    return all.filter(
      (c) => c.label.toLowerCase().includes(q) || c.hint.toLowerCase().includes(q),
    );
  });

  /**
   * {@link results} arranged into {section, items} buckets for the sectioned template.
   * Each item carries its *flat* index in {@link results} so the highlight/keyboard model
   * can stay a single integer even though the list is rendered in groups.
   */
  protected readonly grouped = computed(() => {
    const groups: { section: string; items: { command: Command; index: number }[] }[] = [];
    this.results().forEach((command, index) => {
      let bucket = groups.find((g) => g.section === command.section);
      if (!bucket) {
        bucket = { section: command.section, items: [] };
        groups.push(bucket);
      }
      bucket.items.push({ command, index });
    });
    return groups;
  });

  constructor() {
    // Anything outside this component's subtree (the navbar button) opens the palette through
    // CommandPaletteService rather than by reaching in — see that service for why.
    this.paletteService.openRequested$
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.userService.isAuthenticated() && !this.open()) {
          this.openPalette();
        }
      });

    // Move focus into the search field once the overlay has rendered. The viewChild
    // query resolves during change detection; the macrotask defers the focus() call
    // until after the input element actually exists in the DOM.
    effect(() => {
      if (this.open()) {
        setTimeout(() => this.searchInput()?.nativeElement.focus());
      }
    });
  }

  /**
   * Global hotkey listener. ⌘/Ctrl+K toggles the palette from anywhere (but only for an
   * authenticated user); Escape is also caught here as a belt-and-braces close in case
   * focus has left the input.
   */
  @HostListener('document:keydown', ['$event'])
  protected onGlobalKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && (event.key === 'k' || event.key === 'K')) {
      event.preventDefault();
      if (!this.userService.isAuthenticated()) return;
      if (this.open()) {
        this.close();
      } else {
        this.openPalette();
      }
      return;
    }
    if (this.open() && event.key === 'Escape') {
      event.preventDefault();
      this.close();
    }
  }

  /** Opens the palette, rebuilding the command set from the current token's authorities. */
  protected openPalette(): void {
    this.baseCommands.set(this.buildCommands());
    this.query.set('');
    this.activeIndex.set(0);
    this.open.set(true);
    // Idempotent — a no-op once the caller's favorites are already loaded (e.g. via the navbar's
    // FavoritesBarComponent). Loaded here too because the palette can open before the navbar has
    // ever mounted a favorites bar of its own.
    this.favoriteService.load();
  }

  /** Hides the palette. */
  protected close(): void {
    this.open.set(false);
  }

  /** Updates the query and resets the highlight to the first match. */
  protected onQueryChange(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
  }

  /**
   * Arrow/Enter/Escape handling while the search box has focus. Arrow keys wrap around
   * the ends of the list so the highlight is never "stuck".
   */
  protected onInputKeydown(event: KeyboardEvent): void {
    const count = this.results().length;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.activeIndex.update((i) => (count ? (i + 1) % count : 0));
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.activeIndex.update((i) => (count ? (i - 1 + count) % count : 0));
        break;
      case 'Enter':
        event.preventDefault();
        this.runActive();
        break;
      case 'Escape':
        event.preventDefault();
        this.close();
        break;
    }
  }

  /** Runs a command and closes the palette. */
  protected run(command: Command): void {
    this.close();
    command.run();
  }

  /**
   * Closes the palette when the click landed on the backdrop itself rather than inside the dialog.
   *
   * <p>Replaces a {@code (click)="$event.stopPropagation()"} that used to sit on the panel purely
   * to stop inner clicks from bubbling out and dismissing the palette. Testing the target here
   * removes that second handler entirely: a click inside the dialog simply is not a backdrop
   * click, so there is nothing to cancel. One less interactive element, and one less thing for a
   * future edit to get wrong.
   *
   * <p>Keyboard dismissal is unaffected and lives elsewhere — Escape is caught document-wide by
   * {@link onDocumentKeydown}, so the palette closes regardless of what holds focus.
   *
   * @param event the click event from the backdrop
   */
  protected onBackdropClick(event: Event): void {
    if (event.target === event.currentTarget) {
      this.close();
    }
  }

  /** Highlights an entry (used by mouse hover to keep pointer and keyboard in sync). */
  protected highlight(index: number): void {
    this.activeIndex.set(index);
  }

  /**
   * Whether a result can be pinned at all — only entries resolvable in
   * {@code NAVIGABLE_DESTINATIONS} (navigable-destinations.ts) (i.e. not `home`, not an action like `toggle-theme`/`logout`,
   * not the query-param `new-service` variant) show a star. See that module's doc for the
   * full exclusion list and why.
   */
  protected isFavoritable(id: string): boolean {
    return !!findDestination(id);
  }

  /** Whether the given destination id is currently in the caller's pinned set. */
  protected isPinned(id: string): boolean {
    return (this.favoriteService.favorites() ?? []).includes(id);
  }

  /**
   * Pins or unpins a result's destination, without running the result itself or closing the
   * palette — {@code stopPropagation} is required because the star sits inside the same
   * {@code (click)="run(item.command)"} row.
   *
   * @param event the star button's click event
   * @param id    the destination id to toggle
   */
  protected toggleFavorite(event: Event, id: string): void {
    event.stopPropagation();
    const request = this.isPinned(id) ? this.favoriteService.remove$(id) : this.favoriteService.add$(id);
    request.subscribe({
      // Only the cap-exceeded ApiException reaches here in practice (unpinning never errors) —
      // surfaced as a toast rather than silently swallowed, since a star that visibly did nothing
      // would read as a bug rather than a deliberate limit.
      error: (err: Error) => this.notifications.onError(err.message),
    });
  }

  /** Runs whichever entry is currently highlighted, if any. */
  private runActive(): void {
    const command = this.results()[this.activeIndex()];
    if (command) {
      this.run(command);
    }
  }

  /**
   * Assembles the command set for the current session. Base navigation is available to
   * every authenticated user; the admin block is appended only when the token carries a
   * staff-grade authority, and the theme/logout actions always close the list.
   *
   * <p>Label/hint/icon/route for every navigable entry come from {@code NAVIGABLE_DESTINATIONS} (navigable-destinations.ts)
   * — the registry {@code FavoritesBarComponent} also resolves pinned destinations against —
   * rather than being duplicated here, so the two surfaces cannot silently drift apart.
   */
  private buildCommands(): Command[] {
    // Resolved once per open rather than per entry: the palette is rebuilt every time it opens,
    // so this picks up a language change without needing the command list to be reactive.
    const t = (key: string): string => this.transloco.translate(key);
    const navigate = t('palette.sectionNavigate');
    const actions = t('palette.sectionActions');

    const go = (path: string) => (): void => {
      void this.router.navigate([path]);
    };

    const toCommand = (id: string): Command => {
      const destination = findDestination(id)!;
      return {
        id: destination.id,
        label: t(destination.labelKey),
        hint: t(destination.hintKey),
        icon: destination.icon,
        section: navigate,
        run: go(destination.path),
      };
    };

    const commands: Command[] = [
      { id: 'home', label: t('palette.home'), hint: t('palette.homeHint'), icon: 'bi-grid-1x2-fill', section: navigate, run: go('/') },
      ...(['customers', 'invoices', 'services', 'profile', 'security'] as const).map(toCommand),
    ];

    // Creation commands require write authority (ROADMAP §2 — capability-level RBAC gating).
    // Both destinations POST, so they fall under SecurityConfig's
    // .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER"); a
    // read-only account offered "New Invoice" here would be led to a form that can only 403 on
    // submit. Gated at the same authorities the navbar uses, because a command palette that
    // offers a destination the menu hides is the same bug told twice.
    if (this.userService.hasAnyAuthority('UPDATE:CUSTOMER', 'UPDATE:USER')) {
      commands.push(toCommand('new-customer'), toCommand('new-invoice'));
    }

    if (this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')) {
      commands.push(
        toCommand('users'),
        toCommand('roles'),
        toCommand('billing'),
        toCommand('analytics'),
        toCommand('security-overview'),
        toCommand('manage-services'),
        {
          id: 'new-service',
          label: t('palette.newService'),
          hint: t('palette.newServiceHint'),
          icon: 'bi-plus-circle',
          section: navigate,
          // /services/manage has no dedicated create route (the form is an inline toggle over the
          // list, unlike /invoice/new and /customer/new) — the ?new query param opens it directly.
          // Deliberately not in NAVIGABLE_DESTINATIONS — see that module's doc for why.
          run: () => {
            void this.router.navigate(['/services/manage'], { queryParams: { new: true } });
          },
        },
      );
    }

    commands.push(
      { id: 'toggle-theme', label: t('palette.toggleTheme'), hint: t('palette.toggleThemeHint'), icon: 'bi-circle-half', section: actions, run: () => this.themeService.toggle() },
      {
        id: 'logout',
        label: t('palette.logout'),
        hint: t('palette.logoutHint'),
        icon: 'bi-box-arrow-right',
        section: actions,
        run: () => {
          this.userService.logOut();
          void this.router.navigate(['/login']);
        },
      },
    );

    return commands;
  }
}
