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
  readonly section: 'Navigate' | 'Actions';
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

  /** Highlights an entry (used by mouse hover to keep pointer and keyboard in sync). */
  protected highlight(index: number): void {
    this.activeIndex.set(index);
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
   */
  private buildCommands(): Command[] {
    const go = (path: string) => (): void => {
      void this.router.navigate([path]);
    };

    const commands: Command[] = [
      { id: 'home', label: 'Home', hint: 'Dashboard overview', icon: 'bi-grid-1x2-fill', section: 'Navigate', run: go('/') },
      { id: 'customers', label: 'All Customers', hint: 'Browse customer directory', icon: 'bi-people-fill', section: 'Navigate', run: go('/customers') },
      { id: 'invoices', label: 'All Invoices', hint: 'Browse invoices', icon: 'bi-receipt-cutoff', section: 'Navigate', run: go('/invoices') },
      { id: 'services', label: 'Service Catalog', hint: 'Browse services & apps', icon: 'bi-grid-1x2', section: 'Navigate', run: go('/services') },
      { id: 'profile', label: 'Profile', hint: 'Your account', icon: 'bi-person-circle', section: 'Navigate', run: go('/profile') },
      { id: 'security', label: 'Security Center', hint: 'MFA & active sessions', icon: 'bi-shield-lock', section: 'Navigate', run: go('/security') },
    ];

    // Creation commands require write authority (ROADMAP §2 — capability-level RBAC gating).
    // Both destinations POST, so they fall under SecurityConfig's
    // .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER"); a
    // read-only account offered "New Invoice" here would be led to a form that can only 403 on
    // submit. Gated at the same authorities the navbar uses, because a command palette that
    // offers a destination the menu hides is the same bug told twice.
    if (this.userService.hasAnyAuthority('UPDATE:CUSTOMER', 'UPDATE:USER')) {
      commands.push(
        { id: 'new-customer', label: 'New Customer', hint: 'Create a customer', icon: 'bi-person-plus-fill', section: 'Navigate', run: go('/customer/new') },
        { id: 'new-invoice', label: 'New Invoice', hint: 'Create an invoice', icon: 'bi-receipt', section: 'Navigate', run: go('/invoice/new') },
      );
    }

    if (this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')) {
      commands.push(
        { id: 'users', label: 'User Directory', hint: 'Admin · manage users', icon: 'bi-people-fill', section: 'Navigate', run: go('/users') },
        { id: 'roles', label: 'Roles & Permissions', hint: 'Admin · RBAC matrix', icon: 'bi-grid-3x3-gap-fill', section: 'Navigate', run: go('/roles') },
        { id: 'billing', label: 'Billing Overview', hint: 'Admin · revenue analytics', icon: 'bi-graph-up-arrow', section: 'Navigate', run: go('/billing') },
        { id: 'analytics', label: 'Analytics Hub', hint: 'Admin · trends & stats', icon: 'bi-bar-chart-line-fill', section: 'Navigate', run: go('/analytics') },
        { id: 'security-overview', label: 'Security Overview', hint: 'Admin · anomalies & MFA coverage', icon: 'bi-shield-exclamation', section: 'Navigate', run: go('/security-overview') },
        { id: 'manage-services', label: 'Manage Services', hint: 'Admin · catalog CRUD', icon: 'bi-sliders', section: 'Navigate', run: go('/services/manage') },
      );
    }

    commands.push(
      { id: 'toggle-theme', label: 'Toggle Theme', hint: 'Switch dark / light', icon: 'bi-circle-half', section: 'Actions', run: () => this.themeService.toggle() },
      {
        id: 'logout',
        label: 'Log Out',
        hint: 'End your session',
        icon: 'bi-box-arrow-right',
        section: 'Actions',
        run: () => {
          this.userService.logOut();
          void this.router.navigate(['/login']);
        },
      },
    );

    return commands;
  }
}
