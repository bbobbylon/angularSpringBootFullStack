import { ChangeDetectionStrategy, Component, inject, OnInit, Signal, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { BehaviorSubject, map, Observable, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerService } from '../../../service/customer.service';
import { InvoiceListDataInterface } from '../../../interface/appstates.interface';
import { catchError } from 'rxjs/operators';
import { HttpEvent, HttpEventType } from '@angular/common/http';
import { saveAs } from 'file-saver';
import { toSignal } from '@angular/core/rxjs-interop';

/**
 * All-invoice list view with pagination.
 *
 * Fetches a paginated list of invoices from {@code GET /customer/invoice/list}
 * and renders them in a table with status badges and a Print action per row.
 */
@Component({
  selector: 'app-invoices',
  imports: [CommonModule, RouterModule, NavbarComponent, DatePipe],
  templateUrl: './invoices.component.html',
  styleUrl: './invoices.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoicesComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the entire template — emits a new {@link GlobalStateInterface} snapshot
   * whenever the page index changes.
   */
  invoiceState: Signal<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>;
  invoiceState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>;
  protected readonly router = inject(Router);
  /**
   * Tracks the current 0-based page index.
   * Emitting a new value triggers a new fetch in {@link goToPage}.
   */
  protected currentPage = signal(0);
  /** Observable of the current 0-based page index, used by the template to highlight the active page. */
  currentPage$ = this.currentPage.asReadonly();
  private readonly customerService = inject(CustomerService);
  /**
   * Caches the most recent successful API response so pagination updates can return
   * {@code DataState.LOADED} immediately as the {@code startWith} value while the next
   * request is in flight.
   */
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<InvoiceListDataInterface>>(null);
  private fileStatusSubject = new BehaviorSubject<{ status: string; type: string; percent: number }>(undefined);
  /** Emits download progress state for the progress bar in the template. */
  fileStatus$ = this.fileStatusSubject.asObservable();

  ngOnInit(): void {
    this.loadPage(0);
  }

  /*  ngOnInit(): void {
    const invoices = this.currentPage.pipe(
      switchMap((page) =>
        this.customerService.invoices$(page).pipe(
          map((response) => {
            this.dataSubject.next(response);
            return { dataState: DataState.LOADED, appData: response };
          }),
          startWith({ dataState: DataState.LOADING }),
          catchError((error: string) => of({ dataState: DataState.ERROR, error })),
        ),
      ),
    );
    this.invoiceState = toSignal(invoices, { initialValue: { dataState: DataState.LOADING } });
  }*/

  /**
   * Advances or retreats one page.
   *
   * @param direction - {@code 'forward'} to increment the page, {@code 'backward'} to decrement
   */
  goToNextOrPreviousPage(direction: string): void {
    const step = direction === 'forward' ? 1 : -1;
    this.currentPage.update((current) => current + step);
  }

  /**
   * Jumps directly to a specific page index.
   *
   * @param pageIndex - the 0-based index of the target page
   */
  goToPage(pageIndex: number): void {
    this.currentPage.set(pageIndex);
  }

  /**
   * Triggers the invoice XLSX download from {@code GET /customer/invoice/download/report}.
   *
   * Reassigns {@link invoiceState$} to the download stream so the progress bar in the
   * template reacts to {@link HttpEventType} emissions. Once the download completes,
   * {@link reportProgres} calls {@code saveAs} and resets the progress bar.
   */
  report(): void {
    const report$ = this.customerService.downloadInvoiceReport$().pipe(
      map((response) => {
        this.reportProgres(response);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error, appData: this.dataSubject.value })),
    );
    this.invoiceState = toSignal(report$, { initialValue: { dataState: DataState.LOADING, appData: this.dataSubject.value } });
  }

  /**
   * Fetches a specific page of invoices and updates {@link invoiceState$}.
   *
   * @param page - zero-based page index to fetch
   */
  private loadPage(page: number): void {
    this.currentPage.set(page);
  }

  /**
   * Handles individual {@link HttpEvent} emissions from the download stream.
   *
   * Updates {@link fileStatusSubject} with progress percentage during the download,
   * and triggers {@code saveAs} when the complete {@link HttpEventType.Response} arrives.
   *
   * @param httpEvent - an event emitted by Angular's HTTP pipeline during the download
   */
  private reportProgres(httpEvent: HttpEvent<string[] | Blob>): void {
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
        saveAs(new File([httpEvent.body as Blob], 'invoice_report.xlsx', { type: `${httpEvent.headers.get('Content-Type')};charset=utf-8` }));
        this.fileStatusSubject.next(undefined);
        break;
      default:
        console.log(httpEvent);
        break;
    }
  }
}
