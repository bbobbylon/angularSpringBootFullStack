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
import { CustomerService } from '../../../service/customer.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { NewInvoiceDataInterface } from '../../../interface/appstates.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { UserInterface } from '../../../interface/user.interface';
import { TranslocoDirective } from '@jsverse/transloco';
import { PAGE_SIZE_OPTIONS, PageSizeSelectComponent } from '../../../shared/page-size-select/page-size-select.component';

/**
 * Service/app catalog page — {@code /services}.
 *
 * Displays all services registered in the backend catalog so users can browse
 * available offerings and jump directly to a new invoice pre-selected for a
 * service. Data is fetched from {@code GET /customer/invoice/new}, which already
 * returns {@code availableServices} alongside the customer list — no additional
 * backend endpoint is needed for this view.
 *
 * The "Create Invoice" action navigates to {@code /invoice/new}, which is where
 * organisations choose a service and customer and submit a billing request that
 * flows into the main billing system tracked on the Billing Overview page.
 *
 * <h3>Why the grid pages on the client</h3>
 * Every other paged surface in the app asks the server for one page. This one cannot, and should
 * not: {@code /customer/invoice/new} exists to populate the new-invoice form, whose service picker
 * needs the <em>whole</em> active catalog in one response. Paging it server-side would either break
 * that form or require a second endpoint returning data this page has already been handed. So the
 * response is complete by construction and the grid slices it locally — the same choice
 * {@code customer-details} makes for a customer's invoice history.
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

  private readonly customerService = inject(CustomerService);
  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly router = inject(Router);

  readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<NewInvoiceDataInterface>>>({
    dataState: DataState.LOADING,
  });

  readonly user = computed<UserInterface | undefined>(
    () => this.pageState().appData?.data?.user,
  );
  readonly services = computed<ServicesInterface[]>(
    () => this.pageState().appData?.data?.availableServices ?? [],
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
   * Cards per page. Ten matches the admin catalog at {@code /services/manage}, so an administrator
   * moving between the two views does not have the page size change under them.
   */
  protected readonly catalogPageSize = signal(10);

  /** Requested 0-based page. May exceed the range transiently — see {@link safeCatalogPage}. */
  protected readonly catalogPage = signal(0);

  protected readonly catalogTotalPages = computed(() =>
    Math.ceil(this.services().length / this.catalogPageSize()),
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
    return this.services().slice(start, start + size);
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
    this.customerService
      .newInvoice$()
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
