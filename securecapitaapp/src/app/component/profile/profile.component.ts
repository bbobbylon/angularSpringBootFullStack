import { Component, inject, OnInit, signal } from '@angular/core';
import { AsyncPipe, DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NavbarComponent } from '../navbar/navbar.component';
import { UserService } from '../../service/user.service';
import { BehaviorSubject, catchError, map, Observable, of, startWith } from 'rxjs';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { ProfileInterface } from '../../interface/appstates.interface';
import { EventType } from '../../enumeration/event-type.enum';
import { RolesInterface } from '../../interface/roles.interface';

// TODO - add Reactive forms to bind the form data to the component properties and handle form validation more effectively. This will allow for better user experience and more robust form handling in the profile component. Also it will help with binding directly to the values on the backend for explicit handling instead of implicit.

/**
 * Represents a single user activity event for display in the audit log.
 * This interface defines the structure for events like logins, profile updates, etc.
 */
interface ActivityEvent {
  /** The device and browser from which the event originated. */
  device: string;
  /** The IP address associated with the event. */
  ipAddress: string;
  /** The timestamp when the event occurred, in ISO 8601 format. */
  createdAt: string;
  /** The type of the event, categorized using the `EventType` enum. */
  type: EventType;
  /** A human-readable description of the event. */
  description: string;
}

/**
 * Profile view for authenticated users.
 *
 * Loads profile data, supports profile updates and password changes,
 * and manages local UI state such as loading and audit-log toggles.
 */
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, NavbarComponent, AsyncPipe],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
})
export class ProfileComponent implements OnInit {
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  /**
   * Observable state for the profile view.
   * It holds the global state, including the current data state (e.g., LOADING, LOADED, ERROR),
   * application data, and any errors that may occur during data fetching.
   */
  profileState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<ProfileInterface>>> = of({
    dataState: DataState.LOADED,
    isUsingMfa: false,
  });
  /** Exposes the `EventType` enum to the template for styling and displaying event information. */
  protected readonly EventType = EventType;
  /** A signal that controls the visibility of the user's activity logs section. */
  protected readonly showLogs = signal(true);
  /** A signal holding the list of permissions for the currently selected role. */
  protected readonly permissions = signal<string[]>([]);
  /** A signal containing dummy data for user activity events, used for display until the real data is available. */
  protected readonly dummyEvents = signal<ActivityEvent[]>([
    {
      device: 'Chrome on Windows',
      ipAddress: '192.168.1.10',
      createdAt: '2026-05-04T09:15:00Z',
      type: EventType.LOGIN_ATTEMPT_SUCCESS,
      description: 'Successful login from trusted device',
    },
    {
      device: 'Firefox on macOS',
      ipAddress: '10.0.0.42',
      createdAt: '2026-05-03T18:42:00Z',
      type: EventType.PROFILE_UPDATE,
      description: 'Updated profile information',
    },
    {
      device: 'Safari on iPhone',
      ipAddress: '172.16.5.7',
      createdAt: '2026-05-02T07:20:00Z',
      type: EventType.LOGIN_ATTEMPT_FAILURE,
      description: 'Failed login attempt — wrong password',
    },
  ]);
  /** Injected `UserService` to interact with the backend for user-related operations. */
  private readonly userService = inject(UserService);
  /** A BehaviorSubject to hold and manage the raw profile data fetched from the server. */
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<ProfileInterface>>(null);
  /** A BehaviorSubject to track the loading state of asynchronous operations. */
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  /** An observable of the loading state, derived from `isLoadingSubject`, for use in the template. */
  protected isLoading$ = this.isLoadingSubject.asObservable();

  /**
   * Initializes the component by fetching the user's profile information.
   * This method is an Angular lifecycle hook that is called after the component's
   * data-bound properties have been initialized. It retrieves the user data from
   * the application state, which is managed by a BehaviorSubject in the UserService.
   * It subscribes to the user$ observable to get the latest user data and updates
   * the component's state. This ensures that the profile information is always
   * current. The method also sets the initial data state to LOADING and then
   * updates it to LOADED or ERROR based on the outcome of the data fetch operation.
   */
  ngOnInit(): void {
    this.isLoadingSubject.next(true);
    this.profileState$ = this.userService.profile$().pipe(
      map(response => {
        console.log('Fetched profile data:', response);
        this.dataSubject.next(response);
        this.isLoadingSubject.next(false);
        this.permissions.set(response.data.user.permissions.split(',').map((p: string) => p.trim()));
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADING }),
      catchError((error: string) => {
        this.isLoadingSubject.next(false);
        return of({ dataState: DataState.ERROR, error, appData: this.dataSubject.value });
      }),
    );
  }

  /**
   * Updates the permissions signal based on the selected role.
   * When a user selects a different role in the UI, this method finds the corresponding role
   * from the profile data and updates the `permissions` signal with the permissions of that role.
   * @param roleName The name of the role selected by the user.
   */
  onRoleChange(roleName: string): void {
    const roles = this.dataSubject.value?.data?.roles;
    const match = roles?.find((r: RolesInterface) => r.name === roleName);
    if (match?.permission) {
      this.permissions.set(match.permission.split(',').map((p: string) => p.trim()));
    }
  }

  /**
   * Handles the form submission for updating the user's profile information.
   * This method is triggered when the user submits the profile update form.
   * It sets the application state to LOADING to indicate that an operation is in progress.
   * It then calls the `update$` method from the `UserService`, passing the form data.
   * The subscription to the `update$` observable handles the response from the server.
   * On a successful update, it updates the local user data and sets the application
   * state to LOADED. If an error occurs, it logs the error and sets the state to ERROR.
   *
   * Merges form values with the current user snapshot and updates
   * the observable state so the template reflects the new profile.
   */
  updateProfile(profileForm: NgForm): void {
    this.isLoadingSubject.next(true);
    const currentUser = this.dataSubject.value?.data?.user;
    console.log('Current user data before update:', currentUser);
    const updatedUser = { ...currentUser, ...profileForm.value };
    console.log('Updated user data to be sent to server:', updatedUser);
    this.profileState$ = this.userService.update$(updatedUser).pipe(
      map(response => {
        console.log('Profile updated successfully:', response);
        this.dataSubject.next({ ...response, data: response.data });
        this.isLoadingSubject.next(false);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => {
        this.isLoadingSubject.next(false);
        return of({ dataState: DataState.LOADED, error, appData: this.dataSubject.value });
      }),
    );
  }

  /**
   * Handles the form submission for updating the user's password.
   * This method is called when the user submits the password update form.
   * It sets the application state to LOADING. It then calls the `updatePassword$`
   * method from the `UserService` with the password form data. The subscription
   * handles the server's response, showing a notification to the user upon success
   * or logging an error and showing an error notification if the update fails.
   * After the operation, the form is reset.
   *
   * @param {NgForm} passwordForm - The form containing the user's current and new passwords.
   */
  updatePassword(passwordForm: NgForm): void {
    this.isLoadingSubject.next(true);
    /*    const currentUser = this.dataSubject.value?.data?.user;
    console.log('Current user data before update:', currentUser);
    const updatedUser = { ...currentUser, ...passwordForm.value };
    console.log('Updated user data to be sent to server:', updatedUser);*/

    if (passwordForm.value.newPassword === passwordForm.value.confirmPassword) {
      this.profileState$ = this.userService.updatePassword$(passwordForm.value).pipe(
        map(response => {
          console.log('Profile updated successfully:', response);
          passwordForm.reset();
          this.isLoadingSubject.next(false);
          return { dataState: DataState.LOADED, appData: this.dataSubject.value };
        }),
        startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
        catchError((error: string) => {
          this.isLoadingSubject.next(false);
          passwordForm.reset();
          return of({ dataState: DataState.LOADED, error, appData: this.dataSubject.value });
        }),
      );
    } else {
      passwordForm.reset();
      this.isLoadingSubject.next(false);
    }
  }

  /**
   * Submits the role-change form and reassigns the authenticated user's role.
   * Sends the selected {@code roleName} to the backend via {@code updateUserRole$},
   * then refreshes {@code profileState$} and the local {@code dataSubject} snapshot
   * so the Authorization tab reflects the new role and its permissions immediately.
   *
   * @param roleForm - the submitted NgForm containing the selected {@code roleName}
   */
  updateRole(roleForm: NgForm): void {
    /*    this.isLoadingSubject.next(true);
    const currentUser = this.dataSubject.value?.data?.user;
    console.log('Current user data before update:', currentUser);
    const updatedUser = { ...currentUser, ...profileForm.value };
    console.log('Updated user data to be sent to server:', updatedUser);*/
    this.isLoadingSubject.next(true);
    console.log(roleForm);
    this.profileState$ = this.userService.updateUserRole$(roleForm.value.roleName).pipe(
      map(response => {
        console.log('Role updated successfully:', response);
        this.dataSubject.next({ ...response, data: response.data });
        this.isLoadingSubject.next(false);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => {
        this.isLoadingSubject.next(false);
        return of({ dataState: DataState.LOADED, error, appData: this.dataSubject.value });
      }),
    );
  }

  /**
   * Updates the user's account settings.
   * This method is triggered when the user saves changes to their account settings.
   * It sets the application state to LOADING and calls the `updateAccountSettings$`
   * method from the `UserService`. The subscription handles the server's response,
   * updating the local user data and showing a success notification. If an error
   * occurs, it is logged, and an error notification is shown.
   *
   * @param {NgForm} settingsForm - The form containing the updated account settings.
   */
  updateAccountSettings(settingsForm: NgForm): void {
    /*    this.isLoadingSubject.next(true);
    const currentUser = this.dataSubject.value?.data?.user;
    console.log('Current user data before update:', currentUser);
    const updatedUser = { ...currentUser, ...profileForm.value };
    console.log('Updated user data to be sent to server:', updatedUser);*/
    this.isLoadingSubject.next(true);
    console.log(settingsForm);
    this.profileState$ = this.userService.updateAccountSettings$(settingsForm.value).pipe(
      map(response => {
        console.log('Account Settings updated successfully:', response);
        this.dataSubject.next({ ...response, data: response.data });
        this.isLoadingSubject.next(false);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => {
        this.isLoadingSubject.next(false);
        return of({ dataState: DataState.LOADED, error, appData: this.dataSubject.value });
      }),
    );
  }

  /**
   * Checks whether the current user has a specific permission.
   *
   * Parses the comma-delimited permissions string returned by the backend.
   */
  protected hasPermission(permission: string): boolean {
    const permissions = this.dataSubject.value?.data?.user?.permissions;
    if (!permissions) return false;
    return permissions
      .split(',')
      .map((p: string) => p.trim())
      .includes(permission);
  }

  /**
   * Flips the authenticated user's MFA status via the backend toggle endpoint.
   * Sets the loading state before the call and clears it on success or error.
   * The backend enforces that a phone number is set; if it is missing, the error
   * propagates through {@code catchError} and surfaces in the template.
   */
  protected toggleMfa(): void {
    this.isLoadingSubject.next(true);
    this.profileState$ = this.userService.toggleMFA$().pipe(
      map(response => {
        console.log('MFA Settings updated successfully:', response);
        this.dataSubject.next({ ...response, data: response.data });
        this.isLoadingSubject.next(false);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => {
        this.isLoadingSubject.next(false);
        return of({ dataState: DataState.LOADED, error, appData: this.dataSubject.value });
      }),
    );
  }

  /**
   * Shows or hides the activity log panel.
   *
   * Implemented via a signal (`showLogs`) to keep template updates efficient.
   * Toggling this signal's value will conditionally render the activity log section in the component's template.
   */
  protected toggleLogs(): void {
    this.showLogs.update(v => !v);
  }

  /**
   * Handles a file-input change event triggered when the user picks a new profile image.
   * <p>
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
      this.isLoadingSubject.next(true);
      this.profileState$ = this.userService.updateProfileImage$(this.getFormData(image)).pipe(
        map(response => {
          console.log('MFA Settings updated successfully:', response);
          this.dataSubject.next({
            ...response,
            data: { ...response.data, user: { ...response.data.user, imageUrl: `${response.data.user.imageUrl}?time=${new Date().getTime()}` } },
          });

          this.isLoadingSubject.next(false);
          return { dataState: DataState.LOADED, appData: this.dataSubject.value };
        }),
        startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
        catchError((error: string) => {
          this.isLoadingSubject.next(false);
          return of({ dataState: DataState.LOADED, error, appData: this.dataSubject.value });
        }),
      );
    }
  }

  private getFormData(image: File): FormData {
    const formData = new FormData();
    formData.append('image', image);
    return formData;
  }
}
