import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin, of, Subject } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs/operators';
import { NavbarComponent } from '../../shared/navbar/navbar.component';
import { StatsComponent } from '../../shared/stats/stats.component';
import { UserService } from '../../service/user.service';
import { OrganizationService } from '../../service/organization.service';
import { AdminUserService } from '../../service/admin-user.service';
import { NotificationsService } from '../../service/notifications-service';
import { DataState } from '../../enumeration/datastate.enum';
import { UserInterface } from '../../interface/user.interface';
import {
  OrganizationEventInterface,
  OrganizationInterface,
  OrganizationInviteInterface,
  OrganizationStatsInterface,
} from '../../interface/organization.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/** The four tabs of an expanded organization card, mirroring the endpoint families that back them. */
type OrgTab = 'members' | 'profile' | 'activity' | 'invites';

/** Rows per page for an expanded card's Activity tab ({@code OrganizationService#orgEvents$}). */
const EVENTS_PAGE_SIZE = 10;

/**
 * Organization administration — the Angular half of Organization CRUD + membership management,
 * profile editing, audit trail, and self-service invites (FUTURE-ENHANCEMENTS.md §3.2,
 * {@code OrganizationController}, {@code OrganizationInviteController}).
 *
 * <h3>Dashboard-style overview (2026-08-22 revamp)</h3>
 * The page opened as a bare table; it is now a KPI row ({@link catalogStats}, reusing
 * {@code app-stats}'s `.sc-metric` styling) over a responsive card grid — one {@code .sc-panel}
 * per organization, its own mini stat row from {@code GET /admin/organization/:id/stats}, and an
 * expandable tabbed body (Members / Profile / Activity / Invites) in place of the old single
 * inline member row. Every organization's stats are fetched once, in parallel, right after the
 * catalog loads ({@link loadAllStats}) — both to drive the top KPI row and so a card's mini stat
 * row never shows a loading flicker on first expand.
 *
 * <h3>Two authorization tiers, one page</h3>
 * The backend enforces two different rules per endpoint family — see
 * {@code OrganizationController}'s Javadoc — and this component mirrors that split rather than
 * hiding it: the create-organization form, the rename/status controls, and the Profile tab only
 * render for {@link isUnscopedTier} ({@code ROLE_ADMIN}/{@code ROLE_APPLICATION_ADMIN}), matching
 * {@code OrganizationController#requireUnscopedTier}. Membership management, Activity, and
 * Invites render for every organization row the page loaded, because {@code GET
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
 * <h3>Assignable roles</h3>
 * The Members-tab role {@code <select>} and the Invites-tab role picker both offer the same fixed
 * {@link ASSIGNABLE_ROLES} list — the roles that actually make sense inside an organization
 * context. The backend's {@code RoleType.canAssign} tier ceiling is the real gate: a caller who
 * cannot grant a listed role still gets refused server-side (surfaced as a toast), so this list is
 * a UX narrowing, not a security boundary.
 *
 * <h3>Per-organization roles (2026-08-27 frontend)</h3>
 * {@code userorganizations.org_role} — the member's capacity within <em>this one</em>
 * organization, distinct from the global role the selector above reassigns — gets its own
 * {@code <select>} in the Members tab ({@link assignOrgRole}) and its own picker in the add-member
 * panel ({@link addMemberOrgRole}, threaded through {@code addMember$}'s optional {@code orgRole}
 * param). {@link ASSIGNABLE_ORG_ROLES} always offers all three capacities — unlike
 * {@link ASSIGNABLE_ROLES} it is not narrowed for UX, since {@code OrgRole.canAssign}'s
 * ceiling already refuses whatever a scoped caller may not grant. The invite flow deliberately
 * does <em>not</em> get an independent org-role field: an invite already derives one from its
 * {@code roleName} at redemption ({@code OrgRole#fromInvitedGlobalRole}), and a second, competing
 * source of truth on the same invite would only invite drift between them.
 *
 * Authority gate: only users with {@code UPDATE:USER} or {@code UPDATE:ROLE} reach this route
 * (adminGuard) — every tier {@code UPDATE:ORGANIZATION} was granted to already holds one of
 * those two (schema.sql's 2026-08-21 grant note), so this is not an additional restriction.
 */
@Component({
  selector: 'app-organizations',
  standalone: true,
  imports: [FormsModule, RouterLink, NavbarComponent, StatsComponent, DatePipe, TranslocoDirective],
  templateUrl: './organizations.component.html',
  styleUrl: './organizations.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrganizationsComponent implements OnInit {
  /** Template access to DataState for skeleton/error rendering. */
  readonly DataState = DataState;
  /** Roles offered by the Members-tab reassign control and the Invites-tab role picker. */
  readonly ASSIGNABLE_ROLES: readonly string[] = ['ROLE_USER', 'ROLE_MODERATOR', 'ROLE_ORGANIZATION_ADMIN'];
  /**
   * Capacities offered by the Members-tab per-row org-role selector and the add-member panel's
   * org-role picker ({@code userorganizations.org_role} — {@link OrgRole} server-side). Unlike
   * {@link ASSIGNABLE_ROLES}, this list is not narrowed for UX — all three are always offered,
   * and {@code OrgRole.canAssign}'s "not above your own tier" ceiling is the real gate,
   * surfaced as a toast on refusal exactly like the global-role picker's tier ceiling.
   */
  readonly ASSIGNABLE_ORG_ROLES: readonly string[] = ['ORG_VIEWER', 'ORG_MEMBER', 'ORG_ADMIN'];
  /** Page load state. */
  protected readonly dataState = signal<DataState>(DataState.LOADING);
  /** The signed-in user — passed to the navbar and used to gate the catalog-mutation panel. */
  protected readonly user = signal<UserInterface | undefined>(undefined);
  /** The in-scope organization catalog: everything for an unscoped tier, own orgs otherwise. */
  protected readonly organizations = signal<OrganizationInterface[]>([]);
  /** Per-organization KPI tiles, keyed by organization id — fetched once, right after the catalog. */
  protected readonly orgStats = signal<Record<number, OrganizationStatsInterface>>({});
  /** Tracks in-flight catalog/membership mutations so buttons can disable and show spinners. */
  protected readonly isMutating = signal(false);
  /** The organization currently being renamed inline, or {@code undefined} for none. */
  protected readonly editingOrg = signal<OrganizationInterface | undefined>(undefined);
  /** The organization whose card is expanded, or {@code undefined} for none. */
  protected readonly expandedOrgId = signal<number | undefined>(undefined);
  /** Which tab of the expanded card is showing. */
  protected readonly activeTab = signal<OrgTab>('members');
  /** Active members of {@link expandedOrgId}, once loaded. */
  protected readonly members = signal<UserInterface[]>([]);
  /**
   * Each active member's capacity in {@link expandedOrgId}, keyed by user id — loaded alongside
   * {@link members} rather than folded into {@code UserInterface} (see
   * {@code OrganizationCatalogInterface#orgRoles}'s Javadoc). Absent for a member the backend
   * could not resolve a recognized role for; the template falls back to {@code ORG_MEMBER}, the
   * same default a fresh membership row takes.
   */
  protected readonly memberOrgRoles = signal<Record<number, string>>({});
  /** Load state of the Members tab, separate from the page-level {@link dataState}. */
  protected readonly membersState = signal<DataState>(DataState.LOADING);
  /** Directory matches for the current add-member search term. */
  protected readonly memberCandidates = signal<UserInterface[]>([]);
  /** The org role the add-member panel's picker currently has selected, for the next {@link addMember} call. */
  protected readonly addMemberOrgRole = signal<string>('ORG_MEMBER');
  /** Audit-trail rows for the Activity tab's current page. */
  protected readonly orgEvents = signal<OrganizationEventInterface[]>([]);
  /** Load state of the Activity tab. */
  protected readonly orgEventsState = signal<DataState>(DataState.LOADING);
  /** Zero-based page index of the Activity tab. */
  protected readonly orgEventsPage = signal(0);
  /** Total event count backing the Activity tab's pager. */
  protected readonly orgEventsTotal = signal(0);
  /** Outstanding invites for the Invites tab. */
  protected readonly orgInvites = signal<OrganizationInviteInterface[]>([]);
  /** Load state of the Invites tab. */
  protected readonly orgInvitesState = signal<DataState>(DataState.LOADING);

  /**
   * Catalog-wide KPI row: organization count, active count, and total members across every
   * in-scope organization once its stats have arrived. Derived rather than fetched — no
   * dedicated "catalog totals" endpoint exists, and every input is already loaded for the card
   * grid's own mini stat rows ({@link loadAllStats}).
   */
  protected readonly catalogStats = computed(() => {
    const orgs = this.organizations();
    const stats = this.orgStats();
    return {
      totalOrganizations: orgs.length,
      activeOrganizations: orgs.filter((org) => org.status === 'ACTIVE').length,
      totalMembers: orgs.reduce((sum, org) => sum + (org.id !== undefined ? (stats[org.id]?.memberCount ?? 0) : 0), 0),
    };
  });

  /**
   * Whether the signed-in user may create, rename, or retire/reinstate organizations, and edit an
   * organization's profile.
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
   * catalog in parallel, then fetches every organization's KPI tiles and wires the debounced
   * add-member search pipeline.
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
          const organizations = catalog.data?.organizations ?? [];
          this.organizations.set(organizations);
          this.dataState.set(DataState.LOADED);
          this.loadAllStats(organizations);
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
   * Fetches KPI tiles for every organization in parallel and indexes them by id into
   * {@link orgStats}. A single failed lookup is swallowed (logged via the notification service's
   * error path is skipped here on purpose): the catalog itself already loaded successfully, and
   * one organization's stats being unavailable shouldn't blank out the whole page — its card
   * simply renders without a mini stat row.
   *
   * @param organizations - the in-scope catalog just loaded into {@link organizations}
   */
  private loadAllStats(organizations: OrganizationInterface[]): void {
    const ids = organizations.map((org) => org.id).filter((id): id is number => id !== undefined);
    if (ids.length === 0) return;
    forkJoin(
      ids.map((id) =>
        this.organizationService.orgStats$(id).pipe(
          catchError(() => of(null)),
        ),
      ),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((responses) => {
        const next: Record<number, OrganizationStatsInterface> = { ...this.orgStats() };
        responses.forEach((response, index) => {
          const stats = response?.data?.stats;
          if (stats) next[ids[index]] = stats;
        });
        this.orgStats.set(next);
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
          const organizations = response.data?.organizations ?? this.organizations();
          this.organizations.set(organizations);
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Organization created successfully');
          form.resetForm();
          this.loadAllStats(organizations);
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

  /** Returns one organization's KPI tiles, or {@code undefined} until {@link loadAllStats} resolves. */
  protected statsFor(organizationId: number | undefined): OrganizationStatsInterface | undefined {
    return organizationId === undefined ? undefined : this.orgStats()[organizationId];
  }

  /** Returns the currently expanded card's organization row, or {@code undefined} if none is expanded. */
  protected expandedOrg(): OrganizationInterface | undefined {
    const id = this.expandedOrgId();
    return id === undefined ? undefined : this.organizations().find((org) => org.id === id);
  }

  /**
   * Expands (loading its Members tab) or collapses an organization's card. Only one card is ever
   * open, mirroring {@link editingOrg}'s single-form-at-a-time shape. Always lands back on the
   * Members tab and clears the other tabs' stale data so switching organizations never flashes
   * the previous card's activity or invites.
   *
   * @param org - the organization card that was clicked
   */
  protected toggleCard(org: OrganizationInterface): void {
    if (!org.id) return;
    if (this.expandedOrgId() === org.id) {
      this.expandedOrgId.set(undefined);
      return;
    }
    this.expandedOrgId.set(org.id);
    this.activeTab.set('members');
    this.memberCandidates.set([]);
    this.addMemberOrgRole.set('ORG_MEMBER');
    this.orgEvents.set([]);
    this.orgEventsPage.set(0);
    this.orgInvites.set([]);
    this.loadMembers(org.id);
  }

  /**
   * Switches the expanded card's active tab, lazily loading that tab's data the first time it is
   * shown (Activity and Invites are not needed until viewed, unlike Members which loads eagerly
   * on expand).
   *
   * @param tab - the tab to show
   */
  protected selectTab(tab: OrgTab): void {
    this.activeTab.set(tab);
    const organizationId = this.expandedOrgId();
    if (!organizationId) return;
    if (tab === 'activity') this.loadEvents(organizationId, 0);
    if (tab === 'invites') this.loadInvites(organizationId);
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
      .addMember$(organizationId, candidate.id, this.addMemberOrgRole())
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

  /**
   * Reassigns a member's role from the Members tab
   * ({@code PATCH /admin/user/:id/role/:roleName?organizationId=...}), then refreshes the member
   * list. The {@code organizationId} hint lets the backend record an
   * {@code ORG_MEMBER_ROLE_CHANGED} audit event on this organization's Activity tab, once it
   * verifies the target is actually an active member here.
   *
   * @param member   - a row from {@link members}
   * @param roleName - the newly selected role
   */
  protected assignRole(member: UserInterface, roleName: string): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || !member.id || this.isMutating()) return;
    this.isMutating.set(true);
    this.adminUserService
      .updateUserRole$(member.id, roleName, undefined, organizationId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Role updated successfully');
          this.loadMembers(organizationId);
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /** Fetches the active member list, and each member's org role, for one organization. */
  private loadMembers(organizationId: number): void {
    this.membersState.set(DataState.LOADING);
    this.organizationService
      .members$(organizationId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.members.set(response.data?.members ?? []);
          this.memberOrgRoles.set(response.data?.orgRoles ?? {});
          this.membersState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.membersState.set(DataState.ERROR);
        },
      });
  }

  /** Returns one member's org role, defaulting to {@code ORG_MEMBER} — see {@link memberOrgRoles}. */
  protected orgRoleFor(member: UserInterface): string {
    return (member.id !== undefined && this.memberOrgRoles()[member.id]) || 'ORG_MEMBER';
  }

  /**
   * Reassigns a member's capacity within the currently expanded organization
   * ({@code PATCH /admin/organization/:id/members/:userId/role}), then refreshes the member list
   * so {@link memberOrgRoles} reflects the change. Distinct from {@link assignRole}, which
   * reassigns the member's global role instead.
   *
   * @param member  - a row from {@link members}
   * @param orgRole - the newly selected capacity
   */
  protected assignOrgRole(member: UserInterface, orgRole: string): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || !member.id || this.isMutating()) return;
    this.isMutating.set(true);
    this.organizationService
      .setMemberOrgRole$(organizationId, member.id, orgRole)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Member role updated successfully');
          this.loadMembers(organizationId);
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Submits the Profile tab's form ({@code PATCH /admin/organization/:id/profile}), then splices
   * the returned organization back into {@link organizations} so the card's header reflects the
   * new fields without a full catalog refetch.
   *
   * @param form - the NgForm carrying {@code description}/{@code contactEmail}/{@code website}
   */
  protected saveProfile(form: NgForm): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || this.isMutating()) return;
    this.isMutating.set(true);
    this.organizationService
      .updateOrganizationProfile$(organizationId, form.value.description, form.value.contactEmail, form.value.website)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          const updated = response.data?.organization;
          if (updated) {
            this.organizations.set(this.organizations().map((org) => (org.id === organizationId ? updated : org)));
          }
          this.notification.onSuccess(response.message ?? 'Organization profile updated successfully');
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Fetches one page of the Activity tab ({@code GET /admin/organization/:id/events}).
   *
   * @param organizationId - the expanded organization
   * @param page            - zero-based page index
   */
  protected loadEvents(organizationId: number, page: number): void {
    this.orgEventsState.set(DataState.LOADING);
    this.organizationService
      .orgEvents$(organizationId, page, EVENTS_PAGE_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.orgEvents.set(response.data?.events ?? []);
          this.orgEventsTotal.set(response.data?.totalEvents ?? 0);
          this.orgEventsPage.set(page);
          this.orgEventsState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.orgEventsState.set(DataState.ERROR);
        },
      });
  }

  /** Total pages backing the Activity tab's pager, for display alongside the current page. */
  protected eventsTotalPages(): number {
    return Math.max(1, Math.ceil(this.orgEventsTotal() / EVENTS_PAGE_SIZE));
  }

  /** Advances the Activity tab to its next page, if one exists. */
  protected nextEventsPage(): void {
    const organizationId = this.expandedOrgId();
    const nextPage = this.orgEventsPage() + 1;
    if (!organizationId || nextPage * EVENTS_PAGE_SIZE >= this.orgEventsTotal()) return;
    this.loadEvents(organizationId, nextPage);
  }

  /** Steps the Activity tab back to its previous page, if not already on the first. */
  protected previousEventsPage(): void {
    const organizationId = this.expandedOrgId();
    const previousPage = this.orgEventsPage() - 1;
    if (!organizationId || previousPage < 0) return;
    this.loadEvents(organizationId, previousPage);
  }

  /** Fetches the Invites tab's outstanding invite list ({@code GET /admin/organization/:id/invites}). */
  protected loadInvites(organizationId: number): void {
    this.orgInvitesState.set(DataState.LOADING);
    this.organizationService
      .activeInvites$(organizationId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.orgInvites.set(response.data?.invites ?? []);
          this.orgInvitesState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.orgInvitesState.set(DataState.ERROR);
        },
      });
  }

  /**
   * Submits the "create invite" form ({@code POST /admin/organization/:id/invites}). A role the
   * caller cannot actually grant is refused server-side (the {@code RoleType.canAssign} tier
   * ceiling) and surfaces as an error toast via the normal {@link failMutation} path.
   *
   * @param form - the NgForm carrying {@code roleName} and optional {@code ttlHours}
   */
  protected createInvite(form: NgForm): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || this.isMutating()) return;
    this.isMutating.set(true);
    const ttlHours = form.value.ttlHours ? Number(form.value.ttlHours) : undefined;
    this.organizationService
      .createInvite$(organizationId, form.value.roleName, ttlHours)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.orgInvites.set(response.data?.invites ?? this.orgInvites());
          this.notification.onSuccess(response.message ?? 'Invite created successfully');
          form.resetForm();
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Revokes an outstanding invite before it is redeemed
   * ({@code DELETE /admin/organization/:id/invites/:inviteId}).
   *
   * <p>No confirmation dialog: an accidental revoke costs one more click to re-invite, the same
   * reversibility bar {@link toggleStatus} and {@link removeMember} already use on this page.
   *
   * @param invite - a row from {@link orgInvites}
   */
  protected revokeInvite(invite: OrganizationInviteInterface): void {
    const organizationId = this.expandedOrgId();
    if (!organizationId || !invite.id || this.isMutating()) return;
    this.isMutating.set(true);
    this.organizationService
      .revokeInvite$(organizationId, invite.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.orgInvites.set(response.data?.invites ?? this.orgInvites());
          this.notification.onSuccess(response.message ?? 'Invite revoked successfully');
        },
        error: (error: string) => this.failMutation(error),
      });
  }

  /**
   * Copies an invite's shareable join link to the clipboard
   * ({@code /organizations/join/:code}, the route {@link OrganizationJoinComponent} handles).
   *
   * @param invite - a row from {@link orgInvites}
   */
  protected copyInviteLink(invite: OrganizationInviteInterface): void {
    if (!invite.code) return;
    const link = `${window.location.origin}/organizations/join/${invite.code}`;
    navigator.clipboard
      .writeText(link)
      .then(() => this.notification.onSuccess('Invite link copied to clipboard'))
      .catch(() => this.notification.onError('Could not copy the invite link'));
  }

  /** Surfaces a mutation failure as a toast without touching whichever signal was being changed. */
  private failMutation(error: string): void {
    this.isMutating.set(false);
    this.notification.onError(error);
  }
}
