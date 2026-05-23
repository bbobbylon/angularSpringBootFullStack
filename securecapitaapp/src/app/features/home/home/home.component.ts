import { ChangeDetectionStrategy, Component, inject, Input, OnInit, Signal, signal } from '@angular/core';
import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, startWith, switchMap } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { StatsComponent } from '../../../shared/stats/stats.component';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { UserService } from '../../../service/user.service';
import { DataState } from '../../../enumeration/datastate.enum';
import { CustomerService } from '../../../service/customer.service';
import { ExtractArrayValuePipe } from '../../../pipe/extract-array-value.pipe';
import { UserInterface } from '../../../interface/user.interface';
import { CustomerInterface } from '../../../interface/customer.interface';
import { HttpEvent, HttpEventType } from '@angular/common/http';
import { saveAs } from 'file-saver';

/**
 * Main dashboard component displayed after login.
 *
 * Currently renders stub/dummy data for the customer table and stats panel.
 * Real data will be wired in once the customers and statistics backend
 * endpoints are implemented. The navbar and stats are delegated to their
 * own standalone components.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, NavbarComponent, StatsComponent, ExtractArrayValuePipe, NgOptimizedImage],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeComponent implements OnInit {
  @Input() user: UserInterface;
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  /** Last-resort fallback used by the (error) handler if all other image sources fail. */
  readonly defaultImage = 'https://www.gravatar.com/avatar/?d=mp';
  /**
   * Drives the home dashboard template — emits a new snapshot whenever the page index
   * or page size changes.
   *
   * Wired in {@link ngOnInit} via a combined {@link currentPageSubject}/{@link pageSizeSubject}
   * stream. {@code switchMap} cancels any in-flight request when pagination controls
   * change before the previous response arrives, so the template never shows stale data.
   */
  homeState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>;
  homeState: Signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>;
  readonly title = signal('securecapitaapp');
  readonly pageSizeOptions = [10, 20, 50, 100] as const;
  protected readonly router = inject(Router);
  protected readonly showLogs = signal(true);
  protected readonly permissions = signal<string[]>([]);
  protected readonly sortColumn = signal<string>('createdAt');
  protected readonly sortDirection = signal<'asc' | 'desc'>('desc');
  protected readonly customerService = inject(CustomerService);
  // Option B — deterministic colour picked from palette by customer ID
  private readonly avatarColors = ['0D8ABC', '2ECC71', 'E74C3C', '9B59B6', 'F39C12', '1ABC9C', 'E67E22'];
  // Option C — local images from public/assets/images/
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
  private currentPageSubject = new BehaviorSubject<number>(0);
  /** Observable of the current 0-based page index, used by the template to highlight the active page. */
  currentPage$ = this.currentPageSubject.asObservable();
  private pageSizeSubject = new BehaviorSubject<number>(20);
  /** Observable of the current page size, used by the template to mark the active dropdown option. */
  pageSize$ = this.pageSizeSubject.asObservable();
  private readonly userService = inject(UserService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerListDataInterface>>(null);
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  protected isLoading$ = this.isLoadingSubject.asObservable();
  private fileStatusSubject = new BehaviorSubject<{ status: string; type: string; percent: number }>(undefined);
  fileStatus$ = this.fileStatusSubject.asObservable();
  //fileStatus$: Observable<{ percent: number; type: string } | null> = of({ percent: 0, type: 'idle' });

  /**
   * Wires the home state observable to the combined page/size stream.
   *
   * Uses {@code combineLatest} so that a change to either the current page or the
   * page size triggers a new request. {@code switchMap} automatically cancels any
   * in-flight request when a new emission arrives, preventing stale responses.
   */
  ngOnInit(): void {
    this.homeState$ = combineLatest([this.currentPageSubject, this.pageSizeSubject]).pipe(
      switchMap(([page, size]) =>
        this.customerService.customers$(page, size).pipe(
          map((response) => {
            console.log('Fetched customer data:', response);
            this.dataSubject.next(response);
            return { dataState: DataState.LOADED, appData: response };
          }),
          startWith({ dataState: DataState.LOADING }),
          catchError((error: string) => of({ dataState: DataState.ERROR, error })),
        ),
      ),
    );
  }

  /**
   * Triggers a report download or export for the current data set.
   * Stub — implementation pending backend report endpoint.
   */
  report(): void {
    console.log('report clicked');
    this.homeState$ = this.customerService.downloadCustomerReport$().pipe(
      map((response) => {
        console.log(response);
        this.reportProgress(response);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADING, appData: this.dataSubject.value }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error, appData: this.dataSubject.value })),
    );
  }

  /**
   * Navigates to the next or previous page of the customer list.
   *
   * @param direction - 'forward' to go to the next page, 'backward' to go to the previous
   */
  changePage(direction: 'forward' | 'backward'): void {
    const step = direction === 'forward' ? 1 : -1;
    this.goToPage(this.currentPageSubject.value + step);
  }

  /**
   * Jumps directly to a specific page index in the customer list.
   *
   * Guards against out-of-bounds indices on both ends. A negative index is
   * rejected immediately — Spring Boot's {@code PageRequest.of(page, size)}
   * throws {@code IllegalArgumentException} if {@code page < 0}. An index
   * beyond the last page is also rejected using the total page count from the
   * last known response stored in {@link dataSubject}.
   *
   * This is the second line of defence: the primary guard is the {@code [disabled]}
   * binding on the navigation buttons in the template, which prevents click events
   * from firing at the boundaries. This guard catches any event that slips through
   * (e.g. rapid double-click before the disabled state propagates to the DOM).
   *
   * @param pageIndex - the 0-based index of the page to navigate to
   */
  goToPage(pageIndex: number): void {
    const totalPages = this.dataSubject.value?.data?.page?.page?.totalPages ?? Infinity;
    if (pageIndex < 0 || pageIndex >= totalPages) return;
    this.currentPageSubject.next(pageIndex);
  }

  /**
   * Updates the number of records displayed per page.
   *
   * @param size - the new page size chosen by the user
   */
  changePageSize(size: number): void {
    this.pageSizeSubject.next(size);
    this.currentPageSubject.next(0); // Reset to first page when page size changes
  }

  /**
   * Navigates to the customer detail page.
   *
   * Note: the parameter type is {@link CustomerInterface} rather than a numeric ID.
   * Angular's router will serialize the entire object into the URL segment, which is
   * almost certainly unintended — use {@link goToCustomerDetails1} instead until this
   * is corrected to accept a numeric ID.
   *
   * @param customerId - the customer object (should be a numeric ID; see note above)
   */
  goToCustomerDetails(customerId: CustomerInterface): void {
    this.router.navigate(['/customers/', customerId]);
  }

  /**
   * Navigates to the detail page for the given customer ID.
   *
   * Routes to {@code /customers/:id} using Angular's {@link Router}.
   *
   * @param customerId - the numeric ID of the customer whose detail page to open
   */
  goToCustomerDetails1(customerId: number): void {
    this.router.navigate(['/customers/', customerId]);
  }

  /**
   * Generates a deterministic avatar URL via the UI Avatars API for the given customer.
   *
   * The background colour is chosen by {@code id % avatarColors.length}, so the same
   * customer always gets the same colour regardless of render order or page. The name
   * is URI-encoded so spaces and special characters produce valid initials.
   *
   * @param id - the customer's numeric ID used to pick a colour from {@code avatarColors}
   * @param name - the customer's display name, used to generate initials
   * @returns a fully-qualified URL to a 128×128 rounded avatar image
   */
  protected getDefaultImageB(id: number, name: string): string {
    const color = this.avatarColors[id % this.avatarColors.length];
    return `https://ui-avatars.com/api/?name=${encodeURIComponent(name)}&background=${color}&color=fff&size=128&rounded=true`;
  }

  /**
   * Returns a deterministic local fallback image path for the given customer ID.
   *
   * Uses modulo arithmetic against {@code localDefaultImages} so that each customer
   * always receives the same placeholder regardless of render order or page.
   *
   * @param id - the customer's numeric ID used to index into the image pool
   * @returns a relative path to an asset image under {@code assets/images/}
   */
  protected getDefaultImageC(id: number): string {
    return this.localDefaultImages[id % this.localDefaultImages.length];
  }

  private reportProgress(httpEvent: HttpEvent<string[] | Blob>): void {
    switch (httpEvent.type) {
      case HttpEventType.UploadProgress:
      case HttpEventType.DownloadProgress:
        this.fileStatusSubject.next({
          status: 'progress',
          type: 'Downloading File',
          percent: Math.round((100 * httpEvent.loaded) / (httpEvent.total ?? httpEvent.loaded)),
        });
        break;
      case HttpEventType.ResponseHeader:
        console.log('Received Response headers!', httpEvent);
        break;
      case HttpEventType.Response:
        saveAs(new File([httpEvent.body as Blob], 'customer_report.xlsx', { type: `${httpEvent.headers.get('Content-Type')};charset=utf-8` }));
        this.fileStatusSubject.next(undefined);
        break;
      default:
        console.log(httpEvent);
        break;
    }
  }
}
