import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { NgClass, NgOptimizedImage } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { BehaviorSubject, combineLatest, debounceTime, map, of, startWith, Subject, switchMap } from 'rxjs';
import { catchError, filter } from 'rxjs/operators';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { ExtractArrayValuePipe } from '../../../pipe/extract-array-value.pipe';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';

/**
 * All-customers list view with search and pagination.
 *
 * Fetches from GET /customer/list (no search term) or GET /customer/search
 * (with search term). Pagination and search are driven by BehaviorSubjects so
 * that any change automatically re-fetches via combineLatest + switchMap.
 */
@Component({
  selector: 'app-customers',
  imports: [NgClass, NgOptimizedImage, RouterModule, NavbarComponent, ExtractArrayValuePipe],
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
  readonly defaultImage = 'https://www.gravatar.com/avatar/?d=mp';
  /**
   * Drives the entire template — emits a new {@link GlobalStateInterface} snapshot
   * whenever the page index or search term changes.
   *
   * Switches between {@code /customer/list} and {@code /customer/search} depending
   * on whether {@link currentSearchSubject} holds a non-empty term.
   */
  customersState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({ dataState: DataState.LOADING });
  protected readonly router = inject(Router);
  private readonly customerService = inject(CustomerService);
  private readonly activatedRoute = inject(ActivatedRoute);

  /**
   * Caches the most recent successful API response so that pagination and search
   * updates can return {@code DataState.LOADED} immediately as the {@code startWith}
   * value while the next request is in flight.
   */
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerListDataInterface>>(null);

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
  private readonly localDefaultImages = [
    'assets/images/ali-lokhandwala-KUr51Y4dOyo-unsplash.jpg',
    'assets/images/anders-jilden-cYrMQA7a3Wc-unsplash.jpg',
    'assets/images/braden-jarvis-prSogOoFmkw-unsplash.jpg',
    'assets/images/cody-weiss-hEMYwIE6GEY-unsplash.jpg',
    'assets/images/cristofer-maximilian-KfBkfDGddsY-unsplash.jpg',
    'assets/images/dan-freeman-wAn4RfmXtxU-unsplash.jpg',
    'assets/images/henning-witzel-ukvgqriuOgo-unsplash.jpg',
    'assets/images/ian-dooley-DuBNA1QMpPA-unsplash.jpg',
    'assets/images/ilnur-kalimullin-CB0Qrf8ib4I-unsplash.jpg',
    'assets/images/j-dg-dhsMqSP0o_s-unsplash.jpg',
    'assets/images/jaanus-jagomagi-AZJAIiIn6BY-unsplash.jpg',
    'assets/images/jack-anstey-XVoyX7l9ocY-unsplash.jpg',
    'assets/images/jack-ward-rknrvCrfS1k-unsplash.jpg',
    'assets/images/jake-houglum-dxdA7qd7Y9o-unsplash.jpg',
    'assets/images/jonatan-pie-3l3RwQdHRHg-unsplash.jpg',
    'assets/images/jordan-mcqueen-sDHdRL9ilW0-unsplash.jpg',
    'assets/images/joshua-sortino-xZqr8WtYEJ0-unsplash.jpg',
    'assets/images/karsten-winegeart-ZBUesmAQapY-unsplash.jpg',
    'assets/images/karsten-winegeart-fd1cQ3mmBTE-unsplash.jpg',
    'assets/images/lisha-riabinina-HqZwKWqqpOA-unsplash.jpg',
    'assets/images/luca-bravo-ii5JY_46xH0-unsplash.jpg',
    'assets/images/max-bender-VmX3vmBecFE-unsplash.jpg',
    'assets/images/nasa-Q1p7bh3SHj8-unsplash.jpg',
    'assets/images/premium_photo-1669315452561-618adeb79a8d.avif',
    'assets/images/premium_photo-1675198764382-94d5c093df30.avif',
    'assets/images/premium_photo-1675827055694-010aef2cf08f.avif',
    'assets/images/premium_photo-1694475478052-c5247f63402e.avif',
    'assets/images/premium_photo-1695735927074-20d374c21ecc.avif',
    'assets/images/premium_photo-1773875204303-961af9cb4f5b.avif',
    'assets/images/randy-fath-wwHDqnJsG2E-unsplash.jpg',
    'assets/images/raul-cacho-oses-QZiDYEMUHO4-unsplash.jpg',
    'assets/images/redd-francisco-Dl_Ya8eNRpk-unsplash.jpg',
    'assets/images/roberto-carlos-roman-don-8cG8KEKIowk-unsplash.jpg',
    'assets/images/roberto-nickson-Jat5D3lH_FA-unsplash.jpg',
    'assets/images/saad-khan-pxAuY-HesQM-unsplash.jpg',
    'assets/images/sebastien-gabriel-Y8CW-2Dhk6Q-unsplash.jpg',
    'assets/images/thomas-habr-6NmnrAJPq7M-unsplash.jpg',
    'assets/images/tiago-aleixo-tveboMtwZ9c-unsplash.jpg',
    'assets/images/timo-wagner-fT6-YkB0nfg-unsplash.jpg',
    'assets/images/urban-vintage-78A265wPiO4-unsplash.jpg',
    'assets/images/viktor-mogilat-Ap8Ga6uWBmE-unsplash.jpg',
    'assets/images/yu-ko-gcCw9aiZTzQ-unsplash.jpg',
  ];

  /**
   * Wires {@link customersState$} to react to changes in both page index and search term.
   *
   * {@link combineLatest} ensures a new fetch fires whenever either subject emits.
   * {@link switchMap} cancels any in-flight request when a new emission arrives,
   * preventing stale responses from overwriting newer results.
   */
  ngOnInit(): void {
    // TODO(human): Wire searchInput$ into currentSearchSubject.
    // Pipe searchInput$ through debounceTime(300), filter (length === 0 || length >= 3),
    // and takeUntilDestroyed(this.destroyRef). In the subscribe callback, push the term
    // into currentSearchSubject and reset currentPageSubject to 0.
    this.searchInput$
      .pipe(
        debounceTime(300),
        filter((term) => term.length === 0 || term.length >= 1),
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
            this.dataSubject.next(response);
            return { dataState: DataState.LOADED, appData: response };
          }),
          startWith({ dataState: DataState.LOADING }),
          catchError((error: string) => of({ dataState: DataState.ERROR, error })),
        ),
      ),
    );
    customers$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((state) => this.customersState.set(state));
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
   * Navigates to the detail page for the given customer.
   *
   * Routes to {@code /customers/:id} using Angular's {@link Router}. The {@code .then}
   * callback logs the navigation result, which is useful for diagnosing guard failures
   * (e.g. the {@code authenticationGuard} returning false before the route resolves).
   *
   * @param customerId - the numeric ID of the customer whose detail page to open
   */
  goToCustomerDetails1(customerId: number): void {
    this.router.navigate(['/customers/', customerId]).then((r) => console.log('Navigation result:', r));
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
  protected getDefaultImageC(id: number): string {
    return this.localDefaultImages[id % this.localDefaultImages.length];
  }
}
