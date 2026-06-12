import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserInterface } from '../../../interface/user.interface';
import { SessionInterface, TotpSetupInterface } from '../../../interface/security.interface';

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
  imports: [FormsModule, RouterLink, DatePipe, NavbarComponent],
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
  /** Family of the session this browser is on — badges the current row. */
  protected readonly currentFamily = signal('');
  /** Disables buttons while any mutation is in flight. */
  protected readonly isLoading = signal(false);

  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Boots the page: the profile fetch paints the shell (user, MFA flags), then the
   * TOTP status and sessions panels fill in independently — a failure in one panel
   * never blanks the others.
   */
  ngOnInit(): void {
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
          this.notification.onSuccess('Authenticator app enabled');
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
          this.notification.onSuccess('Authenticator app disabled');
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
   * page so all second-factor management lives on one surface. The backend still
   * requires a phone number on the account.
   */
  protected toggleSmsMfa(): void {
    this.isLoading.set(true);
    this.userService
      .toggleMFA$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.isLoading.set(false);
          this.notification.onSuccess('SMS verification setting updated');
        },
        error: (error: string) => {
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
          this.notification.onSuccess('Session revoked');
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
          this.notification.onSuccess('Logged out of other sessions');
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.isLoading.set(false);
        },
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
}
