import { Component, inject, Input, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, catchError, combineLatest, map, Observable, of, startWith, switchMap } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { StatsComponent } from '../stats/stats.component';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { CustomerListData } from '../../interface/appstates.interface';
import { UserService } from '../../service/user.service';
import { DataState } from '../../enumeration/datastate.enum';
import { CustomerService } from '../../service/customer.service';
import { ExtractArrayValuePipe } from '../../pipe/extract-array-value.pipe';

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
  imports: [CommonModule, RouterModule, NavbarComponent, StatsComponent, ExtractArrayValuePipe],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
})
export class HomeComponent implements OnInit {
  @Input() user: any;
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  /** Last-resort fallback used by the (error) handler if all other image sources fail. */
  readonly defaultImage = 'https://www.gravatar.com/avatar/?d=mp';
  /**
   * Observable state for the profile view.
   * It holds the global state, including the current data state (e.g., LOADING, LOADED, ERROR),
   * application data, and any errors that may occur during data fetching.
   */
  homeState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListData>>>;
  readonly title = signal('securecapitaapp');
  fileStatus$: Observable<{ percent: number; type: string } | null> = of({ percent: 0, type: 'idle' });
  readonly pageSizeOptions = [10, 20, 50, 100] as const;
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
  currentPage$ = this.currentPageSubject.asObservable();
  private pageSizeSubject = new BehaviorSubject<number>(20);
  pageSize$ = this.pageSizeSubject.asObservable();
  private readonly userService = inject(UserService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerListData>>(null);
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  protected isLoading$ = this.isLoadingSubject.asObservable();

  /**
   * Initializes the component by fetching the user's profile information.
   * This method is an Angular lifecycle hook that is called after the component's
   * data-bound properties have been initialized. It retrieves the user data from
   * the application state, which is managed by a BehaviorSubject in the UserService.
   * It subscribes to the user$ observable to get the latest user data and updates
   * the component's state. This ensures that the profile information is always
   * current. The method also sets the initial data state to LOADING and then
   * updates it to LOADED or ERROR based on the outcome of the data fetch operation.
   */
  ngOnInit(): void {
    this.homeState$ = combineLatest([this.currentPageSubject, this.pageSizeSubject]).pipe(
      switchMap(([page, size]) =>
        this.customerService.customers$(page, size).pipe(
          map(response => {
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
   * @param pageIndex - the 0-based index of the page to navigate to
   */
  goToPage(pageIndex: number): void {
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
  gotopage1(pageIndex?: number): void {
    this.homeState$ = this.customerService.customers$(pageIndex).pipe(
      map(response => {
        console.log('Fetched customer data:', response);
        this.dataSubject.next(response);
        this.currentPageSubject.next(pageIndex);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => {
        return of({ dataState: DataState.ERROR, error });
      }),
    );
  }

  protected getDefaultImageB(id: number, name: string): string {
    const color = this.avatarColors[id % this.avatarColors.length];
    return `https://ui-avatars.com/api/?name=${encodeURIComponent(name)}&background=${color}&color=fff&size=128&rounded=true`;
  }

  protected getDefaultImageC(id: number): string {
    return this.localDefaultImages[id % this.localDefaultImages.length];
  }
}
