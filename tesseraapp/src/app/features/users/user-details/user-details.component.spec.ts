import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { UserDetailsComponent } from './user-details.component';
import { AdminUserService } from '../../../service/admin-user.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { TRANSLOCO_TESTING_IMPORTS } from '../../../testing/transloco-testing';
import { installMemoryLocalStorage, restoreLocalStorage } from '../../../testing/local-storage';
import { AdminUserDetailInterface } from '../../../interface/admin.interface';
import { PasskeyInterface } from '../../../interface/security.interface';
import { UserInterface } from '../../../interface/user.interface';

/**
 * Specs for {@link UserDetailsComponent}'s admin Passkeys panel — the third and last of the
 * three passkey-UI surfaces named as the frontend coverage gap in FUTURE-ENHANCEMENTS.md §2.2.
 *
 * <p>Scope is the revoke controls only (single-credential and bulk), not the role/account-state
 * forms or the sessions/events panels this component also owns. Two things this panel must get
 * right, both asserted below: it gates on {@code UPDATE:USER} the same way the backend does
 * (NFR-SEC-4 — a UI convenience, not the real boundary), and it respects the same self-targeting
 * rule the role/settings forms already enforce ({@link UserDetailsComponent#isSelf}) even though
 * the backend has no separate self-targeting guard on the passkey-revoke endpoints specifically —
 * hiding the controls here is this component's own consistent policy, not a mirror of a
 * corresponding server check.
 */
describe('UserDetailsComponent — admin Passkeys panel', () => {
  let fixture: ComponentFixture<UserDetailsComponent>;
  let adminUserService: Record<string, ReturnType<typeof vi.fn>>;
  let userService: Record<string, ReturnType<typeof vi.fn>>;
  let notifications: { onSuccess: ReturnType<typeof vi.fn>; onError: ReturnType<typeof vi.fn> };

  const baseUser = (overrides: Partial<UserInterface> = {}): UserInterface => ({
    id: 1,
    username: 'admin@example.com',
    email: 'admin@example.com',
    phoneNumber: '',
    enabled: true,
    notLocked: true,
    using2FA: false,
    usingTotp: false,
    usingPasskey: false,
    createdAt: new Date('2026-01-01T00:00:00Z'),
    roleName: 'ROLE_ADMIN',
    permissions: '',
    ...overrides,
  });

  const PASSKEY_A: PasskeyInterface = {
    id: 7,
    deviceName: "Jane's iPhone",
    transports: 'hybrid',
    createdAt: new Date('2026-01-01T00:00:00Z'),
  };
  const PASSKEY_B: PasskeyInterface = {
    id: 8,
    deviceName: 'YubiKey',
    transports: 'usb',
    createdAt: new Date('2026-01-05T00:00:00Z'),
  };

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  /** The Passkeys `.card` in the managed-user detail page. */
  const passkeyCard = (): HTMLElement | undefined =>
    Array.from(host().querySelectorAll<HTMLElement>('.card')).find((card) => card.textContent?.includes('Passkeys'));

  const passkeyRows = (): HTMLElement[] => Array.from(passkeyCard()?.querySelectorAll<HTMLElement>('tbody tr') ?? []);

  const findButton = (root: HTMLElement, text: string): HTMLButtonElement | undefined =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((button) => button.textContent?.trim().includes(text));

  /**
   * @param opts.admin whether the calling session carries `UPDATE:USER` (gates every revoke control)
   * @param opts.selfTargeting whether the managed account IS the calling administrator
   * @param opts.passkeys the managed user's registered passkeys
   */
  const setup = (opts: { admin: boolean; selfTargeting?: boolean; passkeys?: PasskeyInterface[] }): void => {
    // The page mounts <app-navbar>, whose CurrentUserService/ThemeService/LanguageService read
    // localStorage and call UserService#profile$() at construction — unrelated to the Passkeys
    // panel, but required for the fixture to render at all.
    installMemoryLocalStorage();

    const callerId = 1;
    const detail: AdminUserDetailInterface = {
      user: baseUser({ id: callerId }),
      selectedUser: baseUser({ id: opts.selfTargeting ? callerId : 42, username: 'target@example.com', email: 'target@example.com', roleName: 'ROLE_USER' }),
      events: [],
      eventsTotalElements: 0,
      eventsTotalPages: 0,
      roles: [],
      passkeys: opts.passkeys ?? [],
      sessions: [],
    };

    adminUserService = {
      user$: vi.fn().mockReturnValue(of({ data: detail })),
      revokePasskey$: vi.fn().mockReturnValue(
        of({ data: { ...detail, passkeys: (opts.passkeys ?? []).filter((p) => p.id !== 7) } }),
      ),
      revokeAllPasskeys$: vi.fn().mockReturnValue(of({ data: { ...detail, passkeys: [] } })),
    };
    userService = {
      hasAnyAuthority: vi.fn().mockReturnValue(opts.admin),
      profile$: vi.fn().mockReturnValue(of({ data: { user: baseUser({ id: callerId }) } })),
      logOut: vi.fn(),
    };
    notifications = { onSuccess: vi.fn(), onError: vi.fn() };

    TestBed.configureTestingModule({
      imports: [UserDetailsComponent, ...TRANSLOCO_TESTING_IMPORTS],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        { provide: AdminUserService, useValue: adminUserService },
        { provide: UserService, useValue: userService },
        // Overrides provideRouter([])'s root ActivatedRoute — the component reads :id from this.
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: '42' })) } },
        { provide: NotificationsService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(UserDetailsComponent);
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  afterEach(() => {
    vi.clearAllMocks();
    restoreLocalStorage();
  });

  it('shows a delete control per passkey and a bulk revoke-all for a staff session managing another account', () => {
    setup({ admin: true, passkeys: [PASSKEY_A, PASSKEY_B] });

    expect(passkeyRows()).toHaveLength(2);
    expect(findButton(passkeyRows()[0], 'delete')).not.toBeUndefined();
    expect(findButton(passkeyCard()!, 'Revoke all')).not.toBeUndefined();
  });

  it('hides every revoke control for a session without UPDATE:USER, even with passkeys present', () => {
    setup({ admin: false, passkeys: [PASSKEY_A] });

    expect(userService.hasAnyAuthority).toHaveBeenCalledWith('UPDATE:USER');
    expect(findButton(passkeyRows()[0], 'delete')).toBeUndefined();
    expect(findButton(passkeyCard()!, 'Revoke all')).toBeUndefined();
  });

  it('omits the bulk revoke-all button when the managed account has no passkeys', () => {
    setup({ admin: true, passkeys: [] });

    expect(passkeyCard()!.textContent).toContain('No passkeys registered.');
    expect(findButton(passkeyCard()!, 'Revoke all')).toBeUndefined();
  });

  it('hides revoke controls when the managed account is the caller\'s own, regardless of authority', () => {
    setup({ admin: true, selfTargeting: true, passkeys: [PASSKEY_A] });

    expect(findButton(passkeyRows()[0], 'delete')).toBeUndefined();
    expect(findButton(passkeyCard()!, 'Revoke all')).toBeUndefined();
  });

  it('revokes a single passkey and refreshes the row list from the response', () => {
    setup({ admin: true, passkeys: [PASSKEY_A, PASSKEY_B] });

    findButton(passkeyRows()[0], 'delete')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(adminUserService.revokePasskey$).toHaveBeenCalledWith(42, 7);
    expect(passkeyRows()).toHaveLength(1);
    expect(passkeyRows()[0].textContent).toContain('YubiKey');
    expect(notifications.onSuccess).toHaveBeenCalledWith('Passkey revoked');
  });

  it('revokes every passkey in one action', () => {
    setup({ admin: true, passkeys: [PASSKEY_A, PASSKEY_B] });

    findButton(passkeyCard()!, 'Revoke all')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(adminUserService.revokeAllPasskeys$).toHaveBeenCalledWith(42);
    expect(passkeyCard()!.textContent).toContain('No passkeys registered.');
    expect(notifications.onSuccess).toHaveBeenCalledWith('All passkeys revoked');
  });
});
