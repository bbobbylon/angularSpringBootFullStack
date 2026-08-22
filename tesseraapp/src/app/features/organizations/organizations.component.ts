import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs/operators';
import { NavbarComponent } from '../../shared/navbar/navbar.component';
import { UserService } from '../../service/user.service';
import { OrganizationService } from '../../service/organization.service';
import { AdminUserService } from '../../service/admin-user.service';
import { NotificationsService } from '../../service/notifications-service';
import { DataState } from '../../enumeration/datastate.enum';
import { UserInterface } from '../../interface/user.interface';
import { OrganizationInterface } from '../../interface/organization.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Organization administration — the Angular half of Organization CRUD + membership
 * management (FUTURE-ENHANCEMENTS.md §3.2, {@code OrganizationController}).
 *
 * <h3>Two authorization tiers, one page</h3>
 * The backend enforces two different rules per endpoint family — see
 * {@code OrganizationController}'s Javadoc — and this component mirrors that split rather than
 * hiding it: the create-organization form and the rename/status controls only render for
 * {@link isUnscopedTier} ({@code ROLE_ADMIN}/{@code ROLE_APPLICATION_ADMIN}), matching
 * {@code OrganizationController#requireUnscopedTier}. Membership management (view/add/remove
 * members) renders for every organization row the page loaded, because {@code GET
 * /admin/organization} already scopes a {@code ROLE_ORGANIZATION_ADMIN} caller down to only the
 * organizations they actively belong to — the exact set {@code requireMembershipAuthority} would
 * let them manage, so no separate per-row permission check is needed here.
 *
 * <h3>Finding a user to add</h3>
 * {@code OrganizationController#addMember} takes a bare {@code userId}, and there is no
 * autocomplete endpoint of its own. Rather than making an administrator type a raw numeric id,
 * the add-member panel reuses {@link AdminUserService#users$} — the same directory search the
 * Users dashboard uses — which already narrows its results to an organization admin's own scope
 * server-side, so the candidate list an org admin sees here is never wider than who they could
 * legitimately add anyway.
 *
 * Authority gate: only users with {@code UPDATE:USER} or {@code UPDATE:ROLE} reach this route
 * (adminGuard) — every tier {@code UPDATE:ORGANIZATION} was granted to already holds one of
 * those two (schema.sql's 2026-08-21 grant note), so this is not an additional restriction.
 */
@Component({
  selector: 'app-organizations',
  standalone: true,
  imports: [FormsModule, RouterLink, NavbarComponent, TranslocoDirective],
  templateUrl: './organizations.component.html',
  styleUrl: './organizations.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrganizationsComponent implements OnInit {
  /** Template access to DataState for skeleton/error rendering. */
  readonly DataState = DataState;
  /** Page load state. */
  protected readonly dataState = signal<DataState>(DataState.LOADING);
  /** The signed-in user — passed to the navbar and used to gate the catalog-mutation panel. */
  protected readonly user = signal<UserInterface | undefined>(undefined);
  /** The in-scope organization catalog: everything for an unscoped tier, own orgs otherwise. */
  protected readonly organizations = signal<OrganizationInterface[]>([]);
  /** Tracks in-flight catalog/membership mutations so buttons can disable and show spinners. */
  protected readonly isMutating = signal(false);
  /** The organization currently being renamed inline, or {@code undefined} for none. */
  protected readonly editingOrg = signal<OrganizationInterface | undefined>(undefined);
  /** The organization whose membership panel is expanded, or {@code undefined} for none. */
  protected readonly expandedOrgId = signal<number | undefined>(undefined);
  /** Active members of {@link expandedOrgId}, once loaded. */
  protected readonly members = signal<UserInterface[]>([]);
  /** Load state of the membership panel, separate from the page-level {@link dataState}. */
  protected readonly membersState = signal<DataState>(DataState.LOADING);
  /** Directory matches for the current add-member search term. */
  protected readonly memberCandidates = signal<UserInterface[]>([]);

  /**
   * Whether the signed-in user may create, rename, or retire/reinstate organizations.
   *
   * <p>Server-side ({@code OrganizationController#requireUnscopedTier}) this excludes
   * {@code ROLE_ORGANIZATION_ADMIN} specifically, even though that role also carries
   * {@code UPDATE:ORGANIZATION} — the authority string alone cannot express the narrower rule,
   * so the frontend gate is spelled out the same way the backend's is: by role name, not by
   * authority.
   */
  protected get isUnscopedTier(): boolean {
    const role = this.user()?.roleName;
    return role === 'ROLE_ADMIN' || role === 'ROLE_APPLICATION_ADMIN';
  }

  private readonly userService = inject(UserService);
  private readonly organizationService = inject(OrganizationService);
  private readonly adminUserService = inject(AdminUserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /** Raw keystrokes from the add-member search input; debounced below before hitting the API. */
  private readonly memberSearchInput$ = new Subject<string>();

  /**
   * Loads the signed-in user (for the navbar and {@link isUnscopedTier}) and the organization
   * catalog in parallel, then wires the debounced add-member search pipeline.
   */
  ngOnInit(): void {
    forkJoin({
      profile: this.userService.profile$(),
      catalog: this.organizationService.organizations$(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ profile, catalog }) => {
          this.user.set(profile.data?.user);
          this.organizations.set(catalog.data?.organizations ?? []);
          this.dataState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.dataState.set(DataState.ERROR);
        },
      });

    this.memberSearchInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        filter((term) => term.length === 0 || term.length >= 3),
        switchMap((term) => this.adminUserService.users$(0, term, 5)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => this.memberCandidates.set(response.data?.users ?? []),
        error: (error: string) => this.notification.onError(error),
      });
  }

  /**
   * Submits the "new organization" form ({@code POST /admin/organization}).
   *
   * @param form - the NgForm carrying {@code name}
   */
  protected createOrganization(form: NgForm): void {
    if (!form.valid) return;
    this.isMutating.set(true);
    this.organizationService
      .createOrganization$(form.value.name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.organizations.set(response.data?.organizations ?? this.organizations());
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Organization created successfully');
          form.resetForm();
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /** Opens the rename form for one catalog row. */
  protected startRename(org: OrganizationInterface): void {
    this.editingOrg.set(org);
  }

  /** Closes the rename form without saving. */
  protected cancelRename(): void {
    this.editingOrg.set(undefined);
  }

  /**
   * Submits the rename form ({@code PATCH /admin/organization/:id/name}).
   *
   * @param form - the NgForm carrying the replacement {@code name}
   */
  protected saveRename(form: NgForm): void {
    const id = this.editingOrg()?.id;
    if (!id || !form.valid) return;
    this.isMutating.set(true);
    this.organizationService
      .renameOrganization$(id, form.value.name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.organizations.set(response.data?.organizations ?? this.organizations());
          this.isMutating.set(false);
          this.editingOrg.set(undefined);
          this.notification.onSuccess(response.message ?? 'Organization renamed successfully');
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Activates or deactivates an organization ({@code PATCH /admin/organization/:id/status}).
   *
   * <p>No confirmation dialog: the toggle is fully reversible with the same button, mirroring
   * {@code ServicesAdminComponent#toggleActive} — a prompt belongs on actions that cannot be
   * undone in one more click, and this one can.
   *
   * @param org - the organization to retire or reinstate
   */
  protected toggleStatus(org: OrganizationInterface): void {
    if (!org.id || this.isMutating()) return;
    this.isMutating.set(true);
    const next = org.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    this.organizationService
      .setOrganizationStatus$(org.id, next)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.organizations.set(response.data?.organizations ?? this.organizations());
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Organization updated successfully');
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Expands (loading its members) or collapses the membership panel for one organization.
   * Only one panel is ever open, mirroring {@link editingOrg}'s single-form-at-a-time shape.
   *
   * @param org - the organization row whose panel was clicked
   */
  protected toggleMembersPanel(org: OrganizationInterface): void {
    if (!org.id) return;
    if (this.expandedOrgId() === org.id) {
      this.expandedOrgId.set(undefined);
      return;
    }
    this.expandedOrgId.set(org.id);
    this.memberCandidates.set([]);
    this.loadMembers(org.id);
  }

  /** Pushes each keystroke from the add-member search input into the debounced pipeline. */
  protected onMemberSearchInput(term: string): void {
    this.memberSearchInput$.next(term);
  }

  /**
   * Adds a candidate user to the currently expanded organization
   * ({@code POST /admin/organization/:id/members/:userId}), then refreshes the member list.
   *
   * @param candidate - a search result from {@link memberCandidates}
   */
  protected addMember(candidate: UserInterface): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || this.isMutating()) return;
    this.isMutating.set(true);
    this.organizationService
      .addMember$(organizationId, candidate.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Member added successfully');
          this.loadMembers(organizationId);
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Removes a member from the currently expanded organization
   * ({@code DELETE /admin/organization/:id/members/:userId}), then refreshes the member list.
   *
   * <p>No confirmation dialog: removal only deactivates the membership row
   * ({@code OrganizationService#removeMember}'s Javadoc), and {@link addMember} reactivates it
   * in one click, so this is reversible the same way {@link toggleStatus} is.
   *
   * @param member - a row from {@link members}
   */
  protected removeMember(member: UserInterface): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || this.isMutating()) return;
    this.isMutating.set(true);
    this.organizationService
      .removeMember$(organizationId, member.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Member removed successfully');
          this.loadMembers(organizationId);
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /** Fetches the active member list for one organization into {@link members}. */
  private loadMembers(organizationId: number): void {
    this.membersState.set(DataState.LOADING);
    this.organizationService
      .members$(organizationId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.members.set(response.data?.members ?? []);
          this.membersState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.membersState.set(DataState.ERROR);
        },
      });
  }

  /** Surfaces a mutation failure as a toast without touching whichever signal was being changed. */
  private failMutation(error: string): void {
    this.isMutating.set(false);
    this.notification.onError(error);
  }
}
