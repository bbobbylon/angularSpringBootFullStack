import { Component, inject, OnInit, signal } from '@angular/core';
import { AsyncPipe, DatePipe, JsonPipe } from '@angular/common';
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
import { UserInterface } from '../../interface/user.interface';

interface Role {
  name: string;
}

interface ActivityEvent {
  device: string;
  ipAddress: string;
  createdAt: string;
  type: EventType;
  description: string;
}

interface ProfileData {
  user: UserInterface;
  roles: Role[];
  events: ActivityEvent[];
}

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, NavbarComponent, AsyncPipe, JsonPipe],
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
  protected readonly isLoading = signal(false);
  protected readonly showLogs = signal(true);
  protected readonly profile = signal<ProfileData>({
    user: {
      id: 1,
      username: 'johndoe',
      firstName: 'John',
      lastName: 'Doe',
      email: 'john.doe@example.com',
      phoneNumber: '555-555-1234',
      address: '123 Main St, Springfield',
      title: 'Senior Engineer',
      bio: 'Likes building things and breaking them on purpose.',
      imageUrl: 'https://www.gravatar.com/avatar/?d=mp',
      roleName: 'ROLE_ADMIN',
      permissions: 'READ_USER,UPDATE_USER,DELETE_USER',
      enabled: true,
      isNotLocked: true,
      using2FA: false,
      createdAt: new Date('2024-01-1'),
    },
    roles: [{ name: 'ROLE_USER' }, { name: 'ROLE_ADMIN' }, { name: 'ROLE_SYSADMIN' }],
    events: [
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
    ],
  });
  private readonly userService = inject(UserService);

  // in this method, When we run the .pipe() on the profile$ observable, we are transforming the emitted value (which is of type CustomHttpResponseInterface<ProfileInterface>) into a new object that matches the GlobalStateInterface structure expected by our component's template. We also handle loading and error states appropriately. This is important to note because the original profile$ observable emits a different type than what our template expects, so we need to map it to the correct structure before it can be used in the template. If we check the GlobalState, we can see that it has a dataState property to indicate the loading state, an appData property to hold the actual profile data, and an error property to hold any error messages. By mapping the profile$ observable to this structure, we ensure that our template can react correctly to loading, success, and error states when displaying the user's profile information. Another thing to keep in mind is that we are also using the startWith operator to emit an initial loading state before the profile data is fetched, and the catchError operator to handle any errors that may occur during the HTTP request and emit an error state accordingly. This is all needed to ensure that our component can handle the asynchronous nature of fetching profile data and provide a good user experience by showing loading indicators and error messages when necessary.
  // when we call ngOnInit, we will make the Http call, and once we get the response, we will log it, save it in a local variable as an observable, which is profileState$. profileState$ is the observable we will be using in the template to display the profile data.
  //private readonly router = inject(Router);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<ProfileInterface>>(null);

  // We return dataState to the profileState$ observable.
  ngOnInit(): void {
    console.log('function fired');
    this.profileState$ = this.userService.profile$().pipe(
      map(response => {
        // we are mapping the response to a local variable this.dataSubject.
        console.log(response);
        this.dataSubject.next(response);
        return {
          dataState: DataState.LOADED,
          //this should be changed back to appData later. Right now I am testing some things
          appData: response,
        };
      }),
      startWith({
        dataState: DataState.LOADING,
      }),
      catchError((error: string) => {
        return of({
          dataState: DataState.ERROR,
          error,
          appData: this.dataSubject.value,
        });
      }),
    );
  }

  protected updateProfile(form: NgForm): void {
    console.log('updateProfile', form.value);
  }

  protected updatePassword(form: NgForm): void {
    if (form.value.newPassword !== form.value.confirmNewPassword) {
      form.reset();
      return;
    }
    console.log('updatePassword', form.value);
    form.reset();
  }

  protected updateRole(form: NgForm): void {
    console.log('updateRole', form.value);
  }

  protected updateAccountSettings(form: NgForm): void {
    console.log('updateAccountSettings', form.value);
  }

  protected toggleMfa(): void {
    this.profile.update(p => ({
      ...p,
      user: { ...p.user, usingMfa: !p.user.using2FA },
    }));
  }

  protected toggleLogs(): void {
    this.showLogs.update(v => !v);
  }

  protected updatePicture(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    console.log('updatePicture', file.name);
  }
}
