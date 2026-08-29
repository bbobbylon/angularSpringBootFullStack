import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { SecurityCenterComponent } from './security-center.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { TRANSLOCO_TESTING_IMPORTS } from '../../../testing/transloco-testing';
import { stubWebAuthn } from '../../../testing/webauthn-stub';
import { installMemoryLocalStorage, restoreLocalStorage } from '../../../testing/local-storage';
import { PasskeyInterface } from '../../../interface/security.interface';
import { UserInterface } from '../../../interface/user.interface';

/**
 * Specs for {@link SecurityCenterComponent}'s Passkeys card — the one named frontend
 * coverage gap in FUTURE-ENHANCEMENTS.md §2.2 (both backend halves, {@code PasskeyServiceImpl}
 * and {@code PasskeyController}, already have specs; this component never did).
 *
 * <p>Scope is deliberately narrow: this file drives only the passkey enrollment card
 * (visibility gating on WebAuthn support, the inline "name this passkey" form, the registration
 * ceremony, and removal) rather than the whole page — the MFA/sessions/providers/events panels
 * this component also owns are unrelated to the gap being closed and would only dilute what a
 * failure here points at.
 *
 * <p>The registration ceremony is driven through {@link stubWebAuthn} — see that helper's doc for
 * why a real {@code navigator.credentials.create()} cannot run under jsdom and why the browser
 * boundary, not the {@code webauthn.utils.ts} functions themselves, is what gets stubbed.
 */
describe('SecurityCenterComponent — Passkeys card', () => {
  let fixture: ComponentFixture<SecurityCenterComponent>;
  let userService: Record<string, ReturnType<typeof vi.fn>>;
  let notifications: { onSuccess: ReturnType<typeof vi.fn>; onError: ReturnType<typeof vi.fn> };

  const baseUser = (overrides: Partial<UserInterface> = {}): UserInterface => ({
    id: 1,
    username: 'jane@example.com',
    email: 'jane@example.com',
    phoneNumber: '',
    enabled: true,
    notLocked: true,
    using2FA: false,
    usingTotp: false,
    usingPasskey: false,
    createdAt: new Date('2026-01-01T00:00:00Z'),
    roleName: 'ROLE_USER',
    permissions: '',
    ...overrides,
  });

  const PASSKEY: PasskeyInterface = {
    id: 7,
    deviceName: 'MacBook Touch ID',
    transports: 'internal',
    createdAt: new Date('2026-01-01T00:00:00Z'),
    lastUsedAt: undefined,
  };

  /** Flushes the promise chain inside the async ceremony methods (there is no timer to fake — the
   *  mocked observables/promises resolve immediately, so a single macrotask tick is enough to let
   *  every already-queued microtask, including chained `await`s, run before assertions). */
  const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  /** The Passkeys `.card`, or undefined when the panel is hidden (no WebAuthn support). */
  const passkeyCard = (): HTMLElement | undefined =>
    Array.from(host().querySelectorAll<HTMLElement>('.card')).find((card) => card.textContent?.includes('Passkeys'));

  const passkeyRows = (): HTMLElement[] => Array.from(passkeyCard()?.querySelectorAll<HTMLElement>('tbody tr') ?? []);

  const findButton = (root: HTMLElement, text: string): HTMLButtonElement | undefined =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((button) => button.textContent?.trim().includes(text));

  const setup = (opts: { webauthnSupported: boolean; passkeys?: PasskeyInterface[] }): ReturnType<typeof stubWebAuthn> => {
    const webauthn = stubWebAuthn(opts.webauthnSupported);
    // The page mounts <app-navbar>, whose CurrentUserService/ThemeService/LanguageService read
    // localStorage and call UserService#profile$()/#hasAnyAuthority() at construction — unrelated
    // to the Passkeys card, but required for the fixture to render at all.
    installMemoryLocalStorage();

    userService = {
      profile$: vi.fn().mockReturnValue(of({ data: { user: baseUser() } })),
      hasAnyAuthority: vi.fn().mockReturnValue(false),
      logOut: vi.fn(),
      totpStatus$: vi.fn().mockReturnValue(of({ data: { recoveryCodesRemaining: 0 } })),
      sessions$: vi.fn().mockReturnValue(of({ data: { sessions: [], currentFamily: '' } })),
      webauthnList$: vi.fn().mockReturnValue(of({ data: { passkeys: opts.passkeys ?? [] } })),
      userEvents$: vi.fn().mockReturnValue(of({ data: { events: [], eventsTotalPages: 0 } })),
      connectedProviders$: vi.fn().mockReturnValue(of({ data: { providers: [] } })),
      federatedProviders$: vi.fn().mockReturnValue(of({ data: { providers: [] } })),
      webauthnEnrollOptions$: vi.fn().mockReturnValue(of({ data: { publicKey: { rp: { id: 'tesseraapp.dev' } } } })),
      webauthnEnrollComplete$: vi.fn().mockReturnValue(of({ data: { passkeys: [PASSKEY], user: baseUser({ usingPasskey: true }) } })),
      webauthnDelete$: vi.fn().mockReturnValue(of({ data: { passkeys: [] } })),
    };
    notifications = { onSuccess: vi.fn(), onError: vi.fn() };

    TestBed.configureTestingModule({
      imports: [SecurityCenterComponent, ...TRANSLOCO_TESTING_IMPORTS],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        { provide: UserService, useValue: userService },
        { provide: NotificationsService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(SecurityCenterComponent);
    fixture.detectChanges();
    return webauthn;
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    restoreLocalStorage();
  });

  it('hides the panel entirely on a browser with no WebAuthn support', () => {
    setup({ webauthnSupported: false });

    expect(passkeyCard()).toBeUndefined();
    // Fetching the list would be wasted work (and a misleading network call) for a browser
    // that can never register or use a passkey.
    expect(userService.webauthnList$).not.toHaveBeenCalled();
  });

  it('shows an empty state when supported but the account has no passkeys yet', () => {
    setup({ webauthnSupported: true, passkeys: [] });

    expect(passkeyCard()).not.toBeUndefined();
    expect(passkeyCard()!.textContent).toContain('No passkeys registered yet.');
  });

  it('renders each registered passkey with its device name', () => {
    setup({ webauthnSupported: true, passkeys: [PASSKEY] });

    expect(passkeyRows()).toHaveLength(1);
    expect(passkeyRows()[0].textContent).toContain('MacBook Touch ID');
  });

  it('opens and cancels the inline add-passkey form without starting a ceremony', () => {
    const webauthn = setup({ webauthnSupported: true, passkeys: [] });

    findButton(passkeyCard()!, 'Add a passkey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).not.toBeNull();

    findButton(passkeyCard()!, 'common.cancel')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).toBeNull();
    expect(userService.webauthnEnrollOptions$).not.toHaveBeenCalled();
    expect(webauthn!.create).not.toHaveBeenCalled();
  });

  it('runs a full registration ceremony on submit and appends the new passkey to the list', async () => {
    const webauthn = setup({ webauthnSupported: true, passkeys: [] });
    webauthn!.create.mockResolvedValue({ toJSON: () => ({ id: 'cred-1' }) });

    findButton(passkeyCard()!, 'Add a passkey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    // NgModel defers registering its control with the parent NgForm to a microtask (to dodge
    // "expression changed after checked"), so the freshly-opened form's control tree is still
    // empty immediately after this detectChanges() — a tick has to pass before typing into it
    // means anything to `nameForm.value`.
    await flush();
    const nameInput = passkeyCard()!.querySelector<HTMLInputElement>('#passkeyDeviceName')!;
    nameInput.value = 'MacBook Touch ID';
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    passkeyCard()!.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    await flush();
    fixture.detectChanges();

    expect(userService.webauthnEnrollOptions$).toHaveBeenCalledTimes(1);
    expect(webauthn!.create).toHaveBeenCalledWith({ publicKey: { rp: { id: 'tesseraapp.dev' } } });
    expect(userService.webauthnEnrollComplete$).toHaveBeenCalledWith('MacBook Touch ID', { id: 'cred-1' });
    // The form closes and the new passkey (from the mocked enroll-complete response) is on screen.
    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).toBeNull();
    expect(passkeyRows()).toHaveLength(1);
    expect(notifications.onSuccess).toHaveBeenCalledWith('toasts.passkeyAdded');
  });

  it('treats a cancelled platform prompt as a quiet notice, not a hard error, and leaves the form open', async () => {
    const webauthn = setup({ webauthnSupported: true, passkeys: [] });
    webauthn!.create.mockRejectedValue(new DOMException('The user cancelled.', 'NotAllowedError'));

    findButton(passkeyCard()!, 'Add a passkey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    passkeyCard()!.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    await flush();
    fixture.detectChanges();

    expect(notifications.onError).toHaveBeenCalledWith('toasts.passkeyCancelled');
    expect(userService.webauthnEnrollComplete$).not.toHaveBeenCalled();
    // Unlike a successful ceremony, cancelling never closes the form — the user may want to retry.
    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).not.toBeNull();
  });

  it('removes a passkey and refreshes the list from the response', () => {
    setup({ webauthnSupported: true, passkeys: [PASSKEY] });

    findButton(passkeyRows()[0], 'delete')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(userService.webauthnDelete$).toHaveBeenCalledWith(7);
    expect(passkeyCard()!.textContent).toContain('No passkeys registered yet.');
    expect(notifications.onSuccess).toHaveBeenCalledWith('toasts.passkeyRemoved');
  });
});
