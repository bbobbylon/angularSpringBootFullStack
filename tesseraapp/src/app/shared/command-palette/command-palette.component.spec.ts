import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CommandPaletteComponent } from './command-palette.component';
import { UserService } from '../../service/user.service';
import { ThemeService } from '../../service/theme.service';

/**
 * Smoke specs for {@link CommandPaletteComponent} — the ⌘/Ctrl+K palette mounted once
 * beside the router outlet (M7 of the UI roadmap, ROADMAP §1.1).
 *
 * <p>Every member of the component is {@code protected} (the template is its only intended
 * caller), so these specs deliberately drive it the way a user does — dispatching real
 * keyboard events at {@code document} and asserting on rendered DOM — rather than reaching
 * past the encapsulation. That also makes them resilient: they describe behaviour, not the
 * component's internal signal names.
 *
 * <p>The app is zoneless (Angular 21 default; note the absence of {@code zone.js}), so a
 * dispatched event does <em>not</em> schedule change detection on its own. Each interaction
 * is therefore followed by an explicit {@code fixture.detectChanges()}.
 *
 * <p>The most important case here is authority filtering: the palette rebuilds its command
 * list from the live token on every open, and the admin block must not render for a
 * non-staff session. As everywhere in this app that is a usability guarantee, not a security
 * one (NFR-SEC-4) — the backend re-checks authorities on every {@code /admin/**} call.
 */
describe('CommandPaletteComponent', () => {
  let fixture: ComponentFixture<CommandPaletteComponent>;
  let userService: { isAuthenticated: ReturnType<typeof vi.fn>; hasAnyAuthority: ReturnType<typeof vi.fn>; logOut: ReturnType<typeof vi.fn> };
  let theme: { toggle: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };

  /** Dispatches the global open/close hotkey and flushes the resulting render. */
  const pressHotkey = (): void => {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
    fixture.detectChanges();
  };

  /** Dispatches a key at the search input (the palette's inner keyboard model). */
  const pressInInput = (key: string): void => {
    input().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    fixture.detectChanges();
  };

  /** The component's host element. {@code fixture.nativeElement} is `any`, so narrow it once here. */
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  /** The overlay panel, or null when the palette is closed. */
  const panel = (): HTMLElement | null => host().querySelector('.cmdk-panel');

  const input = (): HTMLInputElement => host().querySelector('.cmdk-input') as HTMLInputElement;

  /** Visible entry labels, in render order. */
  const labels = (): string[] =>
    Array.from(host().querySelectorAll<HTMLElement>('.cmdk-item__label')).map((el) =>
      (el.textContent ?? '').trim(),
    );

  /** Types into the search box the way a user would, then flushes the render. */
  const type = (value: string): void => {
    const el = input();
    el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  };

  /**
   * Configures the TestBed for a given session shape.
   *
   * <p>{@code hasAnyAuthority} is modelled as a real authority set rather than a single boolean.
   * A flat {@code mockReturnValue} would answer "yes" (or "no") to *every* question the palette
   * asks, which stopped being adequate once the palette gated two different capabilities: it
   * could no longer represent the account that genuinely exists in this system — a
   * {@code ROLE_MODERATOR} that may edit customers but may not administer users. Mirroring the
   * seeded roles from {@code schema.sql} keeps the spec honest about which combinations are real.
   *
   * @param opts.authenticated whether a valid token is present
   * @param opts.admin whether that token carries staff authorities (UPDATE:USER / UPDATE:ROLE)
   * @param opts.write whether it carries write authority (UPDATE:CUSTOMER); defaults to
   *                   {@code opts.admin}, since every staff role holds UPDATE:USER and so can write
   */
  const setup = (opts: { authenticated: boolean; admin: boolean; write?: boolean }): void => {
    const granted = new Set<string>(['READ:USER', 'READ:CUSTOMER']);
    if (opts.write ?? opts.admin) granted.add('UPDATE:CUSTOMER');
    if (opts.admin) {
      granted.add('UPDATE:USER');
      granted.add('UPDATE:ROLE');
    }

    userService = {
      isAuthenticated: vi.fn().mockReturnValue(opts.authenticated),
      hasAnyAuthority: vi.fn((...authorities: string[]) => authorities.some((authority) => granted.has(authority))),
      logOut: vi.fn(),
    };
    theme = { toggle: vi.fn() };
    router = { navigate: vi.fn().mockResolvedValue(true) };

    TestBed.configureTestingModule({
      imports: [CommandPaletteComponent],
      providers: [
        { provide: UserService, useValue: userService },
        { provide: ThemeService, useValue: theme },
        { provide: Router, useValue: router },
      ],
    });

    fixture = TestBed.createComponent(CommandPaletteComponent);
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('renders nothing until the hotkey is pressed', () => {
    setup({ authenticated: true, admin: false });

    expect(panel()).toBeNull();
  });

  it('ignores the hotkey for an unauthenticated visitor', () => {
    setup({ authenticated: false, admin: false });

    pressHotkey();

    // The palette must never surface on the public login/verify screens.
    expect(panel()).toBeNull();
  });

  it('opens on Ctrl+K and shows the base navigation set', () => {
    setup({ authenticated: true, admin: false });

    pressHotkey();

    expect(panel()).not.toBeNull();
    expect(labels()).toContain('Home');
    expect(labels()).toContain('Security Center');
    expect(labels()).toContain('Log Out');
  });

  it('withholds admin destinations from a non-staff session', () => {
    setup({ authenticated: true, admin: false });

    pressHotkey();

    expect(labels()).not.toContain('User Directory');
    expect(labels()).not.toContain('Roles & Permissions');
    expect(labels()).not.toContain('Billing Overview');
    expect(labels()).not.toContain('Analytics Hub');
  });

  it('offers admin destinations to a staff-grade session', () => {
    setup({ authenticated: true, admin: true });

    pressHotkey();

    expect(labels()).toEqual(expect.arrayContaining(['User Directory', 'Roles & Permissions', 'Billing Overview', 'Analytics Hub']));
    // The palette must gate on the same authorities SecurityConfig enforces on /admin/**.
    expect(userService.hasAnyAuthority).toHaveBeenCalledWith('UPDATE:USER', 'UPDATE:ROLE');
  });

  it('withholds creation commands from a read-only session', () => {
    setup({ authenticated: true, admin: false, write: false });

    pressHotkey();

    // A read-only account may browse both directories but must not be offered the forms it
    // could never submit — the same gate the navbar applies (ROADMAP §2).
    expect(labels()).toContain('All Customers');
    expect(labels()).toContain('All Invoices');
    expect(labels()).not.toContain('New Customer');
    expect(labels()).not.toContain('New Invoice');
    expect(userService.hasAnyAuthority).toHaveBeenCalledWith('UPDATE:CUSTOMER', 'UPDATE:USER');
  });

  it('offers creation commands to a writer without staff authority', () => {
    // ROLE_MODERATOR: UPDATE:CUSTOMER but no UPDATE:USER/UPDATE:ROLE. Proves the two gates are
    // independent — write access must not be smuggled in on the back of the admin check.
    setup({ authenticated: true, admin: false, write: true });

    pressHotkey();

    expect(labels()).toEqual(expect.arrayContaining(['New Customer', 'New Invoice']));
    expect(labels()).not.toContain('User Directory');
  });

  it('filters entries by label or hint as the user types', () => {
    setup({ authenticated: true, admin: false, write: true });
    pressHotkey();

    type('invoice');

    const visible = labels();
    expect(visible).toContain('All Invoices');
    expect(visible).toContain('New Invoice');
    expect(visible).not.toContain('Home');
  });

  it('shows an empty state when nothing matches', () => {
    setup({ authenticated: true, admin: false });
    pressHotkey();

    type('zzzzz-no-such-command');

    expect(labels()).toEqual([]);
    expect(host().querySelector('.cmdk-empty')).not.toBeNull();
  });

  it('runs the highlighted entry on Enter and closes', () => {
    setup({ authenticated: true, admin: false });
    pressHotkey();

    // Narrow to a single result so the highlighted entry is unambiguous.
    type('Service Catalog');
    expect(labels()).toEqual(['Service Catalog']);

    pressInInput('Enter');

    expect(router.navigate).toHaveBeenCalledWith(['/services']);
    expect(panel()).toBeNull();
  });

  it('wraps the highlight with the arrow keys', () => {
    setup({ authenticated: true, admin: false });
    pressHotkey();

    // ArrowUp from the first entry wraps to the last, which for a base session is Log Out.
    pressInInput('ArrowUp');
    pressInInput('Enter');

    expect(userService.logOut).toHaveBeenCalledTimes(1);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('closes on Escape and on a second hotkey press', () => {
    setup({ authenticated: true, admin: false });

    pressHotkey();
    expect(panel()).not.toBeNull();
    pressInInput('Escape');
    expect(panel()).toBeNull();

    pressHotkey();
    expect(panel()).not.toBeNull();
    pressHotkey();
    expect(panel()).toBeNull();
  });
});
