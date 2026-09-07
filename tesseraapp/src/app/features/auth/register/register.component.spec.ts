import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { RegisterComponent } from './register.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { TRANSLOCO_TESTING_IMPORTS } from '../../../testing/transloco-testing';
import { environment } from '../../../../environments/environment';

/**
 * Specs for the Cloudflare Turnstile widget lifecycle in {@link RegisterComponent}
 * (FUTURE-ENHANCEMENTS.md §3.1). `environment.turnstileSiteKey` is mutated directly rather than
 * via `vi.mock` — the environment module exports a single shared object, and the component reads
 * `this.turnstileSiteKey` once at field-initialization time, so flipping the property before
 * `TestBed.createComponent` is enough to steer each test down the configured/unconfigured branch.
 *
 * <p>Registration's own loading/success/error state machine (identical to every other
 * NgForm-driven auth screen in this app) is left untested here, matching the project's existing
 * coverage boundary (see {@code login.component.spec.ts}'s doc) — this file's only job is the
 * widget wiring that {@link UserController}'s CAPTCHA gate now depends on.
 */
describe('RegisterComponent — Turnstile CAPTCHA widget', () => {
  let fixture: ComponentFixture<RegisterComponent>;
  let userService: { register$: ReturnType<typeof vi.fn> };
  let notifications: { onSuccess: ReturnType<typeof vi.fn>; onError: ReturnType<typeof vi.fn> };
  let turnstile: { render: ReturnType<typeof vi.fn>; reset: ReturnType<typeof vi.fn>; remove: ReturnType<typeof vi.fn> };
  let originalSiteKey: string;
  let originalTurnstile: Window['turnstile'];

  const setup = (): void => {
    userService = { register$: vi.fn().mockReturnValue(of({ message: 'Check your inbox' })) };
    notifications = { onSuccess: vi.fn(), onError: vi.fn() };

    TestBed.configureTestingModule({
      imports: [RegisterComponent, ...TRANSLOCO_TESTING_IMPORTS],
      providers: [
        provideRouter([]),
        { provide: UserService, useValue: userService },
        { provide: NotificationsService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(RegisterComponent);
  };

  const submit = (): void => {
    const form = (fixture.nativeElement as HTMLElement).querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
    originalSiteKey = environment.turnstileSiteKey;
    originalTurnstile = window.turnstile;
  });

  afterEach(() => {
    environment.turnstileSiteKey = originalSiteKey;
    window.turnstile = originalTurnstile;
    vi.clearAllMocks();
  });

  it('unconfigured (no site key): renders no container and never touches window.turnstile', () => {
    environment.turnstileSiteKey = '';
    window.turnstile = undefined;
    setup();

    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="turnstile-container"]')).toBeNull();
  });

  it('unconfigured: submitting registers with no captcha token', () => {
    environment.turnstileSiteKey = '';
    window.turnstile = undefined;
    setup();
    fixture.detectChanges();

    submit();

    expect(userService.register$).toHaveBeenCalledWith(expect.anything(), undefined);
  });

  it('configured: renders the widget into the container once the view initializes', () => {
    environment.turnstileSiteKey = 'test-site-key';
    turnstile = { render: vi.fn().mockReturnValue('widget-1'), reset: vi.fn(), remove: vi.fn() };
    window.turnstile = turnstile as unknown as Window['turnstile'];
    setup();

    fixture.detectChanges();

    expect(turnstile.render).toHaveBeenCalledTimes(1);
    const [container, options] = turnstile.render.mock.calls[0] as [HTMLElement, { sitekey: string; callback: (token: string) => void }];
    expect(container).toBeInstanceOf(HTMLElement);
    expect(options.sitekey).toBe('test-site-key');
  });

  it('configured: the token the widget produces is attached to the next submit', () => {
    environment.turnstileSiteKey = 'test-site-key';
    turnstile = { render: vi.fn().mockReturnValue('widget-1'), reset: vi.fn(), remove: vi.fn() };
    window.turnstile = turnstile as unknown as Window['turnstile'];
    setup();
    fixture.detectChanges();
    const options = turnstile.render.mock.calls[0][1] as { callback: (token: string) => void };

    options.callback('solved-token');
    submit();

    expect(userService.register$).toHaveBeenCalledWith(expect.anything(), 'solved-token');
  });

  it('configured: a failed submit resets the widget so a stale single-use token cannot be resubmitted', () => {
    environment.turnstileSiteKey = 'test-site-key';
    turnstile = { render: vi.fn().mockReturnValue('widget-1'), reset: vi.fn(), remove: vi.fn() };
    window.turnstile = turnstile as unknown as Window['turnstile'];
    setup();
    userService.register$.mockReturnValue(throwError(() => 'CAPTCHA verification failed. Please try again.'));
    fixture.detectChanges();
    const options = turnstile.render.mock.calls[0][1] as { callback: (token: string) => void };
    options.callback('solved-token');

    submit();

    expect(turnstile.reset).toHaveBeenCalledWith('widget-1');
    expect(notifications.onError).toHaveBeenCalledWith('CAPTCHA verification failed. Please try again.');
  });

  it('configured: destroying the component removes the widget', () => {
    environment.turnstileSiteKey = 'test-site-key';
    turnstile = { render: vi.fn().mockReturnValue('widget-1'), reset: vi.fn(), remove: vi.fn() };
    window.turnstile = turnstile as unknown as Window['turnstile'];
    setup();
    fixture.detectChanges();

    fixture.destroy();

    expect(turnstile.remove).toHaveBeenCalledWith('widget-1');
  });
});
