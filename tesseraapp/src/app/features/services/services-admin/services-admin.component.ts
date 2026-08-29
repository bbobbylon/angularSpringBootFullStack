import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { ServicesCatalogService, ServicesListDataInterface } from '../../../service/services-catalog.service';
import { OrganizationService } from '../../../service/organization.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { OrganizationInterface } from '../../../interface/organization.interface';
import { UserInterface } from '../../../interface/user.interface';
import { PAGE_SIZE_OPTIONS, PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Administrative management of the services catalog — {@code /services/manage}
 * (ROADMAP §2 — "Create / manage services").
 *
 * <h3>Why this is a separate page from the catalog</h3>
 * The browse page at {@code /services} reads {@code GET /services/public} (via {@code
 * ServicesCatalogService#listPublic$}), which returns <em>active</em> services only — correctly,
 * since its job is to show what can be put on an invoice and what a logged-out visitor sees.
 * An administrator needs the opposite: the full catalog including retired entries, so
 * they can see why something has disappeared from the invoice form and reinstate it. Bolting an
 * edit mode onto the browse page would have meant either showing users retired services or showing
 * administrators an incomplete catalog. Two audiences, two questions, two screens.
 *
 * <h3>Retire, never delete</h3>
 * There is deliberately no delete action. Invoices copy a service's name and price into their own
 * line items when raised, so removing the catalog row would not corrupt historical invoices — but
 * it would erase the catalog's own history and turn "bring that offering back" into a retyping
 * exercise. Retirement is reversible; deletion is not, and nothing here needs to be irreversible.
 *
 * <h3>State handling</h3>
 * Every mutation refreshes from the server rather than patching the local list. The catalog is a
 * short list read once, so the extra round trip is invisible, and it removes a whole category of
 * bug where the screen and the database quietly disagree after a partial failure.
 *
 * <h3>Per-organization catalogs (2026-08-28)</h3>
 * {@code GET /admin/services/list} now returns a mix of globally shared entries and entries
 * privately owned by one organization — see {@code ServicesCatalogController}'s class Javadoc. The
 * table shows each row's ownership so the two are never confused for one flat list, and the create
 * form asks {@link isUnscopedTier} to decide what it offers: an unscoped caller may leave the new
 * entry global or pick any organization; a scoped caller ({@code ROLE_ORGANIZATION_ADMIN}/
 * {@code ROLE_HELP_DESK_ADMIN}, the only org-scoped roles that can even reach this
 * {@code UPDATE:USER}-gated page) must pick one of their own active organizations — there is no
 * "leave it global" option, matching the backend's refusal. Ownership is immutable after creation,
 * so the edit form has no organization control at all, matching {@code ServicesCatalogServiceImpl
 * #updateService}'s deliberate exclusion of the field.
 */
@Component({
  selector: 'app-services-admin',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, FormsModule, TranslocoDirective, PageSizeSelectComponent],
  templateUrl: './services-admin.component.html',
  styleUrl: './services-admin.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServicesAdminComponent implements OnInit {
  readonly DataState = DataState;

  private readonly catalog = inject(ServicesCatalogService);
  private readonly organizationService = inject(OrganizationService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);

  protected readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<ServicesListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  protected readonly user = computed<UserInterface | undefined>(() => this.pageState().appData?.data?.user);

  protected readonly services = computed<ServicesInterface[]>(() => this.pageState().appData?.data?.services ?? []);

  /**
   * Whether the signed-in user may create a globally shared catalog entry, mirroring
   * {@code ServicesCatalogController#requireManageable}'s refusal of a scoped caller creating one.
   * Spelled out by role name rather than authority, the same reasoning
   * {@code OrganizationsComponent#isUnscopedTier} documents: the narrower server-side rule cannot be
   * expressed by the {@code UPDATE:USER}/{@code UPDATE:ROLE} authority strings alone.
   */
  protected get isUnscopedTier(): boolean {
    const role = this.user()?.roleName;
    return role === 'ROLE_ADMIN' || role === 'ROLE_APPLICATION_ADMIN';
  }

  /**
   * The organizations the signed-in user may create a service for: every active organization for
   * an unscoped caller, or only the ones they actively belong to for a scoped caller — exactly what
   * {@code GET /admin/organization} already returns per-caller, reused here rather than re-derived.
   */
  protected readonly myOrganizations = signal<OrganizationInterface[]>([]);

  /** {@link myOrganizations} id → name, for rendering each catalog row's ownership. */
  protected readonly organizationNameById = computed<Record<number, string>>(() => {
    const map: Record<number, string> = {};
    for (const org of this.myOrganizations()) {
      if (org.id !== undefined) map[org.id] = org.name ?? `Organization #${org.id}`;
    }
    return map;
  });

  /**
   * Renders a catalog row's ownership: the owning organization's name when known, a generic
   * fallback when it is owned by an organization outside {@link myOrganizations} (an unscoped
   * caller viewing another organization's entry before that organization's name has loaded), or
   * {@code undefined} for a globally shared entry — the template treats {@code undefined} as
   * "Shared".
   *
   * @param service - the row being rendered
   * @returns the owning organization's display name, or undefined when the entry is global
   */
  protected ownerLabel(service: ServicesInterface): string | undefined {
    if (service.organizationId === undefined) return undefined;
    return this.organizationNameById()[service.organizationId] ?? `Organization #${service.organizationId}`;
  }

  /** The organization selected on the create form; undefined means "global/shared". */
  protected readonly createOrganizationId = signal<number | undefined>(undefined);

  protected readonly activeServices = computed(() => this.services().filter((service) => service.active !== false));
  protected readonly retiredServices = computed(() => this.services().filter((service) => service.active === false));

  /** Total value of the offerings currently available — retired entries excluded, since they cannot be sold. */
  protected readonly catalogValue = computed(() =>
    this.activeServices().reduce((sum, service) => sum + (service.price ?? 0), 0),
  );

  /**
   * The current catalog search term, matched against each entry's name and description.
   *
   * <p>Client-side, like the pagination below — the whole catalog is already in memory for the
   * summary bar's totals, so filtering it in the browser needs no round trip and stays consistent
   * with why this page doesn't page on the server (see the note below).
   */
  protected readonly catalogSearchTerm = signal('');

  /**
   * The catalog narrowed by {@link catalogSearchTerm}. Feeds {@link pagedServices} and the pager,
   * but deliberately not {@link activeServices}/{@link retiredServices}/{@link catalogValue} — the
   * summary bar reports the whole catalog's shape, and a figure that silently became "of what you
   * searched for" would be read as a totals regression, not a filter.
   */
  protected readonly filteredServices = computed(() => {
    const term = this.catalogSearchTerm().trim().toLowerCase();
    if (!term) return this.services();
    return this.services().filter(
      (service) => service.name.toLowerCase().includes(term) || (service.description ?? '').toLowerCase().includes(term),
    );
  });

  /**
   * Pushes a new search term and returns the catalog table to its first page.
   *
   * <p>The reset mirrors {@link changeCatalogPageSize}: page 3 of an unfiltered catalog is very
   * likely past the end of a narrowed one, and clamping alone would strand the reader on a page
   * that only happens to still exist rather than the page their new search actually starts on.
   *
   * @param term - the raw value of the search input
   */
  protected onCatalogSearch(term: string): void {
    this.catalogSearchTerm.set(term);
    this.catalogPage.set(0);
  }

  // ── Catalog pagination (client-side) ──────────────────────────────────────────────────────
  // Sliced in the browser rather than fetched per page, deliberately. This component already
  // loads the whole catalog to compute the summary bar above the table — offered/retired counts
  // and total catalog value are all reductions over every row. Paging on the server would mean
  // either recomputing those as aggregate queries, or letting them silently describe one page
  // while presented as totals (the bug that had to be fixed on the security dashboard's tiles).
  //
  // The trade is only worth making when the data genuinely cannot be held at once. A services
  // catalog is bounded by how many things the business sells; an audit log is not, which is why
  // /admin/security/overview pages server-side and this does not.

  /**
   * Rows per page, chosen by the reader. Ten matches NFR-PERF-3's stated default and the admin
   * user directory, so the catalog opens the same size it always has.
   */
  protected readonly catalogPageSize = signal(10);

  /** The requested 0-based page. May briefly exceed the range — see {@link safeCatalogPage}. */
  protected readonly catalogPage = signal(0);

  protected readonly catalogTotalPages = computed(() =>
    Math.ceil(this.filteredServices().length / this.catalogPageSize()),
  );

  /**
   * The requested page clamped to what currently exists.
   *
   * <p>Derived rather than corrected in the setter because the list changes underneath the pager:
   * retiring the last entry on the final page would otherwise leave the table rendering an empty
   * slice with no obvious way back. Clamping here means the view self-corrects on the next
   * recomputation, without any mutation handler having to remember to reset it.
   */
  protected readonly safeCatalogPage = computed(() =>
    Math.min(this.catalogPage(), Math.max(this.catalogTotalPages() - 1, 0)),
  );

  /** The slice of the catalog actually rendered. The summary bar still reads the full list. */
  protected readonly pagedServices = computed(() => {
    const size = this.catalogPageSize();
    const start = this.safeCatalogPage() * size;
    return this.filteredServices().slice(start, start + size);
  });

  /**
   * Whether the footer — position readout, size selector and prev/next — is worth rendering.
   *
   * <p>Keyed to the total row count rather than to the current page count, which is the difference
   * that keeps the size selector reachable. Tying the footer to {@code totalPages > 1} (as this did
   * before the selector existed) sets a trap: choosing 100 rows for a 40-entry catalog collapses it
   * to a single page, which hides the footer, which takes away the only control that could put it
   * back to 10. Asking instead whether *any* offered size could produce a second page means the
   * footer survives its own effect.
   */
  protected readonly showCatalogFoot = computed(() => this.services().length > PAGE_SIZE_OPTIONS[0]);

  /** Hidden below two pages: a lone "1" is indistinguishable from having no pager at all. */
  protected readonly showCatalogPager = computed(() => this.catalogTotalPages() > 1);

  /**
   * Moves the catalog table to a page.
   *
   * @param page - the target 0-based page index; clamped to the available range
   */
  protected goToCatalogPage(page: number): void {
    this.catalogPage.set(Math.min(Math.max(page, 0), Math.max(this.catalogTotalPages() - 1, 0)));
  }

  /**
   * Changes how many catalog entries are shown per page and returns to the first page.
   *
   * <p>{@link safeCatalogPage} would clamp an out-of-range index anyway, but clamping lands the
   * reader on the *last* page of the resized list, which is a strange place to arrive after asking
   * only to see more rows. Resetting explicitly puts them at the top, which is what the request
   * meant.
   *
   * @param size - the new row count, one of the selector's offered values
   */
  protected changeCatalogPageSize(size: number): void {
    this.catalogPageSize.set(size);
    this.catalogPage.set(0);
  }

  /** The id of the row being edited inline, or null when nothing is open. */
  protected readonly editingId = signal<number | null>(null);

  /** Whether the "add a service" form is open. */
  protected readonly isCreating = signal(false);

  /** Blocks duplicate submissions while a mutation is in flight. */
  protected readonly isSaving = signal(false);

  ngOnInit(): void {
    this.load();
    this.organizationService
      .organizations$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.myOrganizations.set(response.data?.organizations ?? []),
        // Silent: this list only feeds the create form's organization picker and the table's
        // ownership labels, both of which degrade gracefully (generic "Organization #n" fallback)
        // rather than blocking the page a reader came here for.
        error: () => this.myOrganizations.set([]),
      });
    // Lets the navbar's "New Service" link and the command palette's matching entry land the
    // reader directly in the create form instead of just the list, mirroring how /invoice/new and
    // /customer/new are dedicated create destinations. This page has no separate create route —
    // the form is an inline toggle over the same list — so a query param does the same job a route
    // would on those other two screens.
    if (this.route.snapshot.queryParamMap.get('new') !== null) {
      this.startCreate();
    }
  }

  /**
   * Opens the create form and closes any open edit, so only one form is ever live.
   *
   * <p>Defaults {@link createOrganizationId} to the caller's own organization when a scoped caller
   * belongs to exactly one — the common case — so the form opens ready to submit rather than making
   * every scoped admin re-pick the one organization they administer. An unscoped caller, or a scoped
   * caller belonging to several organizations, opens with no default and must choose explicitly.
   */
  protected startCreate(): void {
    this.editingId.set(null);
    this.isCreating.set(true);
    const myOrgs = this.myOrganizations();
    this.createOrganizationId.set(
      !this.isUnscopedTier && myOrgs.length === 1 && myOrgs[0].id !== undefined ? myOrgs[0].id : undefined,
    );
  }

  /** Opens an inline edit for one row, closing the create form. */
  protected startEdit(service: ServicesInterface): void {
    this.isCreating.set(false);
    this.editingId.set(service.id);
  }

  /** Abandons whichever form is open without saving. */
  protected cancel(): void {
    this.isCreating.set(false);
    this.editingId.set(null);
    this.createOrganizationId.set(undefined);
  }

  /**
   * Whether the create form can be submitted as currently configured: an unscoped caller may always
   * submit (global is a valid default), but a scoped caller must have picked one of their own
   * organizations — there is no global option for them, mirroring
   * {@code ServicesCatalogController#requireManageable}'s refusal of a null-owned entry from a
   * scoped caller.
   */
  protected get canSubmitCreate(): boolean {
    return this.isUnscopedTier || this.createOrganizationId() !== undefined;
  }

  /**
   * Creates a catalog entry from the add form.
   *
   * @param form - the submitted form carrying name, description and price
   */
  protected create(form: NgForm): void {
    if (this.isSaving() || !this.canSubmitCreate) return;
    this.isSaving.set(true);

    this.catalog
      .create$({
        name: form.value.name,
        description: form.value.description,
        price: Number(form.value.price) || 0,
        organizationId: this.createOrganizationId(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.isCreating.set(false);
          this.createOrganizationId.set(undefined);
          form.resetForm();
          this.notification.onSuccess(response.message ?? 'Service added to the catalog.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Saves an inline edit.
   *
   * @param serviceId - the entry being edited
   * @param form - the submitted form carrying name, description and price
   */
  protected save(serviceId: number, form: NgForm): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.catalog
      .update$(serviceId, {
        name: form.value.name,
        description: form.value.description,
        price: Number(form.value.price) || 0,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.editingId.set(null);
          this.notification.onSuccess(response.message ?? 'Service updated.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Retires or reinstates an entry.
   *
   * <p>No confirmation prompt: retirement is fully reversible with the button that replaces it, so
   * a dialog would be asking the user to confirm something they can undo in one click. Prompts
   * belong on actions that cannot be taken back.
   *
   * @param service - the entry to toggle
   */
  protected toggleActive(service: ServicesInterface): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.catalog
      .setActive$(service.id, service.active === false)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.notification.onSuccess(response.message ?? 'Service updated.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /** Fetches the full catalog and folds it into {@link pageState}. */
  private load(): void {
    this.catalog
      .list$()
      .pipe(
        map((response) => ({ dataState: DataState.LOADED, appData: response })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.pageState.set(state));
  }
}
