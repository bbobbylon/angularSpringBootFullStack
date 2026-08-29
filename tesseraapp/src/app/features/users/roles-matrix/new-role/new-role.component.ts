import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../../shared/navbar/navbar.component';
import { UserService } from '../../../../service/user.service';
import { AdminUserService } from '../../../../service/admin-user.service';
import { NotificationsService } from '../../../../service/notifications-service';
import { DataState } from '../../../../enumeration/datastate.enum';
import { UserInterface } from '../../../../interface/user.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Dedicated "create role" screen (FUTURE-ENHANCEMENTS.md §3.2), split out of
 * {@code RolesMatrixComponent} on 2026-08-29 for the same reason the organization catalog was
 * split — {@code /roles} is meant to be the matrix of what exists, not also the form for adding
 * to it, matching the {@code /customer/new} and {@code /invoice/new} precedent.
 *
 * <p>The create call itself ({@code POST /admin/role}) and its validation (name must match
 * {@code ^ROLE_[A-Za-z_]+$}, permission is a free-text comma-delimited authority list) are
 * unchanged from {@code RolesMatrixComponent#createRole}. Only the destination changed: this page
 * stays put and resets the form on success (matching {@code NewCustomerComponent}), instead of
 * splicing the response into a matrix signal this page no longer holds — the matrix will pick up
 * the new role the next time {@code /roles} loads.
 *
 * <h3>Route-level gate is a UX narrowing, not the security boundary</h3>
 * The backend re-checks {@code UPDATE:ROLE} regardless of what this component renders. The route
 * itself only carries {@code adminGuard} (staff-grade authority), same as {@code /roles} — a
 * caller who reaches this page without the narrower {@code ROLE_APPLICATION_ADMIN} tier (see
 * {@link isApplicationAdmin}) sees a permission notice instead of the form, rather than a form
 * that would 403 on submit.
 */
@Component({
  selector: 'app-new-role',
  standalone: true,
  imports: [FormsModule, RouterLink, NavbarComponent, TranslocoDirective],
  templateUrl: './new-role.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewRoleComponent implements OnInit {
  /** Template access to DataState for skeleton/error rendering. */
  readonly DataState = DataState;
  /** Page load state — starts LOADING while the caller's own role is confirmed (see {@link isApplicationAdmin}). */
  protected readonly dataState = signal<DataState>(DataState.LOADING);
  /** The signed-in user, used only to gate the form to ROLE_APPLICATION_ADMIN. */
  protected readonly user = signal<UserInterface | undefined>(undefined);
  /** Tracks the in-flight create request so the submit button can disable and show a spinner. */
  protected readonly isMutating = signal(false);

  /**
   * Whether the signed-in user may create a role at all.
   *
   * <p>Mirrors {@code RolesMatrixComponent#isApplicationAdmin} exactly — role catalog management
   * is reserved for {@code ROLE_APPLICATION_ADMIN}, narrower than the {@code adminGuard} tier that
   * gates the route itself.
   */
  protected get isApplicationAdmin(): boolean {
    return this.user()?.roleName === 'ROLE_APPLICATION_ADMIN';
  }

  private readonly userService = inject(UserService);
  private readonly adminUserService = inject(AdminUserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /** Loads the signed-in user, purely to evaluate {@link isApplicationAdmin} before rendering the form. */
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
  }

  /**
   * Submits the form ({@code POST /admin/role}). On success the form resets in place (matching
   * {@code NewCustomerComponent}) rather than navigating away, so creating several roles in a row
   * needs no back-and-forth to the matrix.
   *
   * @param form - the NgForm carrying {@code name} and {@code permission}
   */
  protected createRole(form: NgForm): void {
    if (!form.valid) return;
    this.isMutating.set(true);
    this.adminUserService
      .createRole$(form.value.name, form.value.permission)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isMutating.set(false);
          this.notification.onSuccess('Role created successfully');
          form.resetForm();
        },
        error: (error: string) => {
          this.isMutating.set(false);
          this.notification.onError(error);
        },
      });
  }
}
