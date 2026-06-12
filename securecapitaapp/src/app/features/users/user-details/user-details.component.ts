import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DatePipe, NgClass } from '@angular/common';
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
  imports: [FormsModule, RouterLink, DatePipe, NgClass, NavbarComponent],
  templateUrl: './user-details.component.html',
  styleUrl: './user-details.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserDetailsComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  /** Exposes {@link EventType} to the template for event badge styling. */
  protected readonly EventType = EventType;

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
  protected readonly canEditRole: boolean;
  protected readonly canEditSettings: boolean;

  private readonly adminUserService = inject(AdminUserService);
  private readonly userService = inject(UserService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /** Caches the latest successful response so mutations can patch slices of it. */
  private data = signal<CustomHttpResponseInterface<AdminUserDetailInterface> | undefined>(undefined);

  constructor() {
    this.canEditRole = this.userService.hasAnyAuthority('UPDATE:ROLE');
    this.canEditSettings = this.userService.hasAnyAuthority('UPDATE:USER');
  }

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
   * Merges a mutation response into the cached detail state. The PATCH endpoints
   * return {@code selectedUser} and {@code roles} but not {@code events}, so the
   * previously loaded event history is preserved rather than blanked.
   *
   * @param response - the PATCH response envelope
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
   * @param error - the normalised error message from the service layer
   */
  private failMutation(error: string): void {
    this.isLoading.set(false);
    this.notification.onError(error);
    this.userState.set({ dataState: DataState.LOADED, error, appData: this.data() });
  }
}
