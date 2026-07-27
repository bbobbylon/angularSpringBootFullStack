import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserInterface } from '../../../interface/user.interface';
import { RolesInterface } from '../../../interface/roles.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Roles × Permissions Matrix (SRS M3, FR-RBAC-1/2).
 *
 * Renders a grid where every row is a role and every column is a permission
 * string drawn from the full roles catalogue. A filled cell means the role
 * carries that permission; an empty cell means it does not.
 *
 * The roles catalogue (with their comma-delimited {@code permission} strings)
 * is already returned by {@code GET /user/profile}, so no new backend endpoint
 * is needed. The matrix is read-only — role and permission assignment is done
 * through the admin user-detail view (FR-ADMIN-3).
 *
 * Authority gate: only users with UPDATE:USER or UPDATE:ROLE reach this route
 * (adminGuard), matching the existing admin surface constraint (NFR-SEC-4).
 */
@Component({
  selector: 'app-roles-matrix',
  standalone: true,
  imports: [RouterLink, NavbarComponent, TranslocoDirective],
  templateUrl: './roles-matrix.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RolesMatrixComponent implements OnInit {
  /** Template access to DataState for skeleton/error rendering. */
  readonly DataState = DataState;
  /** Page load state. */
  protected readonly dataState = signal<DataState>(DataState.LOADING);
  /** The signed-in user — passed to the navbar. */
  protected readonly user = signal<UserInterface | undefined>(undefined);
  /** The full roles catalogue as returned by the backend. */
  protected readonly roles = signal<RolesInterface[]>([]);

  /**
   * Sorted, de-duplicated list of all permission strings across every role.
   * Drives the column headers; computed once from {@link roles}.
   */
  protected readonly allPermissions = computed<string[]>(() => {
    const perms = new Set<string>();
    for (const role of this.roles()) {
      if (role.permission) {
        role.permission.split(',').map((p) => p.trim()).filter(Boolean).forEach((p) => perms.add(p));
      }
    }
    return [...perms].sort();
  });

  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Loads the roles catalogue via the profile endpoint (cheapest source that
   * already returns the full role list with permission strings).
   */
  ngOnInit(): void {
    this.userService
      .profile$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.roles.set(response.data?.roles ?? []);
          this.dataState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.dataState.set(DataState.ERROR);
        },
      });
  }

  /**
   * Whether the given role grants the given permission string.
   * Called from the template to fill each matrix cell.
   *
   * @param role       - one row from the roles catalogue
   * @param permission - one column header permission string
   * @returns true when the role's permission string contains this permission
   */
  protected hasPermission(role: RolesInterface, permission: string): boolean {
    if (!role.permission) return false;
    return role.permission.split(',').map((p) => p.trim()).includes(permission);
  }
}
