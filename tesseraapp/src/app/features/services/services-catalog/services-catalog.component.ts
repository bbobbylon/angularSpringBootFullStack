import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { ServicesCatalogService, PublicServicesListDataInterface } from '../../../service/services-catalog.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { TranslocoDirective } from '@jsverse/transloco';
import { PAGE_SIZE_OPTIONS, PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';

/**
 * Public service/app catalog page — {@code /services}.
 *
 * Displays every active service in the backend catalog so anyone browsing the site — signed in or
 * not — can see what the business offers. Data comes from {@link ServicesCatalogService#listPublic$},
 * which hits {@code GET /services/public}: a genuinely unauthenticated endpoint, so this page does
 * not silently go blank for a logged-out visitor. (An earlier version of this page reused the
 * authenticated {@code GET /customer/invoice/new} response instead — convenient because no new
 * endpoint was needed, but it meant "the public catalog" only ever worked for people who were
 * already signed in, which defeats the point of a public catalog.)
 *
 * The "Create Invoice" action navigates to {@code /invoice/new}, which is where
 * organizations choose a service and customer and submit a billing request that
 * flows into the main billing system tracked on the Billing Overview page.
 *
 * <h3>Why the grid pages on the client</h3>
 * Every other paged surface in the app asks the server for one page. This one cannot, and should
 * not: the public endpoint returns the <em>whole</em> active catalog in one response — there is no
 * per-visitor state to page against, and a service worth showing on page one is worth showing on
 * page five too. So the response is complete by construction and the grid slices it locally — the
 * same choice {@code customer-details} makes for a customer's invoice history.
 */
@Component({
  selector: 'app-services-catalog',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, TranslocoDirective, PageSizeSelectComponent],
  templateUrl: './services-catalog.component.html',
  styleUrl: './services-catalog.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServicesCatalogComponent implements OnInit {
  readonly DataState = DataState;

  private readonly servicesCatalogService = inject(ServicesCatalogService);
  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly router = inject(Router);

  readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<PublicServicesListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  readonly services = computed<ServicesInterface[]>(
    () => this.pageState().appData?.data?.services ?? [],
  );

  /**
   * Total catalog value — the sum of **every** service's base price.
   *
   * <p>Deliberately reduces over {@link services} rather than {@link pagedServices}: a headline
   * figure that silently meant "the ten offerings you happen to be looking at" would be read as the
   * catalog's worth and be wrong on every page but the last.
   */
  readonly catalogTotal = computed(() =>
    this.services().reduce((sum, svc) => sum + (svc.price ?? 0), 0),
  );

  /**
   * The current catalog search term, matched against each service's name and description.
   *
   * <p>Client-side, for the same reason the pagination below is: the public endpoint already
   * returns the whole active catalog in one response, so there is no per-visitor server state to
   * search against and filtering it in the browser needs no round trip.
   */
  protected readonly catalogSearchTerm = signal('');

  /**
   * The catalog narrowed by {@link catalogSearchTerm}. Feeds {@link pagedServices} and the pager,
   * but deliberately not {@link catalogTotal} — the headline catalog value is a total of *every*
   * offering, and silently becoming "of what you searched for" would read as a value regression,
   * not a filter.
   */
  protected readonly filteredServices = computed(() => {
    const term = this.catalogSearchTerm().trim().toLowerCase();
    if (!term) return this.services();
    return this.services().filter(
      (svc) => svc.name.toLowerCase().includes(term) || (svc.description ?? '').toLowerCase().includes(term),
    );
  });

  /**
   * Pushes a new search term and returns the grid to its first page.
   *
   * @param term - the raw value of the search input
   */
  protected onCatalogSearch(term: string): void {
    this.catalogSearchTerm.set(term);
    this.catalogPage.set(0);
  }

  /**
   * Cards per page. Ten matches the admin catalog at {@code /services/manage}, so an administrator
   * moving between the two views does not have the page size change under them.
   */
  protected readonly catalogPageSize = signal(10);

  /** Requested 0-based page. May exceed the range transiently — see {@link safeCatalogPage}. */
  protected readonly catalogPage = signal(0);

  protected readonly catalogTotalPages = computed(() =>
    Math.ceil(this.filteredServices().length / this.catalogPageSize()),
  );

  /**
   * The requested page clamped to what exists.
   *
   * <p>Derived rather than corrected on mutation, because the catalog can shrink underneath the
   * pager: an administrator retiring a service in another tab removes it from {@code
   * availableServices} on the next load. Clamping here means the grid cannot strand the reader on
   * an empty page with no obvious way back.
   */
  protected readonly safeCatalogPage = computed(() =>
    Math.min(this.catalogPage(), Math.max(this.catalogTotalPages() - 1, 0)),
  );

  /** The slice actually rendered. {@link catalogTotal} still reduces over the full list. */
  protected readonly pagedServices = computed(() => {
    const size = this.catalogPageSize();
    const start = this.safeCatalogPage() * size;
    return this.filteredServices().slice(start, start + size);
  });

  /**
   * Whether the footer — position readout, size selector and prev/next — is worth rendering.
   *
   * <p>Gated on the service count rather than the page count so the size selector cannot delete
   * itself: at 100 cards per page a 30-service catalog is a single page, and a
   * {@code totalPages > 1} gate would hide the footer along with the only control that could
   * restore a smaller size.
   */
  protected readonly showCatalogFoot = computed(
    () => this.services().length > PAGE_SIZE_OPTIONS[0],
  );

  /** Hidden below two pages: a lone "1" is indistinguishable from having no pager. */
  protected readonly showCatalogPager = computed(() => this.catalogTotalPages() > 1);

  /**
   * Moves the grid to a page.
   *
   * @param page - target 0-based page index; clamped to the available range
   */
  protected goToCatalogPage(page: number): void {
    this.catalogPage.set(Math.min(Math.max(page, 0), Math.max(this.catalogTotalPages() - 1, 0)));
  }

  /**
   * Changes how many cards are shown per page and returns to the first page.
   *
   * <p>{@link safeCatalogPage} clamps out-of-range indexes on its own, but clamping lands on the
   * last page of the resized catalog — an odd destination for someone who only asked to see more
   * at once. Resetting puts them back at the start of the catalog, which is where browsing begins.
   *
   * @param size - the new card count, one of the selector's offered values
   */
  protected changeCatalogPageSize(size: number): void {
    this.catalogPageSize.set(size);
    this.catalogPage.set(0);
  }

  // A getter, not a field: authority flags must follow the CURRENT token. Evaluated once at
  // construction they latch whatever was true then — and on a page refresh that is usually an
  // expired token, i.e. "no authorities at all". UserService memoises the decode.
  get isAdmin(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE', 'DELETE:USER');
  }

  ngOnInit(): void {
    this.servicesCatalogService
      .listPublic$()
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
