import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NgClass, NgOptimizedImage } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { StatsComponent } from '../../../shared/stats/stats.component';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { DataState } from '../../../enumeration/datastate.enum';
import { CustomerService } from '../../../service/customer.service';
import { ExtractArrayValuePipe } from '../../../pipe/extract-array-value.pipe';
import { HttpEvent, HttpEventType } from '@angular/common/http';
import { saveAs } from 'file-saver';
import { NotificationsService } from '../../../service/notifications-service';

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
  imports: [NgClass, RouterModule, NavbarComponent, StatsComponent, ExtractArrayValuePipe, NgOptimizedImage],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeComponent implements OnInit {
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  readonly pageSizeOptions = [10, 20, 50, 100] as const;
  /** Drives the entire template — set to LOADING/LOADED/ERROR by the page-fetch subscription. */
  homeState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({ dataState: DataState.LOADING });
  /** Current 0-based page index. Changing this triggers a re-fetch via the toObservable bridge. */
  protected currentPage = signal(0);
  /** Current page size. Changing this resets to page 0 and triggers a re-fetch. */
  protected pageSize = signal(20);
  /** Emits download progress state for the progress bar in the template. */
  protected fileStatus = signal<{ status: string; type: string; percent: number } | undefined>(undefined);
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  private readonly avatarColors = ['0D8ABC', '2ECC71', 'E74C3C', '9B59B6', 'F39C12', '1ABC9C', 'E67E22'];
  private data = signal<CustomHttpResponseInterface<CustomerListDataInterface>>(null);
  private readonly _currentPage$ = toObservable(this.currentPage);
  private readonly _pageSize$ = toObservable(this.pageSize);

  /**
   * Subscribes the home state signal to the combined page/size stream.
   *
   * Uses {@code combineLatest} so that a change to either the current page or the
   * page size triggers a new request. {@code switchMap} automatically cancels any
   * in-flight request when a new emission arrives, preventing stale responses.
   * {@code takeUntilDestroyed} ensures the subscription is cleaned up automatically
   * when the component is destroyed, eliminating the need for manual {@code ngOnDestroy}.
   */
  ngOnInit(): void {
    combineLatest([this._currentPage$, this._pageSize$])
      .pipe(
        switchMap(([page, size]) =>
          this.customerService.customers$(page, size).pipe(
            map((response) => {
              console.log('Fetched customer data:', response);
              this.data.set(response);
              return { dataState: DataState.LOADED, appData: response };
            }),
            startWith({ dataState: DataState.LOADING }),
            catchError((error: string) => {
              this.notification.onError(error);
              return of({ dataState: DataState.ERROR, error });
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.homeState.set(state));
  }

  /**
   * Triggers a report download and saves it as an Excel file.
   *
   * Immediately sets the state to LOADING (preserving the current data so the table
   * remains visible under the progress bar), then subscribes to the download stream.
   * {@code reportProgress} translates {@link HttpEventType} upload/download events
   * into progress bar state while the transfer is in flight.
   */
  report(): void {
    console.log('report clicked');
    this.homeState.set({ dataState: DataState.LOADING, appData: this.data() });
    this.customerService
      .downloadCustomerReport$()
      .pipe(
        map((response) => {
          console.log(response);
          this.reportProgress(response);
          return { dataState: DataState.LOADED, appData: this.data() };
        }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error, appData: this.data() });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.homeState.set(state));
  }

  /**
   * Navigates to the next or previous page of the customer list.
   *
   * @param direction - 'forward' to go to the next page, 'backward' to go to the previous
   */
  changePage(direction: 'forward' | 'backward'): void {
    const step = direction === 'forward' ? 1 : -1;
    this.goToPage(this.currentPage() + step);
  }

  /**
   * Jumps directly to a specific page index in the customer list.
   *
   * Guards against out-of-bounds indices on both ends. A negative index is
   * rejected immediately — Spring Boot's {@code PageRequest.of(page, size)}
   * throws {@code IllegalArgumentException} if {@code page < 0}. An index
   * beyond the last page is also rejected using the total page count from the
   * last known response stored in the {@link data} signal.
   *
   * This is the second line of defence: the primary guard is the {@code [disabled]}
   * binding on the navigation buttons in the template, which prevents click events
   * from firing at the boundaries. This guard catches any event that slips through
   * (e.g. rapid double-click before the disabled state propagates to the DOM).
   *
   * @param pageIndex - the 0-based index of the page to navigate to
   */
  goToPage(pageIndex: number): void {
    const totalPages = this.data()?.data?.page?.page?.totalPages ?? Infinity;
    if (pageIndex < 0 || pageIndex >= totalPages) return;
    this.currentPage.set(pageIndex);
  }

  /**
   * Updates the number of records displayed per page.
   *
   * Resetting {@link currentPage} to 0 is necessary to avoid requesting a page index
   * that no longer exists — e.g. if the user was on page 5 with 10 rows/page and
   * switches to 100 rows/page, page 5 would be out of range.
   *
   * @param size - the new page size chosen by the user
   */
  changePageSize(size: number): void {
    this.pageSize.set(size);
    this.currentPage.set(0);
  }

  /**
   * Returns a deterministic background colour for a customer's initials avatar.
   *
   * The colour is chosen by {@code id % avatarColors.length}, so the same customer
   * always gets the same colour regardless of render order or page.
   *
   * @param id - the customer's numeric ID used to pick a colour from {@code avatarColors}
   * @returns a CSS hex colour string (e.g. {@code '#0D8ABC'})
   */
  protected getAvatarColor(id: number): string {
    return '#' + this.avatarColors[id % this.avatarColors.length];
  }

  /**
   * Translates raw {@link HttpEvent} emissions from the download stream into
   * progress bar state stored in the {@link fileStatus} signal.
   *
   * Called on every emission from {@link CustomerService#downloadCustomerReport$}.
   * On {@code DownloadProgress}, updates the percentage. On {@code Response},
   * triggers {@code saveAs} and clears the progress bar.
   *
   * @param httpEvent - the raw HTTP event emitted by the Angular {@code HttpClient}
   */
  private reportProgress(httpEvent: HttpEvent<string[] | Blob>): void {
    switch (httpEvent.type) {
      case HttpEventType.UploadProgress:
      case HttpEventType.DownloadProgress:
        this.fileStatus.set({
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
        this.notification.onSuccess('Report downloaded successfully');
        this.fileStatus.set(undefined);
        break;
      default:
        console.log(httpEvent);
        break;
    }
  }
}
