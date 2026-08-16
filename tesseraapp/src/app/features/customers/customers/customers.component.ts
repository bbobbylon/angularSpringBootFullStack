import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { NgClass, NgOptimizedImage } from '@angular/common';
import { RouterModule } from '@angular/router';
import { debounceTime, map, of, startWith, Subject, switchMap } from 'rxjs';
import { catchError, filter } from 'rxjs/operators';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';
import { CustomerTrendComponent } from '../../../shared/charts/customer-trend/customer-trend.component';
import { PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * All-customers list view with search and pagination.
 *
 * Fetches from {@code GET /customer/list} (no search term) or {@code GET /customer/search}
 * (with search term). Pagination and search are driven by Signals bridged to Observables
 * via {@code toObservable} so that any change automatically re-fetches via
 * {@code combineLatest + switchMap}.
 */
@Component({
  selector: 'app-customers',
  imports: [NgClass, RouterModule, NavbarComponent, NgOptimizedImage, CustomerTrendComponent, TranslocoDirective, PageSizeSelectComponent],
  templateUrl: './customers.component.html',
  styleUrl: './customers.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  /**
   * Last-resort fallback avatar used when a customer's {@code imageUrl} is absent or fails to load.
   *
   * Bound via {@code [src]="customer.imageUrl || defaultImage"} and the {@code (error)} handler
   * so that neither a missing nor a broken URL results in a broken-image icon.
   */
  /**
   * Drives the entire template — emits a new {@link GlobalStateInterface} snapshot
   * whenever the page index or search term changes.
   *
   * Switches between {@code /customer/list} and {@code /customer/search} depending
   * on whether {@link currentSearchSubject} holds a non-empty term.
   */
  customersState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({ dataState: DataState.LOADING });
  private readonly customerService = inject(CustomerService);

  /**
   * Tracks the current 0-based page index.
   *
   * Emitting a new value triggers {@link customersState$} to re-fetch
   * via {@link combineLatest}.
   */
  private currentPage = signal(0);

  /**
   * Public observable of the current 0-based page index.
   *
   * Consumed by the template's pagination controls to highlight the active page button.
   */
  currentPage$ = this.currentPage.asReadonly();

  /**
   * Tracks the active search term entered by the user.
   *
   * An empty string means no search is active — {@link customersState$} will call
   * {@code /customer/list}. A non-empty string routes to {@code /customer/search}.
   */
  private currentSearchTerm = signal('');

  /**
   * Rows fetched per page. Changing it resets {@link currentPage} — see {@link changePageSize}.
   *
   * <p>Twenty is what this screen has always requested (it was {@code CustomerService}'s default
   * argument); it is now stated here because the value is a user preference rather than a
   * service-layer fallback.
   */
  private pageSize = signal(20);

  /** Read-only view of the row count, for the template's size selector. */
  pageSize$ = this.pageSize.asReadonly();

  /**
   * The column currently sorted on, or {@code null} for the server's default (insertion) order.
   * Only fields in the backend's {@code CUSTOMER_SORT_FIELDS} allow-list have any effect — an
   * unrecognized field is silently treated as unsorted, so this never needs to mirror that list.
   */
  private sortField = signal<string | null>(null);

  /** Direction for {@link sortField}. Meaningless while {@link sortField} is {@code null}. */
  private sortDirection = signal<'asc' | 'desc'>('asc');

  /** Read-only view of the active sort, for the template to render the column indicator. */
  sort$ = computed(() => ({ field: this.sortField(), direction: this.sortDirection() }));

  /**
   * Page, size, search term and sort as one derived value — the single input to the fetch
   * pipeline.
   *
   * <p>This replaced a {@code combineLatest} over separate {@code toObservable} bridges. Because
   * each bridge runs its own effect, the two places that legitimately write two signals at once —
   * {@link changePageSize} and the debounced search subscription, both of which reset the page
   * index — made {@code combineLatest} emit twice in a single flush and issue two requests.
   * {@code switchMap} cancelled one, so nothing looked broken, but the server answered both.
   * Deriving a single value collapses that to one emission regardless of how many inputs moved.
   */
  private readonly query = computed(() => ({
    page: this.currentPage(),
    size: this.pageSize(),
    term: this.currentSearchTerm(),
    sort: this.sortField() ? `${this.sortField()},${this.sortDirection()}` : undefined,
  }));
  private readonly _query$ = toObservable(this.query);

  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /**
   * Raw keystrokes from the search input are pushed here.
   * The debounced subscription in {@link ngOnInit} gates what actually reaches {@link currentSearchSubject}.
   */
  private readonly searchInput$ = new Subject<string>();

  /**
   * Pool of local asset images used as deterministic fallback avatars.
   *
   * The image for a given customer is selected by {@code id % localDefaultImages.length},
   * ensuring the same customer always gets the same placeholder across renders.
   */
  private readonly avatarColors = ['0D8ABC', '2ECC71', 'E74C3C', '9B59B6', 'F39C12', '1ABC9C', 'E67E22'];

  /**
   * Wires {@link customersState} to react to changes in page index, row count or search term.
   *
   * All three travel together in {@link query}, so one fetch fires per change no matter how many
   * of them moved. {@link switchMap} cancels any in-flight request when a new emission arrives,
   * preventing stale responses from overwriting newer results.
   */
  ngOnInit(): void {
    this.searchInput$
      .pipe(
        debounceTime(300),
        filter((term) => term.length === 0 || term.length >= 3),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((term) => {
        this.currentSearchTerm.set(term);
        this.currentPage.set(0);
      });

    const customers$ = this._query$.pipe(
      switchMap(({ page, size, term, sort }) =>
        (term ? this.customerService.searchCustomers$(term, page, size, sort) : this.customerService.customers$(page, size, sort)).pipe(
          map((response) => {
            return { dataState: DataState.LOADED, appData: response };
          }),
          startWith({ dataState: DataState.LOADING }),
          catchError((error: string) => {
            this.notification.onError(error);
            return of({ dataState: DataState.ERROR, error });
          }),
        ),
      ),
    );
    customers$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((state) => this.customersState.set(state));
  }

  /**
   * Called on every keystroke in the search input. Pushes the raw term into
   * {@link searchInput$}, which the debounced subscription in {@link ngOnInit} gates.
   *
   * @param term - the current value of the search input
   */
  onSearchInput(term: string): void {
    this.searchInput$.next(term);
  }

  /**
   * Advances or retreats one page. The active search term is preserved automatically
   * because {@link currentSearchSubject} already holds it and {@link customersState$}
   * reads from both subjects via {@code combineLatest}.
   *
   * @param direction - {@code 'forward'} to increment the page, {@code 'backward'} to decrement
   */
  goToNextOrPreviousPage(direction: string): void {
    const step = direction === 'forward' ? 1 : -1;
    this.currentPage.update((current) => current + step);
  }

  /**
   * Jumps directly to a specific page index. The active search term is preserved automatically
   * because {@link currentSearchSubject} already holds it and {@link customersState$}
   * reads from both subjects via {@code combineLatest}.
   *
   * @param pageIndex - the 0-based index of the target page
   */
  goToPage(pageIndex: number): void {
    this.currentPage.set(pageIndex);
  }

  /**
   * Changes how many customers are fetched per page and returns to the first page.
   *
   * <p>The reset is the whole reason this is a method rather than a two-way binding on the
   * selector. Page indexes are only meaningful relative to a row count: someone reading page 6 of a
   * 10-row listing who switches to 100 rows is asking for rows 500–599 of a list that may now be
   * five pages long, and would land on an empty table with no indication of why. Returning to the
   * first page is the only interpretation that always has an answer.
   *
   * <p>The active search term is preserved — {@link query} carries it independently, so widening
   * the page size while filtering does not silently drop the filter.
   *
   * @param size - the new row count, one of the selector's offered values
   */
  changePageSize(size: number): void {
    this.pageSize.set(size);
    this.currentPage.set(0);
  }

  /**
   * Sorts by the given column, toggling direction on repeated clicks of the same header.
   *
   * <p>Clicking a new column always starts ascending — a first click that flipped straight to
   * descending would be surprising, since nothing on screen indicated a prior direction to
   * reverse. Clicking the already-active column toggles, which is the conventional spreadsheet
   * behavior every user of a sortable table already expects.
   *
   * <p>Resets to the first page for the same reason {@link changePageSize} does: a page index is
   * only meaningful relative to the current ordering, and re-sorting changes which rows occupy it.
   *
   * @param field - a JPA property path from the backend's {@code CUSTOMER_SORT_FIELDS} allow-list
   */
  toggleSort(field: string): void {
    if (this.sortField() === field) {
      this.sortDirection.update((current) => (current === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortField.set(field);
      this.sortDirection.set('asc');
    }
    this.currentPage.set(0);
  }

  /**
   * Returns a deterministic local fallback image path for the given customer ID.
   *
   * Uses modulo arithmetic against {@link localDefaultImages} so that each customer
   * always receives the same placeholder regardless of render order or page.
   *
   * @param id - the customer's numeric ID used to index into the image pool
   * @returns a relative path to an asset image under {@code assets/images/}
   */
  protected getAvatarColor(id: number): string {
    return '#' + this.avatarColors[id % this.avatarColors.length];
  }

  /**
   * Bootstrap Icons class for a sortable column header: a neutral up/down glyph when this column
   * is not the active sort, or a direction-specific arrow when it is.
   *
   * @param field - the JPA property path the column sorts by
   * @returns a single `bi-*` class name for use in `[ngClass]`
   */
  protected sortIconClass(field: string): string {
    const active = this.sort$();
    if (active.field !== field) {
      return 'bi-arrow-down-up';
    }
    return active.direction === 'asc' ? 'bi-sort-down' : 'bi-sort-up';
  }
}
