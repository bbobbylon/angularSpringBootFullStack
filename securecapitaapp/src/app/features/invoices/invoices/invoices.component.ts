import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DatePipe, NgClass } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { map, of, startWith, switchMap } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerService } from '../../../service/customer.service';
import { InvoiceListDataInterface } from '../../../interface/appstates.interface';
import { catchError } from 'rxjs/operators';
import { HttpEvent, HttpEventType } from '@angular/common/http';
import { saveAs } from 'file-saver';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';

/**
 * All-invoice list view with pagination.
 *
 * Fetches a paginated list of invoices from {@code GET /customer/invoice/list}
 * and renders them in a table with status badges and a Print action per row.
 *
 * State is held in {@link invoiceState}, a writable signal driven by changes to
 * the {@link currentPage} signal — bridged through {@code toObservable} so the
 * existing RxJS pipeline (with {@code switchMap} cancel-stale semantics) is
 * preserved without rewriting the service layer.
 */
@Component({
  selector: 'app-invoices',
  imports: [NgClass, RouterModule, NavbarComponent, DatePipe],
  templateUrl: './invoices.component.html',
  styleUrl: './invoices.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoicesComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the entire template — set to LOADING/LOADED/ERROR by the
   * page-fetch subscription in {@link ngOnInit}.
   */
  invoiceState = signal<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>({
    dataState: DataState.LOADING,
  });
  protected readonly router = inject(Router);
  /**
   * Tracks the current 0-based page index. Changing this value triggers a re-fetch
   * via the {@code toObservable} bridge in {@link ngOnInit}.
   */
  protected currentPage = signal(0);
  /** Readonly view of the current page for the template's pagination controls. */
  currentPage$ = this.currentPage.asReadonly();
  private readonly _currentPageObs$ = toObservable(this.currentPage);
  private readonly customerService = inject(CustomerService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  /**
   * Caches the most recent successful API response so pagination updates can return
   * {@code DataState.LOADED} immediately as the {@code startWith} value while the next
   * request is in flight.
   */
  private data = signal<CustomHttpResponseInterface<InvoiceListDataInterface> | undefined>(undefined);
  /** Drives the progress bar in the template during an Excel report download. */
  protected fileStatus = signal<{ status: string; type: string; percent: number } | undefined>(undefined);

  /**
   * Wires the {@code currentPage} signal to the invoice-list endpoint.
   *
   * {@code toObservable} converts the signal into an Observable so we can keep
   * using {@code switchMap}'s cancel-on-new-emission behavior — pagination clicks
   * cancel any in-flight request rather than racing it. The inner pipe's
   * {@code startWith} re-emits LOADING (with cached data) on every page change so
   * the template never blanks out between fetches.
   */
  ngOnInit(): void {
    this._currentPageObs$
      .pipe(
        switchMap((page) =>
          this.customerService.invoices$(page).pipe(
            map((response) => {
              this.data.set(response);
              return { dataState: DataState.LOADED, appData: response } as GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>;
            }),
            startWith({ dataState: DataState.LOADING, appData: this.data() } as GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>),
            catchError((error: string) => {
              this.notification.onError(error);
              return of({ dataState: DataState.ERROR, error, appData: this.data() } as GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.invoiceState.set(state));
  }

  /**
   * Advances or retreats one page. The fetch is triggered automatically because
   * {@link ngOnInit}'s {@code toObservable(currentPage)} bridge re-emits on every
   * signal change.
   *
   * @param direction - {@code 'forward'} to increment the page, {@code 'backward'} to decrement
   */
  goToNextOrPreviousPage(direction: string): void {
    const step = direction === 'forward' ? 1 : -1;
    this.currentPage.update((current) => current + step);
  }

  /**
   * Jumps directly to a specific page index. Triggers the same re-fetch path as
   * {@link goToNextOrPreviousPage} via the {@code currentPage} signal.
   *
   * @param pageIndex - the 0-based index of the target page
   */
  goToPage(pageIndex: number): void {
    this.currentPage.set(pageIndex);
  }

  /**
   * Triggers the invoice XLSX download from {@code GET /customer/invoice/download/report}.
   *
   * Progress events are routed to {@link reportProgres}, which updates the
   * {@link fileStatus} signal so the template's progress bar reacts via Signal
   * binding. The main {@link invoiceState} signal is
   * intentionally NOT touched — the download button lives inside the LOADED branch
   * of the template, so transitioning out of LOADED would hide the button itself.
   */
  report(): void {
    this.customerService.downloadInvoiceReport$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.reportProgres(response),
        error: (error: string) => {
          this.notification.onError(error);
          console.error('Invoice report download failed:', error);
        },
      });
  }

  /**
   * Handles individual {@link HttpEvent} emissions from the download stream.
   *
   * Updates the {@link fileStatus} signal with progress percentage during the download,
   * and triggers {@code saveAs} when the complete {@link HttpEventType.Response} arrives.
   *
   * @param httpEvent - an event emitted by Angular's HTTP pipeline during the download
   */
  private reportProgres(httpEvent: HttpEvent<string[] | Blob>): void {
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
        saveAs(new File([httpEvent.body as Blob], 'invoice_report.xlsx', { type: `${httpEvent.headers.get('Content-Type')};charset=utf-8` }));
        this.notification.onSuccess('Report downloaded successfully');
        this.fileStatus.set(undefined);
        break;
      default:
        console.log(httpEvent);
        break;
    }
  }
}
