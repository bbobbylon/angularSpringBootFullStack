import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { ServicesCatalogService, ServicesListDataInterface } from '../../../service/services-catalog.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { UserInterface } from '../../../interface/user.interface';
import { PAGE_SIZE_OPTIONS, PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Administrative management of the services catalog — {@code /services/manage}
 * (ROADMAP §2 — "Create / manage services").
 *
 * <h3>Why this is a separate page from the catalog</h3>
 * The browse page at {@code /services} reads {@code GET /customer/invoice/new}, which returns
 * <em>active</em> services only — correctly, since its job is to show what can be put on an
 * invoice. An administrator needs the opposite: the full catalog including retired entries, so
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
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<ServicesListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  protected readonly user = computed<UserInterface | undefined>(() => this.pageState().appData?.data?.user);

  protected readonly services = computed<ServicesInterface[]>(() => this.pageState().appData?.data?.services ?? []);

  protected readonly activeServices = computed(() => this.services().filter((service) => service.active !== false));
  protected readonly retiredServices = computed(() => this.services().filter((service) => service.active === false));

  /** Total value of the offerings currently available — retired entries excluded, since they cannot be sold. */
  protected readonly catalogValue = computed(() =>
    this.activeServices().reduce((sum, service) => sum + (service.price ?? 0), 0),
  );

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
    Math.ceil(this.services().length / this.catalogPageSize()),
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
    return this.services().slice(start, start + size);
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
  }

  /** Opens the create form and closes any open edit, so only one form is ever live. */
  protected startCreate(): void {
    this.editingId.set(null);
    this.isCreating.set(true);
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
  }

  /**
   * Creates a catalog entry from the add form.
   *
   * @param form - the submitted form carrying name, description and price
   */
  protected create(form: NgForm): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.catalog
      .create$({
        name: form.value.name,
        description: form.value.description,
        price: Number(form.value.price) || 0,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.isCreating.set(false);
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
