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

// TODO - add Reactive forms to bind the form data to the component properties and handle form validation more effectively. This will allow for better user experience and more robust form handling in the profile component. Also it will help with binding directly to the values on the backend for explicit handling instead of implicit.
interface ActivityEvent {
  device: string;
  ipAddress: string;
  createdAt: string;
  type: EventType;
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
  readonly DataState = DataState;
  profileState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<ProfileInterface>>> = of({
    dataState: DataState.LOADED,
    isUsingMfa: false,
  });
  protected readonly EventType = EventType;
  protected readonly showLogs = signal(true);
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
  private readonly userService = inject(UserService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<ProfileInterface>>(null);
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  protected isLoading$ = this.isLoadingSubject.asObservable();

  /**
   * Loads the current profile into the component state on initial render.
   *
   * Emits loading and error states for the template to display while
   * the profile request is in flight.
   */
  ngOnInit(): void {
    this.isLoadingSubject.next(true);
    this.profileState$ = this.userService.profile$().pipe(
      map(response => {
        console.log('Fetched profile data:', response);
        this.dataSubject.next(response);
        this.isLoadingSubject.next(false);
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
   * Persists updated profile fields entered in the form.
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
   * Submits a password change after confirming the two new passwords match.
   *
   * Resets the form on success or failure and relies on the backend to
   * return refreshed tokens that keep the session active.
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
   * Handles a role update form submission (stub until backend wiring).
   *
   * @param form - the role form payload
   */
  protected updateRole(form: NgForm): void {
    console.log('updateRole', form.value);
  }

  /**
   * Handles account settings updates (stub until backend wiring).
   *
   * @param form - the settings form payload
   */
  protected updateAccountSettings(form: NgForm): void {
    console.log('updateAccountSettings', form.value);
  }

  /**
   * Toggles multi-factor authentication in the UI (placeholder).
   *
   * Backend integration will be added alongside the account settings API.
   */
  protected toggleMfa(): void {
    /* empty */
  }

  /**
   * Shows or hides the activity log panel.
   *
   * Implemented via a signal to keep template updates cheap.
   */
  protected toggleLogs(): void {
    this.showLogs.update(v => !v);
  }

  /**
   * Handles selection of a new avatar image (stub).
   *
   * Extracts the chosen file for future upload handling.
   */
  protected updatePicture(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    console.log('updatePicture', file.name);
  }
}
