import { Component, inject, OnInit } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { BehaviorSubject, map, Observable, of, startWith } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { CustomerService } from '../../service/customer.service';
import { InvoiceListDataInterface } from '../../interface/appstates.interface';
import { catchError } from 'rxjs/operators';

/**
 * All-invoices list view with pagination.
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
})
export class InvoicesComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the entire template — emits a new {@link GlobalStateInterface} snapshot
   * whenever the page index changes.
   */
  invoiceState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>;

  protected readonly router = inject(Router);
  private readonly customerService = inject(CustomerService);

  /**
   * Caches the most recent successful API response so pagination updates can return
   * {@code DataState.LOADED} immediately as the {@code startWith} value while the next
   * request is in flight.
   */
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<InvoiceListDataInterface>>(null);

  /**
   * Tracks the current 0-based page index.
   * Emitting a new value triggers a new fetch in {@link goToPage}.
   */
  private currentPageSubject = new BehaviorSubject<number>(0);

  /** Observable of the current 0-based page index, used by the template to highlight the active page. */
  currentPage$ = this.currentPageSubject.asObservable();

  ngOnInit(): void {
    this.loadPage(0);
  }

  /**
   * Advances or retreats one page.
   *
   * @param direction - {@code 'forward'} to increment the page, {@code 'backward'} to decrement
   */
  goToNextOrPreviousPage(direction: string): void {
    const step = direction === 'forward' ? 1 : -1;
    this.loadPage(this.currentPageSubject.value + step);
  }

  /**
   * Jumps directly to a specific page index.
   *
   * @param pageIndex - the 0-based index of the target page
   */
  goToPage(pageIndex: number): void {
    this.loadPage(pageIndex);
  }

  /**
   * Fetches a specific page of invoices and updates {@link invoiceState$}.
   *
   * @param page - zero-based page index to fetch
   */
  private loadPage(page: number): void {
    this.invoiceState$ = this.customerService.invoices$(page).pipe(
      map((response) => {
        this.dataSubject.next(response);
        this.currentPageSubject.next(page);
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADING }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error })),
    );
  }
}
