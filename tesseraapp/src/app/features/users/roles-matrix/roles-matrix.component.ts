import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule, NgForm } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { UserService } from '../../../service/user.service';
import { AdminUserService } from '../../../service/admin-user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserInterface } from '../../../interface/user.interface';
import { RolesInterface } from '../../../interface/roles.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Roles × Permissions Matrix (SRS M3, FR-RBAC-1/2).
 *
 * Renders a grid where every row is a role and every column is a permission
 * string drawn from the full roles catalog. A filled cell means the role
 * carries that permission; an empty cell means it does not.
 *
 * The roles catalog (with their comma-delimited {@code permission} strings)
 * is already returned by {@code GET /user/profile}, so no new backend endpoint
 * is needed for the read-only grid. Role and permission ASSIGNMENT (which user
 * holds which existing role) is still done through the admin user-detail view
 * (FR-ADMIN-3) — this page is where the catalog rows themselves are managed
 * (create / edit-permission / delete), gated tighter than that: only an
 * application administrator sees the panel at all (see {@link isApplicationAdmin}).
 *
 * Authority gate: only users with UPDATE:USER or UPDATE:ROLE reach this route
 * (adminGuard), matching the existing admin surface constraint (NFR-SEC-4).
 */
@Component({
  selector: 'app-roles-matrix',
  standalone: true,
  imports: [RouterLink, FormsModule, NavbarComponent, TranslocoDirective],
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
  /** The full roles catalog as returned by the backend. */
  protected readonly roles = signal<RolesInterface[]>([]);
  /** Tracks in-flight catalog mutations so the panel's buttons can disable and show spinners. */
  protected readonly isMutating = signal(false);
  /** The role currently being edited in the permission-edit form, or {@code undefined} for none. */
  protected readonly editingRole = signal<RolesInterface | undefined>(undefined);

  /**
   * Whether the signed-in user may create, edit, or delete role-catalog rows.
   *
   * Server-side ({@code RoleController#requireApplicationAdmin}) this is an exact role-name
   * check, not an authority check — {@code ROLE_ADMIN} and {@code ROLE_APPLICATION_ADMIN} share
   * the same {@code UPDATE:ROLE} authority string, so no {@code hasAnyAuthority} call could tell
   * them apart. {@link user} already carries the calling administrator's {@code roleName} from
   * the profile response this page loads anyway, so no separate token decode is needed here —
   * the backend re-checks on every request regardless, so this only shapes the UI.
   */
  protected get isApplicationAdmin(): boolean {
    return this.user()?.roleName === 'ROLE_APPLICATION_ADMIN';
  }

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
  private readonly adminUserService = inject(AdminUserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Loads the roles catalog via the profile endpoint (cheapest source that
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
   * @param role       - one row from the roles catalog
   * @param permission - one column header permission string
   * @returns true when the role's permission string contains this permission
   */
  protected hasPermission(role: RolesInterface, permission: string): boolean {
    if (!role.permission) return false;
    return role.permission.split(',').map((p) => p.trim()).includes(permission);
  }

  /**
   * Submits the "new role" form ({@code POST /admin/role}). The created row comes back with
   * {@code assignable: false} until a redeploy adds a matching {@code RoleType} constant — the
   * refreshed catalog from the response already carries that flag, so no extra fetch is needed.
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
        next: (response) => {
          this.roles.set(response.data?.roles ?? this.roles());
          this.isMutating.set(false);
          this.notification.onSuccess('Role created successfully');
          form.resetForm();
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /** Opens the permission-edit form for one catalog row. The role name itself is immutable. */
  protected startEditPermission(role: RolesInterface): void {
    this.editingRole.set(role);
  }

  /** Closes the permission-edit form without saving. */
  protected cancelEditPermission(): void {
    this.editingRole.set(undefined);
  }

  /**
   * Submits the permission-edit form ({@code PATCH /admin/role/:id}).
   *
   * @param form - the NgForm carrying the replacement {@code permission} string
   */
  protected saveEditPermission(form: NgForm): void {
    const id = this.editingRole()?.id;
    if (!id || !form.valid) return;
    this.isMutating.set(true);
    this.adminUserService
      .updateRolePermission$(id, form.value.permission)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.roles.set(response.data?.roles ?? this.roles());
          this.isMutating.set(false);
          this.editingRole.set(undefined);
          this.notification.onSuccess('Role updated successfully');
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Deletes a catalog row ({@code DELETE /admin/role/:id}). No confirmation dialog — a browser
   * {@code confirm()} would block the extension-driven flows this project uses (same reasoning
   * as {@code UserDetailsComponent#revokeSessions}) — and the backend already refuses to delete
   * any built-in {@code RoleType} role or one any user currently holds, so the worst case this
   * button can cause is deleting an unused, freshly-created catalog row.
   *
   * @param role - the role to delete
   */
  protected deleteRole(role: RolesInterface): void {
    if (!role.id) return;
    this.isMutating.set(true);
    this.adminUserService
      .deleteRole$(role.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.roles.set(response.data?.roles ?? this.roles());
          this.isMutating.set(false);
          this.notification.onSuccess('Role deleted successfully');
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /** Surfaces a catalog-mutation failure as a toast without touching {@link roles}. */
  private failMutation(error: string): void {
    this.isMutating.set(false);
    this.notification.onError(error);
  }
}
