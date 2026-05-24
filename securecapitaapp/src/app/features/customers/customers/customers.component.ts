import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { NgClass, NgOptimizedImage } from '@angular/common';
import { RouterModule } from '@angular/router';
import { combineLatest, debounceTime, map, of, startWith, Subject, switchMap } from 'rxjs';
import { catchError, filter } from 'rxjs/operators';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { ExtractArrayValuePipe } from '../../../pipe/extract-array-value.pipe';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';

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
  imports: [NgClass, RouterModule, NavbarComponent, ExtractArrayValuePipe, NgOptimizedImage],
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
  private readonly _currentPageObs$ = toObservable(this.currentPage);
  private readonly _currentSearchTermObs$ = toObservable(this.currentSearchTerm);

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
   * Wires {@link customersState$} to react to changes in both page index and search term.
   *
   * {@link combineLatest} ensures a new fetch fires whenever either subject emits.
   * {@link switchMap} cancels any in-flight request when a new emission arrives,
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

    const customers$ = combineLatest([this._currentPageObs$, this._currentSearchTermObs$]).pipe(
      switchMap(([page, name]) =>
        (name ? this.customerService.searchCustomers$(name, page) : this.customerService.customers$(page)).pipe(
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
}
