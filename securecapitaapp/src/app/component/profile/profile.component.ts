import { Component, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NavbarComponent } from '../navbar/navbar.component';

enum EventType {
  LOGIN_ATTEMPT = 'LOGIN_ATTEMPT',
  LOGIN_ATTEMPT_SUCCESS = 'LOGIN_ATTEMPT_SUCCESS',
  LOGIN_ATTEMPT_FAILURE = 'LOGIN_ATTEMPT_FAILURE',
  PROFILE_UPDATE = 'PROFILE_UPDATE',
  PROFILE_PICTURE_UPDATE = 'PROFILE_PICTURE_UPDATE',
  ROLE_UPDATE = 'ROLE_UPDATE',
  ACCOUNT_SETTINGS_UPDATE = 'ACCOUNT_SETTINGS_UPDATE',
  PASSWORD_UPDATE = 'PASSWORD_UPDATE',
  MFA_UPDATE = 'MFA_UPDATE',
}

interface User {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  address: string;
  title: string;
  bio: string;
  imageUrl: string;
  roleName: 'ROLE_USER' | 'ROLE_ADMIN' | 'ROLE_SYSADMIN';
  permissions: string;
  enabled: boolean;
  notLocked: boolean;
  usingMfa: boolean;
  createdAt: string;
}

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
  user: User;
  roles: Role[];
  events: ActivityEvent[];
}

@Component({
  selector: 'app-profile',
  imports: [FormsModule, RouterLink, DatePipe, NavbarComponent],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
})
export class ProfileComponent {
  protected readonly EventType = EventType;

  protected readonly isLoading = signal(false);
  protected readonly showLogs = signal(true);

  protected readonly profile = signal<ProfileData>({
    user: {
      id: 1,
      firstName: 'John',
      lastName: 'Doe',
      email: 'john.doe@example.com',
      phone: '555-555-1234',
      address: '123 Main St, Springfield',
      title: 'Senior Engineer',
      bio: 'Likes building things and breaking them on purpose.',
      imageUrl: 'https://www.gravatar.com/avatar/?d=mp',
      roleName: 'ROLE_ADMIN',
      permissions: 'READ_USER,UPDATE_USER,DELETE_USER',
      enabled: true,
      notLocked: true,
      usingMfa: false,
      createdAt: '2024-01-15T10:30:00Z',
    },
    roles: [
      { name: 'ROLE_USER' },
      { name: 'ROLE_ADMIN' },
      { name: 'ROLE_SYSADMIN' },
    ],
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
      user: { ...p.user, usingMfa: !p.user.usingMfa },
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
