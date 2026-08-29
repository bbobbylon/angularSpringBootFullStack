import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { SecurityCenterComponent } from './security-center.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoService } from '@jsverse/transloco';
import { translocoStub } from '../../../testing/transloco-stub';
import { PasskeyInterface } from '../../../interface/security.interface';
import { UserInterface } from '../../../interface/user.interface';
import * as webauthnUtils from '../../../utils/webauthn.utils';

// Only `startRegistration` is swapped for a spy — `isWebAuthnSupported` stays real so these
// specs prove the panel reacts to `window.PublicKeyCredential` the same way production does,
// and a real ceremony can never run under jsdom anyway (no platform authenticator exists).
vi.mock('../../../utils/webauthn.utils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../utils/webauthn.utils')>();
  return { ...actual, startRegistration: vi.fn() };
});

/**
 * Specs for {@link SecurityCenterComponent}'s Passkeys card — the one named frontend
 * coverage gap in FUTURE-ENHANCEMENTS.md §2.2 (both backend halves, {@code PasskeyServiceImpl}
 * and {@code PasskeyController}, already have specs; this component never did).
 *
 * <p>Scope is deliberately narrow: this file drives only the passkey enrollment card
 * (visibility gating on {@link webauthnUtils.isWebAuthnSupported}, the inline "name this
 * passkey" form, the registration ceremony, and removal) rather than the whole page — the
 * MFA/sessions/providers/events panels this component also owns are unrelated to the gap
 * being closed and would only dilute what a failure here points at.
 *
 * <p>The registration ceremony itself (`navigator.credentials.create()`) cannot run under
 * jsdom — there is no platform authenticator — so {@link webauthnUtils.startRegistration} is
 * mocked at the module level above rather than driven for real; everything around it
 * (options fetch, completion POST, list/notification updates, the cancelled-prompt path) runs
 * through the real component code.
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

  /** The Passkeys `.card`, or undefined when the panel is hidden (`webauthnSupported === false`). */
  const passkeyCard = (): HTMLElement | undefined =>
    Array.from(host().querySelectorAll<HTMLElement>('.card')).find((card) => card.textContent?.includes('Passkeys'));

  const passkeyRows = (): HTMLElement[] => Array.from(passkeyCard()?.querySelectorAll<HTMLElement>('tbody tr') ?? []);

  const findButton = (root: HTMLElement, text: string): HTMLButtonElement | undefined =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((button) => button.textContent?.trim().includes(text));

  const setup = (opts: { webauthnSupported: boolean; passkeys?: PasskeyInterface[] }): void => {
    vi.stubGlobal('PublicKeyCredential', opts.webauthnSupported ? function PublicKeyCredential() {} : undefined);

    userService = {
      profile$: vi.fn().mockReturnValue(of({ data: { user: baseUser() } })),
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
      imports: [SecurityCenterComponent],
      providers: [
        { provide: UserService, useValue: userService },
        { provide: NotificationsService, useValue: notifications },
        { provide: TranslocoService, useValue: translocoStub() },
      ],
    });

    fixture = TestBed.createComponent(SecurityCenterComponent);
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
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
    setup({ webauthnSupported: true, passkeys: [] });

    findButton(passkeyCard()!, 'Add a passkey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).not.toBeNull();

    findButton(passkeyCard()!, 'common.cancel')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).toBeNull();
    expect(userService.webauthnEnrollOptions$).not.toHaveBeenCalled();
  });

  it('runs a full registration ceremony on submit and appends the new passkey to the list', async () => {
    setup({ webauthnSupported: true, passkeys: [] });
    vi.mocked(webauthnUtils.startRegistration).mockResolvedValue({ id: 'cred-1' });

    findButton(passkeyCard()!, 'Add a passkey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    const nameInput = passkeyCard()!.querySelector<HTMLInputElement>('#passkeyDeviceName')!;
    nameInput.value = 'MacBook Touch ID';
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    passkeyCard()!.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    await flush();
    fixture.detectChanges();

    expect(userService.webauthnEnrollOptions$).toHaveBeenCalledTimes(1);
    expect(webauthnUtils.startRegistration).toHaveBeenCalledWith({ rp: { id: 'tesseraapp.dev' } });
    expect(userService.webauthnEnrollComplete$).toHaveBeenCalledWith('MacBook Touch ID', { id: 'cred-1' });
    // The form closes and the new passkey (from the mocked enroll-complete response) is on screen.
    expect(passkeyCard()!.querySelector('#passkeyDeviceName')).toBeNull();
    expect(passkeyRows()).toHaveLength(1);
    expect(notifications.onSuccess).toHaveBeenCalledWith('toasts.passkeyAdded');
  });

  it('treats a cancelled platform prompt as a quiet notice, not a hard error, and leaves the form open', async () => {
    setup({ webauthnSupported: true, passkeys: [] });
    vi.mocked(webauthnUtils.startRegistration).mockRejectedValue(new DOMException('The user cancelled.', 'NotAllowedError'));

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

  it('removes a passkey and refreshes the list from the response', async () => {
    setup({ webauthnSupported: true, passkeys: [PASSKEY] });

    findButton(passkeyRows()[0], 'common.delete')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(userService.webauthnDelete$).toHaveBeenCalledWith(7);
    expect(passkeyCard()!.textContent).toContain('No passkeys registered yet.');
    expect(notifications.onSuccess).toHaveBeenCalledWith('toasts.passkeyRemoved');
  });
});
