import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { ServiceAccountsComponent } from './service-accounts.component';
import { AdminServiceAccountService } from '../../service/admin-service-account.service';
import { UserService } from '../../service/user.service';
import { NotificationsService } from '../../service/notifications-service';
import { TRANSLOCO_TESTING_IMPORTS } from '../../testing/transloco-testing';
import { installMemoryLocalStorage, restoreLocalStorage } from '../../testing/local-storage';
import { UserInterface } from '../../interface/user.interface';
import { ApiKeyInterface } from '../../interface/apikey.interface';
import { OAuthClientInterface } from '../../interface/oauthclient.interface';
import { RolesInterface } from '../../interface/roles.interface';

/**
 * Specs for {@link ServiceAccountsComponent} — the admin catalog for the machine-account side of
 * user administration (FUTURE-ENHANCEMENTS.md §3.1 "P2-3 — Machine-to-machine API access", both
 * Option A/API keys and Option B/OAuth2 client credentials), closing the frontend coverage gap
 * tracked in [[project_api_key_service_accounts]].
 *
 * <p>Covers the flows the class Javadoc documents as deliberate design choices: create-then-open
 * (a fresh account's credentials panel opens with the issue form already showing), the one-time
 * raw-key/client-secret reveal, and the no-confirmation-dialog deactivate/revoke actions — plus
 * {@link accountLabel}'s first+last-name-with-email-fallback rule, which silently truncates a
 * multi-word name if broken.
 */
describe('ServiceAccountsComponent', () => {
  let fixture: ComponentFixture<ServiceAccountsComponent>;
  let accountsService: Record<string, ReturnType<typeof vi.fn>>;
  let userService: Record<string, ReturnType<typeof vi.fn>>;
  let notifications: { onSuccess: ReturnType<typeof vi.fn>; onError: ReturnType<typeof vi.fn> };

  const baseAccount = (overrides: Partial<UserInterface> = {}): UserInterface => ({
    id: 34,
    username: 'ci-pipeline@tessera.dev',
    email: 'ci-pipeline@tessera.dev',
    firstName: 'CI',
    lastName: 'pipeline',
    phoneNumber: '',
    enabled: true,
    notLocked: true,
    using2FA: false,
    usingTotp: false,
    usingPasskey: false,
    createdAt: new Date('2026-01-01T00:00:00Z'),
    roleName: 'ROLE_MODERATOR',
    permissions: '',
    ...overrides,
  });

  const KEY_A: ApiKeyInterface = {
    id: 1,
    userId: 34,
    name: 'GitHub Actions',
    keyPrefix: 'tsk_abcd',
    createdAt: new Date('2026-01-01T00:00:00Z'),
    revoked: false,
  };

  const CLIENT_A: OAuthClientInterface = {
    id: 1,
    userId: 34,
    clientId: 'tsc_abc123',
    name: 'CI pipeline',
    createdAt: new Date('2026-01-01T00:00:00Z'),
    revoked: false,
  };

  const ROLE: RolesInterface = { id: 2, name: 'ROLE_MODERATOR', permission: 'UPDATE:USER', assignable: true };

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  /** Top-level account rows only — excludes the nested credentials-panel row Angular renders as a sibling `<tr>`. */
  const accountRows = (): HTMLElement[] =>
    Array.from(host().querySelectorAll<HTMLElement>('.sc-svcacct__table tbody > tr')).filter(
      (row) => !row.classList.contains('sc-svcacct__keyrow'),
    );

  const keyRows = (): HTMLElement[] => Array.from(host().querySelectorAll<HTMLElement>('.sc-svcacct__apikeytable tbody tr'));
  const clientRows = (): HTMLElement[] => Array.from(host().querySelectorAll<HTMLElement>('.sc-svcacct__oauthtable tbody tr'));

  const findButton = (root: HTMLElement, text: string): HTMLButtonElement | undefined =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((button) => button.textContent?.includes(text));

  // NgModel defers registering its control with the parent NgForm to a microtask (to dodge
  // "expression changed after checked"), so a freshly-opened form's control tree is still empty
  // immediately after the detectChanges() that reveals it — a tick has to pass before typing into
  // it means anything to the form's value.
  const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  const setup = (opts: { accounts?: UserInterface[]; roles?: RolesInterface[] } = {}): void => {
    installMemoryLocalStorage();

    accountsService = {
      list$: vi.fn().mockReturnValue(of({ data: { serviceAccounts: opts.accounts ?? [] } })),
      create$: vi.fn(),
      deactivate$: vi.fn(),
      listApiKeys$: vi.fn().mockReturnValue(of({ data: { apiKeys: [] } })),
      issueApiKey$: vi.fn(),
      revokeApiKey$: vi.fn(),
      listOAuthClients$: vi.fn().mockReturnValue(of({ data: { oauthClients: [] } })),
      registerOAuthClient$: vi.fn(),
      revokeOAuthClient$: vi.fn(),
    };
    userService = {
      hasAnyAuthority: vi.fn().mockReturnValue(true),
      profile$: vi.fn().mockReturnValue(of({ data: { user: baseAccount({ id: 1 }), roles: opts.roles ?? [ROLE] } })),
      logOut: vi.fn(),
    };
    notifications = { onSuccess: vi.fn(), onError: vi.fn() };

    TestBed.configureTestingModule({
      imports: [ServiceAccountsComponent, ...TRANSLOCO_TESTING_IMPORTS],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        { provide: AdminServiceAccountService, useValue: accountsService },
        { provide: UserService, useValue: userService },
        { provide: NotificationsService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(ServiceAccountsComponent);
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  afterEach(() => {
    vi.clearAllMocks();
    restoreLocalStorage();
  });

  it('shows the empty state when there are no service accounts', () => {
    setup({ accounts: [] });

    expect(host().querySelector('.sc-svcacct__empty')).not.toBeNull();
    expect(accountRows()).toHaveLength(0);
  });

  it('labels a row by first+last name, falling back to the email when both are blank', () => {
    setup({
      accounts: [baseAccount(), baseAccount({ id: 35, firstName: '', lastName: '', email: 'bare@tessera.dev' })],
    });

    expect(accountRows()[0].textContent).toContain('CI pipeline');
    expect(accountRows()[1].textContent).toContain('bare@tessera.dev');
  });

  it('creates a service account, then opens its key panel with the issue form already showing', async () => {
    setup({ accounts: [] });
    accountsService.create$.mockReturnValue(
      of({ message: 'Service account created.', data: { serviceAccount: baseAccount() } }),
    );

    findButton(host(), 'addAccount')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    await flush();

    const nameInput = host().querySelector<HTMLInputElement>('#create-name')!;
    nameInput.value = 'CI pipeline';
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));

    const roleSelect = host().querySelector<HTMLSelectElement>('#create-role')!;
    const roleOption = Array.from(roleSelect.querySelectorAll('option')).find((option) => option.textContent?.trim() === 'ROLE_MODERATOR')!;
    roleSelect.value = roleOption.value;
    roleSelect.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    // list$ reloads after creation — the account now exists on the next fetch.
    accountsService.list$.mockReturnValue(of({ data: { serviceAccounts: [baseAccount()] } }));

    host().querySelector('.sc-svcacct__form form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await flush();
    fixture.detectChanges();

    expect(accountsService.create$).toHaveBeenCalledWith('CI pipeline', 'ROLE_MODERATOR');
    expect(notifications.onSuccess).toHaveBeenCalledWith('Service account created.');
    expect(host().querySelector('.sc-svcacct__form')).toBeNull();
    expect(accountRows()).toHaveLength(1);
    // Chains straight into the new account's credentials panel with the issue form already open,
    // per the class Javadoc — no separate click to find and expand the row is needed. Both
    // credential types load, even though only the API-key form is pre-opened.
    expect(accountsService.listApiKeys$).toHaveBeenCalledWith(34);
    expect(accountsService.listOAuthClients$).toHaveBeenCalledWith(34);
    expect(host().querySelector('#key-name')).not.toBeNull();
  });

  it('toggles a service account\'s credentials panel open and closed, reloading both credential types fresh on every open', () => {
    setup({ accounts: [baseAccount()] });

    findButton(accountRows()[0], 'manageCredentials')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(accountsService.listApiKeys$).toHaveBeenCalledWith(34);
    expect(accountsService.listOAuthClients$).toHaveBeenCalledWith(34);
    expect(host().querySelector('.sc-svcacct__keyrow')).not.toBeNull();
    expect(findButton(accountRows()[0], 'hideCredentials')).not.toBeUndefined();

    findButton(accountRows()[0], 'hideCredentials')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(host().querySelector('.sc-svcacct__keyrow')).toBeNull();
    expect(findButton(accountRows()[0], 'manageCredentials')).not.toBeUndefined();
  });

  it('issues a key, reveals the raw value exactly once, and clears it for good on dismiss', async () => {
    setup({ accounts: [baseAccount()] });

    findButton(accountRows()[0], 'manageCredentials')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    findButton(host(), 'issueKey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    await flush();

    const keyNameInput = host().querySelector<HTMLInputElement>('#key-name')!;
    keyNameInput.value = 'GitHub Actions';
    keyNameInput.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    accountsService.issueApiKey$.mockReturnValue(of({ data: { rawKey: 'tsk_raw123', apiKeys: [KEY_A] } }));
    host().querySelector('.sc-svcacct__keyrow form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();

    expect(accountsService.issueApiKey$).toHaveBeenCalledWith(34, 'GitHub Actions', undefined);
    expect(host().querySelector('.sc-svcacct__reveal')?.textContent).toContain('tsk_raw123');

    findButton(host(), 'revealDismiss')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(host().querySelector('.sc-svcacct__reveal')).toBeNull();
    expect(keyRows()).toHaveLength(1);
    expect(keyRows()[0].textContent).toContain('GitHub Actions');
  });

  it('revokes an API key and drops its revoke control once revoked', () => {
    setup({ accounts: [baseAccount()] });
    accountsService.listApiKeys$.mockReturnValue(of({ data: { apiKeys: [KEY_A] } }));

    findButton(accountRows()[0], 'manageCredentials')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    accountsService.revokeApiKey$.mockReturnValue(
      of({ message: 'API key revoked.', data: { apiKeys: [{ ...KEY_A, revoked: true }] } }),
    );
    findButton(keyRows()[0], 'revokeKey')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(accountsService.revokeApiKey$).toHaveBeenCalledWith(34, 1);
    expect(notifications.onSuccess).toHaveBeenCalledWith('API key revoked.');
    expect(findButton(keyRows()[0], 'revokeKey')).toBeUndefined();
  });

  it('registers an OAuth client, reveals the client id/secret exactly once, and clears them for good on dismiss', async () => {
    setup({ accounts: [baseAccount()] });

    findButton(accountRows()[0], 'manageCredentials')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    findButton(host(), 'registerClient')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    await flush();

    const clientNameInput = host().querySelector<HTMLInputElement>('#client-name')!;
    clientNameInput.value = 'CI pipeline';
    clientNameInput.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    accountsService.registerOAuthClient$.mockReturnValue(
      of({ data: { clientId: 'tsc_abc123', rawClientSecret: 'tss_raw456', oauthClients: [CLIENT_A] } }),
    );
    host().querySelector('.sc-svcacct__keyrow form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();

    expect(accountsService.registerOAuthClient$).toHaveBeenCalledWith(34, 'CI pipeline');
    expect(host().querySelector('.sc-svcacct__reveal')?.textContent).toContain('tsc_abc123');
    expect(host().querySelector('.sc-svcacct__reveal')?.textContent).toContain('tss_raw456');

    findButton(host(), 'oauthRevealDismiss')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(host().querySelector('.sc-svcacct__reveal')).toBeNull();
    expect(clientRows()).toHaveLength(1);
    expect(clientRows()[0].textContent).toContain('CI pipeline');
  });

  it('revokes an OAuth client and drops its revoke control once revoked', () => {
    setup({ accounts: [baseAccount()] });
    accountsService.listOAuthClients$.mockReturnValue(of({ data: { oauthClients: [CLIENT_A] } }));

    findButton(accountRows()[0], 'manageCredentials')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    accountsService.revokeOAuthClient$.mockReturnValue(
      of({ message: 'OAuth client revoked.', data: { oauthClients: [{ ...CLIENT_A, revoked: true }] } }),
    );
    findButton(clientRows()[0], 'revokeClient')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(accountsService.revokeOAuthClient$).toHaveBeenCalledWith(34, 1);
    expect(notifications.onSuccess).toHaveBeenCalledWith('OAuth client revoked.');
    expect(findButton(clientRows()[0], 'revokeClient')).toBeUndefined();
  });

  it('deactivates a service account with a single click and no confirmation dialog', () => {
    setup({ accounts: [baseAccount()] });
    accountsService.deactivate$.mockReturnValue(of({ message: 'Service account deactivated.', data: {} }));
    // deactivate() doesn't fold deactivate$'s own response into the list — it reloads via load(),
    // so the next list$() fetch is what needs to reflect the now-disabled account.
    accountsService.list$.mockReturnValue(of({ data: { serviceAccounts: [baseAccount({ enabled: false })] } }));

    findButton(accountRows()[0], 'deactivateAccount')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(accountsService.deactivate$).toHaveBeenCalledWith(34);
    expect(notifications.onSuccess).toHaveBeenCalledWith('Service account deactivated.');
    expect(accountRows()[0].classList.contains('is-deactivated')).toBe(true);
    expect(findButton(accountRows()[0], 'deactivateAccount')).toBeUndefined();
  });
});
