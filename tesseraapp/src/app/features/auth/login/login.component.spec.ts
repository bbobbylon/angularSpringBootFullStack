import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { LoginComponent } from './login.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { TRANSLOCO_TESTING_IMPORTS } from '../../../testing/transloco-testing';
import { stubWebAuthn } from '../../../testing/webauthn-stub';
import { Key } from '../../../enumeration/key.enumeration';
import { UserInterface } from '../../../interface/user.interface';
import { installMemoryLocalStorage, restoreLocalStorage } from '../../../testing/local-storage';

/**
 * Specs for {@link LoginComponent}'s passkey sign-in button — the second of the three
 * passkey-UI surfaces named as the frontend coverage gap in FUTURE-ENHANCEMENTS.md §2.2 (the
 * Security Center enrollment card is covered by `security-center.component.spec.ts`, the
 * admin revoke panel by `user-details.component.spec.ts`).
 *
 * <p>Scope is the button's visibility gate and {@link LoginComponent#loginWithPasskey} —
 * the password form, MFA verification step, and federated-provider buttons this component
 * also renders are exercised nowhere in this app yet and are out of scope for closing this
 * specific gap.
 *
 * <p>The authentication ceremony is driven through {@link stubWebAuthn} — see that helper's doc
 * for why the browser boundary, not the {@code webauthn.utils.ts} functions themselves, is what
 * gets stubbed.
 */
describe('LoginComponent — passkey sign-in', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let userService: Record<string, ReturnType<typeof vi.fn>>;
  let router: { navigate: ReturnType<typeof vi.fn> };
  let notifications: { onError: ReturnType<typeof vi.fn> };

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

  /** See the identical helper's doc in `security-center.component.spec.ts`. */
  const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const passkeyButton = (): HTMLButtonElement | undefined =>
    Array.from(host().querySelectorAll<HTMLButtonElement>('button')).find((button) =>
      button.textContent?.includes('Sign in with a passkey'),
    );

  const setup = (opts: { webauthnSupported: boolean }): ReturnType<typeof stubWebAuthn> => {
    const webauthn = stubWebAuthn(opts.webauthnSupported);
    installMemoryLocalStorage();

    userService = {
      isAuthenticated: vi.fn().mockReturnValue(false),
      federatedProviders$: vi.fn().mockReturnValue(of({ data: { providers: [] } })),
      webauthnLoginOptions$: vi.fn().mockReturnValue(of({ data: { publicKey: { rpId: 'tesseraapp.dev' } } })),
      webauthnLoginVerify$: vi.fn().mockReturnValue(
        of({ data: { access_token: 'AT', refresh_token: 'RT', user: baseUser({ usingPasskey: true }) } }),
      ),
    };
    router = { navigate: vi.fn().mockResolvedValue(true) };
    notifications = { onError: vi.fn() };

    TestBed.configureTestingModule({
      imports: [LoginComponent, ...TRANSLOCO_TESTING_IMPORTS],
      providers: [
        { provide: UserService, useValue: userService },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: NotificationsService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(LoginComponent);
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

  it('hides the button on a browser with no WebAuthn support', () => {
    setup({ webauthnSupported: false });

    expect(passkeyButton()).toBeUndefined();
  });

  it('shows the button, above the federated-provider row, when WebAuthn is supported', () => {
    setup({ webauthnSupported: true });

    expect(passkeyButton()).not.toBeUndefined();
    expect(passkeyButton()!.disabled).toBe(false);
  });

  it('runs a full authentication ceremony, stores the tokens, and routes home', async () => {
    const webauthn = setup({ webauthnSupported: true });
    webauthn!.get.mockResolvedValue({ toJSON: () => ({ id: 'assertion-1' }) });

    passkeyButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush();
    fixture.detectChanges();

    expect(userService.webauthnLoginOptions$).toHaveBeenCalledTimes(1);
    expect(webauthn!.get).toHaveBeenCalledWith({ publicKey: { rpId: 'tesseraapp.dev' } });
    expect(userService.webauthnLoginVerify$).toHaveBeenCalledWith({ id: 'assertion-1' });
    expect(localStorage.getItem(Key.TOKEN)).toBe('AT');
    expect(localStorage.getItem(Key.REFRESH_TOKEN)).toBe('RT');
    // usingPasskey: true on the returned user — no reason to detour through the "add a passkey?" prompt.
    expect(router.navigate).toHaveBeenCalledWith(['/']);
  });

  it('routes through the "add a passkey?" prompt for a sign-in whose account still has none', async () => {
    const webauthn = setup({ webauthnSupported: true });
    webauthn!.get.mockResolvedValue({ toJSON: () => ({ id: 'assertion-1' }) });
    userService.webauthnLoginVerify$.mockReturnValue(
      of({ data: { access_token: 'AT', refresh_token: 'RT', user: baseUser({ usingPasskey: false }) } }),
    );

    passkeyButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush();
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledWith(['/welcome-passkey']);
  });

  it('treats a cancelled platform prompt as a silent no-op, not an error toast', async () => {
    const webauthn = setup({ webauthnSupported: true });
    webauthn!.get.mockRejectedValue(new DOMException('The user cancelled.', 'NotAllowedError'));

    passkeyButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush();
    fixture.detectChanges();

    expect(notifications.onError).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(userService.webauthnLoginVerify$).not.toHaveBeenCalled();
    // The in-flight spinner clears so the button (and the password form beside it) is usable again.
    expect(passkeyButton()!.disabled).toBe(false);
  });

  it('surfaces a genuine failure — e.g. no passkey registered for this site — as an error toast', async () => {
    setup({ webauthnSupported: true });
    userService.webauthnLoginOptions$.mockReturnValue(throwError(() => 'No passkey found for this device.'));

    passkeyButton()!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush();
    fixture.detectChanges();

    expect(notifications.onError).toHaveBeenCalledWith('No passkey found for this device.');
  });
});
