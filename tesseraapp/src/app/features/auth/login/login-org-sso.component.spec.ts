import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { LoginComponent } from './login.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { TRANSLOCO_TESTING_IMPORTS } from '../../../testing/transloco-testing';

/**
 * Specs for {@link LoginComponent}'s email-domain SSO discovery affordance
 * (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 2 frontend). This is a
 * lookup, not a redirect the component performs itself: on {@code found: true} the component
 * hands the resolved login URL to {@link UserService#initiateOrgSsoLogin}, which is where the
 * actual full-page navigation happens (mirroring {@code loginWithProvider}/
 * {@code initiateFederatedLogin}) — so these specs assert the delegation, not `window.location`.
 *
 * <p>Scope is {@link LoginComponent#lookupOrgSso} and the inline "not found" hint only. The
 * password form, MFA step, passkey button, and consumer federated-provider buttons are covered
 * (or intentionally left uncovered) elsewhere — see `login.component.spec.ts`'s doc comment.
 */
describe('LoginComponent — email-domain SSO discovery', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let userService: Record<string, ReturnType<typeof vi.fn>>;
  let notifications: { onError: ReturnType<typeof vi.fn> };

  /** See the identical helper's doc in `security-center.component.spec.ts`. */
  const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const emailInput = (): HTMLInputElement => host().querySelector<HTMLInputElement>('#orgSsoEmail')!;
  const orgSsoForm = (): HTMLFormElement => emailInput().closest('form')!;

  const typeEmail = async (value: string): Promise<void> => {
    emailInput().value = value;
    emailInput().dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    await flush();
  };

  const submit = async (): Promise<void> => {
    orgSsoForm().dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await flush();
    fixture.detectChanges();
  };

  const setup = (): void => {
    userService = {
      isAuthenticated: vi.fn().mockReturnValue(false),
      federatedProviders$: vi.fn().mockReturnValue(of({ data: { providers: [] } })),
      orgSsoLookup$: vi.fn(),
      initiateOrgSsoLogin: vi.fn(),
    };
    notifications = { onError: vi.fn() };

    TestBed.configureTestingModule({
      imports: [LoginComponent, ...TRANSLOCO_TESTING_IMPORTS],
      providers: [
        { provide: UserService, useValue: userService },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: NotificationsService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
    setup();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('hands the resolved login URL to the service on a found domain, without touching window.location itself', async () => {
    userService.orgSsoLookup$.mockReturnValue(
      of({ data: { found: true, organizationName: 'Acme Partners', displayName: 'Acme Okta', loginUrl: '/oauth2/authorization/org-oidc-42' } }),
    );

    await typeEmail('member@acme.com');
    await submit();

    expect(userService.orgSsoLookup$).toHaveBeenCalledWith('member@acme.com');
    expect(userService.initiateOrgSsoLogin).toHaveBeenCalledWith('/oauth2/authorization/org-oidc-42');
  });

  it('shows a neutral inline hint on an unclaimed domain, without any error toast', async () => {
    userService.orgSsoLookup$.mockReturnValue(of({ data: { found: false } }));

    await typeEmail('member@unknown.com');
    await submit();

    // TRANSLOCO_TESTING_IMPORTS resolves every key to itself (no en.json loaded) — see that
    // helper's doc — so the rendered hint is asserted by its i18n key, not translated copy.
    expect(userService.initiateOrgSsoLogin).not.toHaveBeenCalled();
    expect(notifications.onError).not.toHaveBeenCalled();
    expect(host().textContent).toContain('login.orgSsoNotFound');
  });

  it('clears a stale "not found" hint as soon as the email is edited again', async () => {
    userService.orgSsoLookup$.mockReturnValue(of({ data: { found: false } }));
    await typeEmail('member@unknown.com');
    await submit();
    expect(host().textContent).toContain('login.orgSsoNotFound');

    await typeEmail('member@unknown.com2');

    expect(host().textContent).not.toContain('login.orgSsoNotFound');
  });

  it('surfaces a genuine lookup failure as an error toast, not the neutral hint', async () => {
    userService.orgSsoLookup$.mockReturnValue(throwError(() => 'Server returned code: 500, error message is: boom'));

    await typeEmail('member@acme.com');
    await submit();

    expect(notifications.onError).toHaveBeenCalledWith('Server returned code: 500, error message is: boom');
    expect(host().textContent).not.toContain('login.orgSsoNotFound');
  });

  it('ignores a submit with no email typed yet', async () => {
    await submit();

    expect(userService.orgSsoLookup$).not.toHaveBeenCalled();
  });
});
