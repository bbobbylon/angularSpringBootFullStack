import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, OnInit, signal } from '@angular/core';
import { DatePipe, NgClass } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { UserService } from '../../../service/user.service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { NotificationsService } from '../../../service/notifications-service';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { ProfileInterface } from '../../../interface/appstates.interface';
import { EventType } from '../../../enumeration/event-type.enum';
import { RolesInterface } from '../../../interface/roles.interface';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { UserInterface } from '../../../interface/user.interface';

// TODO - add Reactive forms to bind the form data to the component properties and handle form validation more effectively. This will allow for better user experience and more robust form handling in the profile component. Also it will help with binding directly to the values on the backend for explicit handling instead of implicit.

/**
 * Profile view for authenticated users.
 *
 * Loads profile data, supports profile updates and password changes,
 * and manages local UI state such as loading and audit-log toggles.
 *
 * State is held in {@link profileState}, a single writable signal that every
 * update method (`updateProfile`, `updatePassword`, `updateRole`, etc.)
 * mutates via {@code .set()}. The previous per-method {@code toSignal()}
 * reassignment pattern hit NG0203 at runtime because {@code toSignal} is
 * not callable from event handlers — see notes on each method below.
 */
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, NgClass, NavbarComponent],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileComponent implements OnInit {
  @Input() user: UserInterface;
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  /**
   * Single source of truth for the profile view. Carries `dataState`,
   * `appData` (the full profile response), and optional `error` for the template.
   */
  profileState = signal<GlobalStateInterface<CustomHttpResponseInterface<ProfileInterface>>>({
    dataState: DataState.LOADING,
  });
  /** Exposes the `EventType` enum to the template for styling and displaying event information. */
  protected readonly EventType = EventType;
  /** A signal that controls the visibility of the user's activity logs section. */
  protected readonly showLogs = signal(true);
  /** A signal holding the list of permissions for the currently selected role. */
  protected readonly permissions = signal<string[]>([]);
  /** The column currently used to sort the activity log. Defaults to newest-first by date. */
  protected readonly sortColumn = signal<string>('createdAt');
  /** The current sort direction for the activity log. */
  protected readonly sortDirection = signal<'asc' | 'desc'>('desc');
  /** Zero-based index of the currently displayed events page. */
  protected readonly currentEventPage = signal(0);
  /** Total number of events pages returned by the backend. Drives pagination control visibility. */
  protected readonly eventsTotalPages = signal(0);
  /** Tracks whether any async operation is in progress. Controls button disabled states and spinner visibility. */
  protected isLoading = signal(false);
  /** Injected `UserService` to interact with the backend for user-related operations. */
  private readonly userService = inject(UserService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  /** Caches the most recent successful API response so all update methods can stay in LOADED state while requests are in flight. */
  private data = signal<CustomHttpResponseInterface<ProfileInterface>>(null);

  /**
   * Initializes the component by fetching the user's profile information.
   *
   * Sets {@link profileState} to LOADING synchronously, then `.set()`s LOADED or
   * ERROR from the subscribe callbacks. {@code takeUntilDestroyed} cleans up the
   * subscription on unmount.
   */
  ngOnInit(): void {
    this.isLoading.set(true);
    this.userService
      .profile$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Fetched profile data:', response);
          this.data.set(response);
          this.isLoading.set(false);
          this.permissions.set(response.data.user.permissions.split(',').map((p: string) => p.trim()));
          this.eventsTotalPages.set(response.data?.eventsTotalPages ?? 0);
          this.profileState.set({ dataState: DataState.LOADED, appData: response });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.profileState.set({ dataState: DataState.ERROR, error, appData: this.data() });
        },
      });
  }

  /**
   * Updates the permissions signal based on the selected role.
   * When a user selects a different role in the UI, this method finds the corresponding role
   * from the profile data and updates the `permissions` signal with the permissions of that role.
   * @param roleName The name of the role selected by the user.
   */
  onRoleChange(roleName: string): void {
    const roles = this.data()?.data?.roles;
    const match = roles?.find((r: RolesInterface) => r.name === roleName);
    if (match?.permission) {
      this.permissions.set(match.permission.split(',').map((p: string) => p.trim()));
    }
  }

  /**
   * Submits the profile-update form to the backend.
   *
   * Merges form values onto the current user snapshot from the {@link data} signal so
   * unmodified fields are preserved (the backend expects a full user object).
   * Keeps {@link profileState} in LOADED throughout — the form stays visible while
   * the spinner overlay reacts via the {@link isLoading} signal.
   *
   * @param profileForm - the Angular {@link NgForm} containing the updated profile fields
   */
  updateProfile(profileForm: NgForm): void {
    this.isLoading.set(true);
    const currentUser = this.data()?.data?.user;
    console.log('Current user data before update:', currentUser);
    const updatedUser = { ...currentUser, ...profileForm.value };
    console.log('Updated user data to be sent to server:', updatedUser);
    this.userService
      .update$(updatedUser)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Profile updated successfully:', response);
          this.data.set({ ...response, data: response.data });
          this.isLoading.set(false);
          this.notification.onSuccess('Profile updated successfully');
          this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }

  /**
   * Submits the password-change form to the backend.
   *
   * Performs a client-side equality check between `newPassword` and `confirmPassword`
   * before issuing the request; mismatched values reset the form silently and do not
   * call the server. On success the form is reset; on failure the error is surfaced
   * via the LOADED-with-error pattern so the form stays visible.
   *
   * @param passwordForm - the {@link NgForm} containing currentPassword, newPassword, confirmPassword
   */
  updatePassword(passwordForm: NgForm): void {
    this.isLoading.set(true);
    if (passwordForm.value.newPassword === passwordForm.value.confirmPassword) {
      this.userService
        .updatePassword$(passwordForm.value)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (response) => {
            console.log('Profile updated successfully:', response);
            this.data.set({ ...response, data: response.data });
            passwordForm.reset();
            this.isLoading.set(false);
            this.notification.onSuccess('Password updated successfully');
            this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
          },
          error: (error: string) => {
            this.isLoading.set(false);
            passwordForm.reset();
            this.notification.onError(error);
            this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
          },
        });
    } else {
      passwordForm.reset();
      this.isLoading.set(false);
    }
  }

  /**
   * Submits the role-change form and reassigns the authenticated user's role.
   *
   * Calls {@code updateUserRole$} with the selected {@code roleName}, then mirrors
   * the server-returned user back into the {@link data} signal and {@link profileState}
   * so the Authorization tab reflects the new role and its permissions without a
   * page reload.
   *
   * @param roleForm - the submitted NgForm containing the selected {@code roleName}
   */
  updateRole(roleForm: NgForm): void {
    this.isLoading.set(true);
    console.log(roleForm);
    this.userService
      .updateUserRole$(roleForm.value.roleName)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Role updated successfully:', response);
          this.data.set({ ...response, data: response.data });
          this.isLoading.set(false);
          this.notification.onSuccess('Role updated successfully');
          this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }

  /**
   * Updates the user's account settings (theme, notifications, etc.) via the
   * {@code updateAccountSettings$} endpoint.
   *
   * @param settingsForm - the {@link NgForm} with the updated account settings
   */
  updateAccountSettings(settingsForm: NgForm): void {
    this.isLoading.set(true);
    console.log(settingsForm);
    this.userService
      .updateAccountSettings$(settingsForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Account Settings updated successfully:', response);
          this.data.set({ ...response, data: response.data });
          this.isLoading.set(false);
          this.notification.onSuccess('Settings updated successfully');
          this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }

  /**
   * Checks whether the current user has a specific permission.
   *
   * Parses the comma-delimited permissions string returned by the backend.
   */
  protected hasPermission(permission: string): boolean {
    const permissions = this.data()?.data?.user?.permissions;
    if (!permissions) return false;
    return permissions
      .split(',')
      .map((p: string) => p.trim())
      .includes(permission);
  }

  /**
   * Flips the authenticated user's MFA status via the backend toggle endpoint.
   *
   * The backend rejects the toggle if no phone number is set; the error
   * propagates through the subscribe callback and surfaces in the template.
   */
  protected toggleMfa(): void {
    this.isLoading.set(true);
    this.userService
      .toggleMFA$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('MFA Settings updated successfully:', response);
          this.data.set({ ...response, data: response.data });
          this.isLoading.set(false);
          this.notification.onSuccess('MFA settings updated');
          this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }

  /**
   * Shows or hides the activity log panel.
   *
   * Implemented via a signal (`showLogs`) to keep template updates efficient.
   * Toggling this signal's value will conditionally render the activity log section in the component's template.
   */
  protected toggleLogs(): void {
    this.showLogs.update((v) => !v);
  }

  /**
   * Navigates to the given events page without re-fetching the full profile.
   *
   * Calls {@code GET /user/events?page=n} and patches only the {@code events}
   * slice of the cached {@link data} signal so user data and roles are preserved.
   *
   * @param page - zero-based target page index
   */
  protected goToEventsPage(page: number): void {
    this.currentEventPage.set(page);
    this.userService.userEvents$(page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.data.set({
            ...this.data(),
            data: { ...this.data().data, events: response.data?.events },
          });
          this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }

  /**
   * Handles a file-input change event triggered when the user picks a new profile image.
   *
   * Extracts the selected {@link File} from the DOM event, wraps it in a
   * {@code FormData} object (required for multipart upload), and sends it to
   * {@code PATCH /user/update/image}. On success the response contains the updated
   * user with a cache-busted image URL so the browser reloads the new image immediately.
   *
   * @param event - the DOM {@code change} event fired by the hidden file input
   */
  protected updatePicture(event: Event): void {
    const image = (event.target as HTMLInputElement).files?.[0];
    if (image) {
      this.isLoading.set(true);
      this.userService
        .updateProfileImage$(this.getFormData(image))
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (response) => {
            console.log('MFA Settings updated successfully:', response);
            this.data.set({
              ...response,
              data: { ...response.data, user: { ...response.data.user, imageUrl: `${response.data.user.imageUrl}?time=${new Date().getTime()}` } },
            });
            this.isLoading.set(false);
            this.profileState.set({ dataState: DataState.LOADED, appData: this.data() });
          },
          error: (error: string) => {
            this.isLoading.set(false);
            this.profileState.set({ dataState: DataState.LOADED, error, appData: this.data() });
          },
        });
    }
  }

  private getFormData(image: File): FormData {
    const formData = new FormData();
    formData.append('image', image);
    return formData;
  }
}
