import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { switchMap } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { UserService } from '../../../service/user.service';
import { ProviderLinkInterface } from '../../../interface/security.interface';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserInterface } from '../../../interface/user.interface';
import { SessionInterface, TotpSetupInterface } from '../../../interface/security.interface';
import { UserEventsInterface } from '../../../interface/user-events.interface';
import { getEventDisplay } from '../../../utils/event-display.utils';
import { TranslocoDirective } from '@jsverse/transloco';
import { TranslocoService } from '@jsverse/transloco';

/**
 * Account Security Center (plan.md M4 creates this surface; M5 populates it).
 *
 * One page, two security stories:
 * - <b>Multi-factor authentication</b> — the authenticator (TOTP) enrollment wizard
 *   (QR scan → code confirmation → one-time recovery-code reveal), disable-with-code,
 *   and the legacy SMS toggle relocated from the Profile page's Authentication tab.
 * - <b>Sessions &amp; devices</b> — the stateful half of the hybrid token model made
 *   visible: every live refresh session with device/IP/last-seen, per-session revoke,
 *   and "log out everywhere else".
 *
 * State follows the profile page's conventions: one {@link DataState} signal for the
 * page skeleton, fine-grained signals for each panel, and {@code takeUntilDestroyed}
 * on every subscription.
 */
@Component({
  selector: 'app-security-center',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, NavbarComponent, TranslocoDirective],
  templateUrl: './security-center.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityCenterComponent implements OnInit {
  /** Template access to the DataState enum for asynchronous rendering. */
  readonly DataState = DataState;
  /** Page-level load state (driven by the initial profile fetch). */
  protected readonly dataState = signal<DataState>(DataState.LOADING);
  /** The signed-in user — drives the navbar and the MFA badges. */
  protected readonly user = signal<UserInterface | undefined>(undefined);
  /** Unused recovery codes remaining, from GET /user/totp/status. */
  protected readonly recoveryCodesRemaining = signal(0);
  /**
   * Authenticator wizard position: 'idle' (status view), 'scan' (QR + confirm code),
   * 'codes' (the one-time recovery-code reveal after a successful enable).
   */
  protected readonly enrollStep = signal<'idle' | 'scan' | 'codes'>('idle');
  /** Payload of POST /user/totp/setup while the wizard is in the 'scan' step. */
  protected readonly setup = signal<TotpSetupInterface | undefined>(undefined);
  /** Plaintext recovery codes — held only while the 'codes' step is on screen. */
  protected readonly recoveryCodes = signal<string[]>([]);
  /** Live sessions for the devices panel. */
  protected readonly sessions = signal<SessionInterface[]>([]);

  /** Identity providers currently connected to this account (ROADMAP §1.4). */
  protected readonly connectedProviders = signal<ProviderLinkInterface[]>([]);

  /** Providers this deployment supports, so the panel can offer the ones not yet connected. */
  protected readonly availableProviders = signal<string[]>([]);

  /**
   * Providers on offer that are not already connected.
   *
   * <p>Computed rather than stored so the "Connect" list shrinks the moment a link is made,
   * without a second round trip to recompute what is left.
   */
  protected readonly connectableProviders = computed(() => {
    const linked = new Set(this.connectedProviders().map((link) => link.provider));
    return this.availableProviders().filter((provider) => !linked.has(provider));
  });
  /** Family of the session this browser is on — badges the current row. */
  protected readonly currentFamily = signal('');
  /** Disables buttons while any mutation is in flight. */
  protected readonly isLoading = signal(false);
  /**
   * Whether the inline "add a phone number" form is showing under the SMS row.
   *
   * SMS 2FA cannot be enabled without a phone number, and the backend enforces that
   * (see {@code UserRepoImpl#toggleMFA}). Surfacing that as a raw error toast made the
   * user's next step their problem to figure out; opening the field that unblocks them
   * turns a dead end into the first step of the flow. The server-side guard is
   * deliberately left in place — this is a usability layer over it, not a replacement.
   */
  protected readonly phonePromptOpen = signal(false);
  /** Audit events for the Activity History panel (M2). */
  protected readonly events = signal<UserEventsInterface[]>([]);
  /** Total event pages returned by the backend — drives the pagination controls. */
  protected readonly eventsTotalPages = signal(0);
  /** Zero-based index of the currently shown events page. */
  protected readonly currentEventPage = signal(0);
  /** Exposes the event display helper to the template. */
  protected readonly getEventDisplay = getEventDisplay;

  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  /** Translates toast copy at call time, so a language switch applies to the next toast. */
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Boots the page: the profile fetch paints the shell (user, MFA flags), then the
   * TOTP status and sessions panels fill in independently — a failure in one panel
   * never blanks the others.
   */
  ngOnInit(): void {
    this.loadConnectedProviders();
    this.userService
      .profile$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.dataState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.dataState.set(DataState.ERROR);
        },
      });
    this.refreshTotpStatus();
    this.refreshSessions();
    this.loadEvents(0);
  }

  /**
   * Starts (or restarts) authenticator enrollment: fetches a fresh secret + QR and
   * moves the wizard to the scan step. Safe to re-click — the backend replaces the
   * pending secret.
   */
  protected startEnrollment(): void {
    this.isLoading.set(true);
    this.userService
      .totpSetup$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.setup.set(response.data);
          this.enrollStep.set('scan');
          this.isLoading.set(false);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
        },
      });
  }

  /**
   * Confirms enrollment with the code the user read from their freshly scanned
   * authenticator. On success the wizard advances to the one-time recovery-code
   * reveal and the user object is refreshed (usingTotp flips on).
   */
  protected confirmEnrollment(confirmForm: NgForm): void {
    this.isLoading.set(true);
    this.userService
      .totpEnable$(confirmForm.value.code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.recoveryCodes.set(response.data?.recoveryCodes ?? []);
          this.enrollStep.set('codes');
          this.setup.set(undefined);
          this.isLoading.set(false);
          this.notification.onSuccess(this.transloco.translate('toasts.totpEnabled'));
          this.refreshTotpStatus();
          confirmForm.reset();
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
        },
      });
  }

  /** Leaves the recovery-code reveal (the codes are gone for good once dismissed). */
  protected finishEnrollment(): void {
    this.recoveryCodes.set([]);
    this.enrollStep.set('idle');
  }

  /** Abandons the scan step without enabling anything (the pending secret stays inert). */
  protected cancelEnrollment(): void {
    this.setup.set(undefined);
    this.enrollStep.set('idle');
  }

  /**
   * Disables the authenticator. The backend demands a live TOTP or recovery code —
   * a stolen session alone cannot strip the second factor — so the form collects one.
   */
  protected disableTotp(disableForm: NgForm): void {
    this.isLoading.set(true);
    this.userService
      .totpDisable$(disableForm.value.code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.isLoading.set(false);
          this.notification.onSuccess(this.transloco.translate('toasts.totpDisabled'));
          this.refreshTotpStatus();
          disableForm.reset();
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
          disableForm.reset();
        },
      });
  }

  /**
   * Flips the SMS second factor (the pre-M4 MFA), relocated here from the Profile
   * page so all second-factor management lives on one surface.
   *
   * <p>When the user is <em>enabling</em> SMS and has no phone number on file, this opens
   * the inline capture form instead of calling the API. Letting the request go through
   * would return the backend's "a phone number is required" error — accurate, but it ends
   * the interaction and leaves the user to find the Profile page themselves. Disabling is
   * never intercepted: an account can hold a stale flag with no number, and blocking the
   * path back to "off" would be the worst possible time to ask for a phone number.
   */
  protected toggleSmsMfa(): void {
    if (!this.user()?.using2FA && !this.user()?.phoneNumber) {
      this.phonePromptOpen.set(true);
      return;
    }
    this.isLoading.set(true);
    this.userService
      .toggleMFA$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.isLoading.set(false);
          this.notification.onSuccess(this.transloco.translate('toasts.smsUpdated'));
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
        },
      });
  }

  /** Abandons the inline phone capture, leaving SMS 2FA off and the profile untouched. */
  protected cancelPhonePrompt(phoneForm: NgForm): void {
    this.phonePromptOpen.set(false);
    phoneForm.resetForm();
  }

  /**
   * Saves the supplied phone number to the profile and then turns SMS 2FA on, as one
   * user-visible action.
   *
   * <p>Two requests, chained with {@code switchMap} rather than fired together: the
   * toggle is only legal <em>after</em> the number is persisted, since the backend reads
   * it back from the user row to decide whether to allow the flip. Chaining also gives an
   * honest partial-failure story — if the profile write fails, the toggle never runs and
   * the account is not left claiming an SMS factor it cannot deliver to.
   *
   * <p>The existing user object is spread into the payload because
   * {@code PATCH /user/update} binds a whole {@code UpdateForm} whose first name, last
   * name, and email are {@code @NotEmpty}; sending the phone number alone would fail
   * validation. The user's own id is ignored server-side — that endpoint sources it from
   * the JWT principal — so this cannot be aimed at another account.
   *
   * @param phoneForm the inline form supplying {@code phoneNumber}
   */
  protected savePhoneAndEnableSms(phoneForm: NgForm): void {
    const current = this.user();
    if (!current) {
      return;
    }
    this.isLoading.set(true);
    this.userService
      .update$({ ...current, phoneNumber: phoneForm.value.phoneNumber })
      .pipe(
        switchMap(() => this.userService.toggleMFA$()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.isLoading.set(false);
          this.phonePromptOpen.set(false);
          phoneForm.resetForm();
          this.notification.onSuccess(this.transloco.translate('toasts.phoneSaved'));
        },
        error: (error: string) => {
          // The form stays open and populated so the user can correct a rejected number
          // (the backend validates the shape) without retyping it.
          this.notification.onError(error);
          this.isLoading.set(false);
        },
      });
  }

  /** Revokes one session; the refreshed list comes back in the same response. */
  protected revokeSession(family: string): void {
    this.isLoading.set(true);
    this.userService
      .revokeSession$(family)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.sessions.set(response.data?.sessions ?? []);
          this.currentFamily.set(response.data?.currentFamily ?? '');
          this.isLoading.set(false);
          this.notification.onSuccess(this.transloco.translate('toasts.sessionRevoked'));
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
        },
      });
  }

  /** "Log out everywhere else" — every session except this browser's is revoked. */
  protected revokeOtherSessions(): void {
    this.isLoading.set(true);
    this.userService
      .revokeOtherSessions$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.sessions.set(response.data?.sessions ?? []);
          this.currentFamily.set(response.data?.currentFamily ?? '');
          this.isLoading.set(false);
          this.notification.onSuccess(this.transloco.translate('toasts.otherSessionsRevoked'));
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
        },
      });
  }

  /**
   * Navigates to the given events page in the Activity History panel.
   * Fetches only the events slice so the rest of the page stays intact.
   *
   * @param page - zero-based target page index
   */
  protected goToEventsPage(page: number): void {
    this.currentEventPage.set(page);
    this.loadEvents(page);
  }

  /**
   * Fetches one page of the caller's audit events from {@code GET /user/events}.
   * Called on init (page 0) and by pagination controls for subsequent pages.
   *
   * @param page - zero-based page index
   */
  private loadEvents(page: number): void {
    this.userService
      .userEvents$(page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.events.set(response.data?.events ?? []);
          this.eventsTotalPages.set(response.data?.eventsTotalPages ?? 0);
        },
        error: () => this.events.set([]),
      });
  }

  /** Re-pulls authenticator status (enabled + recovery codes remaining). */
  private refreshTotpStatus(): void {
    this.userService
      .totpStatus$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.recoveryCodesRemaining.set(response.data?.recoveryCodesRemaining ?? 0),
        // Status is decoration; a failure must not block the page.
        error: () => this.recoveryCodesRemaining.set(0),
      });
  }

  /** Re-pulls the live sessions list. */
  private refreshSessions(): void {
    this.userService
      .sessions$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.sessions.set(response.data?.sessions ?? []);
          this.currentFamily.set(response.data?.currentFamily ?? '');
        },
        error: (error: string) => this.notification.onError(error),
      });
  }

  /**
   * Loads the account's connected providers alongside the deployment's available ones.
   *
   * <p>Both fail soft: a deployment with no OAuth2 credentials configured returns an empty
   * provider list, and this panel should then simply show nothing rather than an error — federated
   * login being unconfigured is a normal state, not a fault.
   */
  private loadConnectedProviders(): void {
    this.userService
      .connectedProviders$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.connectedProviders.set(response.data?.providers ?? []),
        error: () => this.connectedProviders.set([]),
      });

    this.userService
      .federatedProviders$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.availableProviders.set(response.data?.providers ?? []),
        error: () => this.availableProviders.set([]),
      });
  }

  /**
   * Starts the OAuth2 flow to connect an additional provider.
   *
   * <p>Reuses the ordinary login initiation: signing in with a provider whose subject is unknown
   * but whose email matches this account is exactly what links it (the find-or-create convergence
   * step). There is no separate "link" endpoint to maintain, and no second code path that could
   * diverge from the one login already exercises.
   *
   * @param provider - the registration id to connect
   */
  protected connectProvider(provider: string): void {
    this.userService.initiateFederatedLogin(provider);
  }

  /**
   * Disconnects a provider, refreshing the panel from the server's response.
   *
   * <p>The backend refuses when this is the account's last sign-in method; that arrives as an
   * ordinary error whose message already names the remedy, so it is surfaced verbatim.
   *
   * @param provider - the registration id to disconnect
   */
  protected disconnectProvider(provider: string): void {
    if (this.isLoading()) return;
    this.isLoading.set(true);

    this.userService
      .unlinkProvider$(provider)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isLoading.set(false);
          this.connectedProviders.set(response.data?.providers ?? []);
          this.notification.onSuccess(this.transloco.translate('toasts.providerDisconnected'));
        },
        error: (error: Error) => {
          this.isLoading.set(false);
          this.notification.onError(error.message);
        },
      });
  }
}
