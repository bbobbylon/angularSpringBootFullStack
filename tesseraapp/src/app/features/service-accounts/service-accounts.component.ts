import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith } from 'rxjs';
import { TranslocoDirective } from '@jsverse/transloco';
import { NavbarComponent } from '../../shared/navbar/navbar.component';
import { AdminServiceAccountService } from '../../service/admin-service-account.service';
import { UserService } from '../../service/user.service';
import { NotificationsService } from '../../service/notifications-service';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { ServiceAccountsDataInterface } from '../../interface/serviceaccount.interface';
import { ApiKeyInterface } from '../../interface/apikey.interface';
import { UserInterface } from '../../interface/user.interface';
import { RolesInterface } from '../../interface/roles.interface';

/**
 * Administrative management of service accounts and their API keys — {@code /service-accounts}
 * (FUTURE-ENHANCEMENTS.md §3.1 "P2-3 — Machine-to-machine API access", Option A).
 *
 * <h3>Why this is a separate page from the Users directory</h3>
 * A service account is stored as an ordinary {@code users} row ({@code origin =
 * 'SERVICE_ACCOUNT'}) and already shows up in {@code UsersComponent}/{@code UserDetailsComponent}
 * badged accordingly — but those pages have no concept of an API key, which is a resource nested
 * under {@code AdminServiceAccountController}, not {@code AdminUserController}. Rather than bolt
 * key management onto the human-user detail page, this screen is the dedicated catalog for the
 * machine-account side of user administration, the same split {@code ServicesAdminComponent}'s own
 * Javadoc argues for: one audience (create an account, issue/revoke its keys) is not the audience
 * of the general Users directory (search, lock, reassign a human's role).
 *
 * <h3>Retire, don't rebuild reactivation</h3>
 * {@link deactivate} only ever disables an account ({@code enabled = false}); there is
 * deliberately no "reactivate" control here. A service account is still an ordinary row in the
 * Users directory, so {@code AdminUserService#updateAccountSettings$} — the exact control
 * {@code UserDetailsComponent} already exposes for every account — already re-enables it with zero
 * new code. Building a second path to the same flag here would be the "Assign Roles" duplicate
 * {@code navbar.component.html} explicitly removed, not a convenience.
 *
 * <h3>The raw key is shown exactly once</h3>
 * {@link revealedKey} holds a freshly issued key's plaintext only transiently, mirroring
 * {@code SecurityCenterComponent}'s TOTP recovery-code reveal: the backend's create response is
 * the only time the secret ever leaves the server, so the panel stays on screen until the admin
 * explicitly dismisses it, never auto-hides, and is never re-derivable from a later list fetch
 * ({@code ApiKeyDTO} never carries it).
 */
@Component({
  selector: 'app-service-accounts',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DatePipe, FormsModule, TranslocoDirective],
  templateUrl: './service-accounts.component.html',
  styleUrl: './service-accounts.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServiceAccountsComponent implements OnInit {
  readonly DataState = DataState;

  private readonly accounts = inject(AdminServiceAccountService);
  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<ServiceAccountsDataInterface>>>({
    dataState: DataState.LOADING,
  });

  protected readonly serviceAccounts = computed<UserInterface[]>(() => this.pageState().appData?.data?.serviceAccounts ?? []);

  /**
   * The role catalog, fetched once via the profile endpoint — the same "cheapest source that
   * already returns it" reasoning {@code RolesMatrixComponent#load} documents, since there is no
   * dedicated role-list endpoint. {@code assignable === false} disables an option the creating
   * admin could not actually assign; the real ceiling ({@code RoleType#canAssign}) is still
   * enforced server-side regardless of what this list offers.
   */
  protected readonly roles = signal<RolesInterface[]>([]);

  /** Whether the "create a service account" form is open. */
  protected readonly isCreating = signal(false);
  /** Blocks duplicate submissions while a service-account mutation is in flight. */
  protected readonly isSaving = signal(false);

  /** The service account whose API-key panel is currently open, or null when none is. */
  protected readonly expandedId = signal<number | null>(null);
  /** API keys for {@link expandedId}, reloaded fresh every time the panel opens. */
  protected readonly keysState = signal<GlobalStateInterface<ApiKeyInterface[]>>({ dataState: DataState.LOADING });
  /** Whether the "issue a new key" form is open inside the expanded panel. */
  protected readonly isIssuingKey = signal(false);
  /** Blocks duplicate submissions while a key mutation is in flight. */
  protected readonly isKeySaving = signal(false);
  /** The one-time plaintext reveal of a just-issued key — see the class Javadoc. */
  protected readonly revealedKey = signal<{ name: string; rawKey: string } | undefined>(undefined);

  ngOnInit(): void {
    this.load();
    this.userService
      .profile$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.roles.set(response.data?.roles ?? []),
        // Silent: this list only feeds the create form's role picker, which degrades to "no
        // options" rather than blocking the page a reader came here for.
        error: () => this.roles.set([]),
      });
  }

  /** Opens the create form. */
  protected startCreate(): void {
    this.isCreating.set(true);
  }

  /** Abandons the create form without saving. */
  protected cancelCreate(): void {
    this.isCreating.set(false);
  }

  /**
   * Creates a service account, then immediately opens its API-key panel with the "issue a key"
   * form already showing — a freshly created service account is not useful without a key, so this
   * chains straight into the next step instead of leaving the admin to find the new row and expand
   * it themselves.
   *
   * @param form - the submitted form carrying name and role
   */
  protected create(form: NgForm): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.accounts
      .create$(form.value.name, form.value.role)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.isCreating.set(false);
          form.resetForm();
          this.notification.onSuccess(response.message ?? 'Service account created.');
          this.load();
          const newId = response.data?.serviceAccount?.id;
          if (newId !== undefined) {
            this.expandedId.set(newId);
            this.loadKeys(newId);
            this.isIssuingKey.set(true);
          }
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Deactivates a service account. No confirmation prompt — same reasoning
   * {@code ServicesAdminComponent#toggleActive} and {@code UserDetailsComponent#revokeSessions}
   * document for their own single-click destructive actions: this is reversible (see class
   * Javadoc), so a dialog would only be asking the admin to confirm something one more click can
   * undo.
   *
   * @param account - the service account to deactivate
   */
  protected deactivate(account: UserInterface): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.accounts
      .deactivate$(account.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.notification.onSuccess(response.message ?? 'Service account deactivated.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Opens or closes a service account's API-key panel. Reloads fresh from the server on every
   * open rather than caching between visits, the same "a short list read once, extra round trip is
   * invisible" trade-off {@code ServicesAdminComponent}'s class Javadoc documents for the whole
   * catalog.
   *
   * @param account - the row being toggled
   */
  protected toggleExpand(account: UserInterface): void {
    if (this.expandedId() === account.id) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(account.id);
    this.isIssuingKey.set(false);
    this.revealedKey.set(undefined);
    this.loadKeys(account.id);
  }

  /** Opens the "issue a new key" form inside the currently expanded panel. */
  protected startIssueKey(): void {
    this.isIssuingKey.set(true);
  }

  /** Abandons the issue-key form without saving. */
  protected cancelIssueKey(): void {
    this.isIssuingKey.set(false);
  }

  /**
   * Issues a new API key for the expanded service account.
   *
   * <p>The optional expiry input is a plain date picker, not a date-and-time one — matching how
   * {@code AdminUserService#updateUserRole$}'s role-expiry field works — so a chosen date is sent
   * as end-of-that-day ({@code T23:59:59}), the same "auto-reverts at end of that calendar day"
   * semantics {@code AdminUserService}'s own Javadoc describes for role expiry, rather than asking
   * an admin to also pick a meaningless time-of-day.
   *
   * @param accountId - the service account the key belongs to
   * @param form      - the submitted form carrying name and an optional expiry date
   */
  protected issueKey(accountId: number, form: NgForm): void {
    if (this.isKeySaving()) return;
    this.isKeySaving.set(true);

    const expiryDate: string = form.value.expiresAt;
    const expiresAt = expiryDate ? `${expiryDate}T23:59:59` : undefined;

    this.accounts
      .issueApiKey$(accountId, form.value.name, expiresAt)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isKeySaving.set(false);
          this.isIssuingKey.set(false);
          form.resetForm();
          this.keysState.set({ dataState: DataState.LOADED, appData: response.data?.apiKeys ?? [] });
          if (response.data?.rawKey) {
            this.revealedKey.set({ name: form.value.name, rawKey: response.data.rawKey });
          }
        },
        error: (error: Error) => {
          this.isKeySaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Revokes one API key. No confirmation prompt, same reasoning as {@link deactivate} — a revoked
   * key cannot itself be un-revoked, but nothing about the service account or its other keys is
   * touched, and issuing a fresh replacement key is one click away.
   *
   * @param accountId - the key's owning service account
   * @param key       - the key to revoke
   */
  protected revokeKey(accountId: number, key: ApiKeyInterface): void {
    if (this.isKeySaving()) return;
    this.isKeySaving.set(true);

    this.accounts
      .revokeApiKey$(accountId, key.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isKeySaving.set(false);
          this.notification.onSuccess(response.message ?? 'API key revoked.');
          this.keysState.set({ dataState: DataState.LOADED, appData: response.data?.apiKeys ?? [] });
        },
        error: (error: Error) => {
          this.isKeySaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /** Dismisses the one-time key reveal — the plaintext is gone from the page for good. */
  protected dismissRevealedKey(): void {
    this.revealedKey.set(undefined);
  }

  /**
   * The display name for a service-account row. {@code ServiceAccountServiceImpl#insertServiceAccount}
   * splits the create form's single {@code name} into {@code firstName}/{@code lastName} the same
   * way a human registration does (first word / remainder), so reading {@code firstName} alone
   * would silently truncate a multi-word name like "CI pipeline" down to "CI".
   *
   * @param account - the service-account row being rendered
   * @returns the account's full display name, or its synthetic email if somehow both are blank
   */
  protected accountLabel(account: UserInterface): string {
    return [account.firstName, account.lastName].filter((part) => !!part).join(' ') || account.email;
  }

  /** Fetches the full service-account list and folds it into {@link pageState}. */
  private load(): void {
    this.accounts
      .list$()
      .pipe(
        map((response) => ({ dataState: DataState.LOADED, appData: response })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.pageState.set(state));
  }

  /** Fetches one service account's API keys and folds them into {@link keysState}. */
  private loadKeys(accountId: number): void {
    this.keysState.set({ dataState: DataState.LOADING });
    this.accounts
      .listApiKeys$(accountId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.keysState.set({ dataState: DataState.LOADED, appData: response.data?.apiKeys ?? [] }),
        error: (error: Error) => {
          this.keysState.set({ dataState: DataState.ERROR, error: error.message });
          this.notification.onError(error.message);
        },
      });
  }
}
