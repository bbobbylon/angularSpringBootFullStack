import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DatePipe, NgClass, SlicePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map, of, startWith, switchMap } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { EventType } from '../../../enumeration/event-type.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { AdminUserDetailInterface } from '../../../interface/admin.interface';
import { AdminUserService } from '../../../service/admin-user.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { getEventDisplay } from '../../../utils/event-display.utils';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Single-user management view — the detail half of the Users dashboard
 * (SRS FR-ADMIN-2/3/4, plan.md M3, the planned "Home > Users > User's Name" page).
 *
 * Loads {@code GET /admin/user/:id} for the selected account and lets an
 * administrator reassign the user's role ({@code UPDATE:ROLE}) and toggle the
 * enabled / not-locked account flags ({@code UPDATE:USER}). The selected user's
 * audit event history is shown read-only per FR-ADMIN-2.
 *
 * Two self-management rules mirror the backend's hard checks: the forms are
 * disabled when the selected user IS the calling administrator (the backend
 * rejects self-targeting to keep FR-RBAC-4 airtight), and each form is disabled
 * when the token lacks the matching authority (the backend re-checks both at the
 * URL and method level — NFR-SEC-4).
 */
@Component({
  selector: 'app-user-details',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, NgClass, SlicePipe, NavbarComponent, TranslocoDirective],
  templateUrl: './user-details.component.html',
  styleUrl: './user-details.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserDetailsComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  /** Exposes {@link EventType} to the template for event badge styling. */
  protected readonly EventType = EventType;
  /** Exposes the event display helper to the template for icon/label/badge rendering. */
  protected readonly getEventDisplay = getEventDisplay;
  /** Zero-based index of the currently shown events page in the activity table. */
  protected readonly currentEventPage = signal(0);

  /**
   * Single source of truth for the view: carries dataState, the full detail
   * response under appData, and an optional error string.
   */
  userState = signal<GlobalStateInterface<CustomHttpResponseInterface<AdminUserDetailInterface>>>({
    dataState: DataState.LOADING,
  });

  /** Tracks in-flight mutations so buttons can disable and show spinners. */
  protected isLoading = signal(false);

  /**
   * Whether the calling administrator may reassign roles / change account state.
   * Evaluated once from the access token's authorities claim; the backend
   * re-validates on every request, so these only shape the UI.
   */
  // Getters, not constructor-assigned fields: authority flags must follow the CURRENT token.
  // Captured once at construction they latch whatever was true then — and on a page refresh that
  // is usually an expired token, i.e. "no authorities at all", which silently disabled both admin
  // forms for a legitimate administrator. UserService memoises the decode.
  protected get canEditRole(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:ROLE');
  }

  protected get canEditSettings(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:USER');
  }

  private readonly adminUserService = inject(AdminUserService);
  private readonly userService = inject(UserService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /** Caches the latest successful response so mutations can patch slices of it. */
  private data = signal<CustomHttpResponseInterface<AdminUserDetailInterface> | undefined>(undefined);

  /**
   * Loads the selected user whenever the {@code :id} route parameter changes.
   * {@code switchMap} on {@code paramMap} (rather than a one-shot snapshot read)
   * keeps the view correct if the router reuses this component instance for a
   * different user id.
   */
  ngOnInit(): void {
    this.route.paramMap
      .pipe(
        switchMap((params) =>
          this.adminUserService.user$(Number(params.get('id'))).pipe(
            map((response) => {
              this.data.set(response);
              return { dataState: DataState.LOADED, appData: response };
            }),
            startWith({ dataState: DataState.LOADING }),
            catchError((error: string) => {
              this.notification.onError(error);
              return of({ dataState: DataState.ERROR, error });
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.userState.set(state));
  }

  /**
   * Whether the selected account belongs to the calling administrator.
   * Both mutation forms are disabled in that case — the backend rejects
   * self-targeting, so offering the buttons would only produce errors.
   */
  protected isSelf(): boolean {
    const d = this.data()?.data;
    return !!d?.user?.id && d.user.id === d.selectedUser?.id;
  }

  /**
   * Submits the role-reassignment form to {@code PATCH /admin/user/:id/role/:roleName}
   * (FR-ADMIN-3) and merges the refreshed selected user back into the cached state.
   *
   * @param roleForm - the NgForm carrying the selected {@code roleName}
   */
  protected updateRole(roleForm: NgForm): void {
    const id = this.data()?.data?.selectedUser?.id;
    if (!id) return;
    this.isLoading.set(true);
    this.adminUserService
      .updateUserRole$(id, roleForm.value.roleName)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyMutation(response, 'Role updated successfully'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Submits the account-state form to {@code PATCH /admin/user/:id/settings}
   * (FR-ADMIN-4) and merges the refreshed selected user back into the cached state.
   *
   * @param settingsForm - the NgForm carrying {@code enabled} and {@code notLocked}
   */
  protected updateAccountSettings(settingsForm: NgForm): void {
    const id = this.data()?.data?.selectedUser?.id;
    if (!id) return;
    this.isLoading.set(true);
    this.adminUserService
      .updateAccountSettings$(id, settingsForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyMutation(response, 'Account settings updated successfully'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Signs the managed user out of every device via
   * {@code DELETE /admin/user/:id/sessions}.
   *
   * <p>Deliberately separate from the account-state form rather than another checkbox on it.
   * Locking and revoking answer different questions — "can they sign in again?" versus "are they
   * still signed in right now?" — and only the second one ends an intrusion already in progress.
   * Bundling them would also make revocation a side effect of saving unrelated settings.
   *
   * <p>No confirmation dialog: a browser {@code confirm()} would block the extension-driven flows
   * this project uses, and the action is recoverable — the user simply signs in again. The button
   * carries the warning styling instead.
   */
  protected revokeSessions(): void {
    const id = this.data()?.data?.selectedUser?.id;
    if (!id) return;
    this.isLoading.set(true);
    this.adminUserService
      .revokeSessions$(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyMutation(response, 'All sessions for this user have been revoked'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Revokes ONE of the managed user's sessions, leaving their other devices signed in — the
   * granular sibling of {@link revokeSessions}, which ends all of them at once.
   *
   * @param family - the session (family) to revoke
   */
  protected revokeSession(family: string): void {
    const id = this.data()?.data?.selectedUser?.id;
    if (!id) return;
    this.isLoading.set(true);
    this.adminUserService
      .revokeSession$(id, family)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyMutation(response, 'Session revoked'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Revokes one of the managed user's passkeys — the admin "help reset" action for a lost or
   * compromised device. There is no "regenerate": a passkey's private key never leaves its
   * authenticator, so this forces the user to enroll a fresh one (or fall back to password/TOTP)
   * on their next sign-in.
   *
   * @param id - the credential's database id (never the WebAuthn credential id itself)
   */
  protected revokePasskey(id: number): void {
    const targetId = this.data()?.data?.selectedUser?.id;
    if (!targetId) return;
    this.isLoading.set(true);
    this.adminUserService
      .revokePasskey$(targetId, id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyPasskeyMutation(response, 'Passkey revoked'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /** Revokes ALL of the managed user's passkeys in one action — the bulk form of {@link revokePasskey}. */
  protected revokeAllPasskeys(): void {
    const targetId = this.data()?.data?.selectedUser?.id;
    if (!targetId) return;
    this.isLoading.set(true);
    this.adminUserService
      .revokeAllPasskeys$(targetId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyPasskeyMutation(response, 'All passkeys revoked'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Force-disables the managed user's authenticator MFA — the admin recovery path for an account
   * that has lost both its authenticator and every recovery code, and so has no live code to
   * present through the self-service disable flow (Security Center) at all. Unlike
   * {@link revokePasskey}, there is nothing to pick: one action, the whole authenticator state,
   * gone. Returns just the refreshed user (no passkey list), so this uses {@link applyMutation}
   * rather than {@link applyPasskeyMutation}.
   */
  protected resetTotp(): void {
    const targetId = this.data()?.data?.selectedUser?.id;
    if (!targetId) return;
    this.isLoading.set(true);
    this.adminUserService
      .resetTotp$(targetId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.applyMutation(response, 'Authenticator MFA reset'),
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Merges a passkey-mutation response into the cached detail state. Same shape as
   * {@link applyMutation} plus the refreshed {@code passkeys} slice those two endpoints also return.
   */
  private applyPasskeyMutation(response: CustomHttpResponseInterface<AdminUserDetailInterface>, message: string): void {
    const current = this.data();
    this.data.set({
      ...current!,
      data: {
        ...current!.data!,
        selectedUser: response.data?.selectedUser ?? current!.data!.selectedUser,
        passkeys: response.data?.passkeys ?? [],
      },
    });
    this.isLoading.set(false);
    this.notification.onSuccess(message);
    this.userState.set({ dataState: DataState.LOADED, appData: this.data() });
  }

  /**
   * Navigates to a different page of the managed user's audit event history
   * (FR-ADMIN-2). Fetches via {@code GET /admin/user/:id/events?page=n} and patches
   * only the events slice so the identity card and management forms stay intact.
   *
   * @param page - zero-based target page index
   */
  protected goToEventsPage(page: number): void {
    const id = this.data()?.data?.selectedUser?.id;
    if (!id) return;
    this.currentEventPage.set(page);
    this.adminUserService
      .userEvents$(id, page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          const current = this.data();
          this.data.set({
            ...current!,
            data: {
              ...current!.data!,
              events: response.data?.events ?? [],
              eventsTotalElements: response.data?.eventsTotalElements ?? current!.data!.eventsTotalElements,
              eventsTotalPages: response.data?.eventsTotalPages ?? current!.data!.eventsTotalPages,
            },
          });
          this.userState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => this.notification.onError(error),
      });
  }

  /**
   * Merges a mutation response into the cached detail state. The PATCH endpoints
   * return {@code selectedUser} and {@code roles} but not {@code events}, so the
   * previously loaded event history is preserved rather than blanked. The two session-revoke
   * endpoints DO return a refreshed {@code sessions} slice; the role/settings/profile PATCHes
   * don't touch sessions at all, so falling back to the previous value leaves that panel intact
   * for them too — same convention as {@code roles}.
   *
   * @param response - the PATCH/DELETE response envelope
   * @param message  - the success toast to show
   */
  private applyMutation(response: CustomHttpResponseInterface<AdminUserDetailInterface>, message: string): void {
    const current = this.data();
    this.data.set({
      ...current!,
      data: {
        ...current!.data!,
        selectedUser: response.data?.selectedUser ?? current!.data!.selectedUser,
        roles: response.data?.roles ?? current!.data!.roles,
        sessions: response.data?.sessions ?? current!.data!.sessions,
      },
    });
    this.isLoading.set(false);
    this.notification.onSuccess(message);
    this.userState.set({ dataState: DataState.LOADED, appData: this.data() });
  }

  /**
   * Surfaces a mutation failure without leaving the LOADED view — the form stays
   * visible and the error arrives as a toast plus the state's error field.
   *
   * @param error - the normalized error message from the service layer
   */
  private failMutation(error: string): void {
    this.isLoading.set(false);
    this.notification.onError(error);
    this.userState.set({ dataState: DataState.LOADED, error, appData: this.data() });
  }
}
