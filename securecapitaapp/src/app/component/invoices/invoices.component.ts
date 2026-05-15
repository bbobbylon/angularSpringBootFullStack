import { Component, OnInit } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';

/**
 * All-invoices list view with pagination.
 *
 * Stub implementation — real data will be wired to GET /customer/invoice/list
 * once the full invoice list backend integration is complete.
 */
@Component({
  selector: 'app-invoices',
  imports: [CommonModule, RouterModule, NavbarComponent, DatePipe],
  templateUrl: './invoices.component.html',
  styleUrl: './invoices.component.css',
})
export class InvoicesComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  /**
   * Drives the invoices list template — emits a new snapshot on page navigation.
   *
   * Currently seeded with stub data. Will be replaced by a live stream from
   * {@code GET /customer/invoice/list} once the invoice list integration is complete.
   */
  invoicesState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;
  private currentPageSubject = new BehaviorSubject<number>(0);
  /** Observable of the current 0-based page index, used by the template to highlight the active page. */
  currentPage$ = this.currentPageSubject.asObservable();

  /**
   * Seeds {@link invoicesState$} with stub data so the template renders without a backend call.
   *
   * Will be replaced by a {@code combineLatest} + {@code switchMap} stream wired to
   * {@code GET /customer/invoice/list} once the invoice list backend integration is complete.
   */
  ngOnInit(): void {
    this.invoicesState$ = of({
      dataState: DataState.LOADED,
      appData: {
        statusCode: 200,
        message: '',
        timestamp: new Date(),
        status: 'OK',
        data: {
          user: { firstName: 'Test', lastName: 'User', roleName: 'ROLE_ADMIN', email: 'test@test.com' },
          page: { content: [], totalPages: 0 },
        },
      },
    });
  }

  /** Stub — will navigate to the next or previous invoice page. */
  goToNextOrPreviousPage(direction: string): void {
    console.log('goToNextOrPreviousPage stub:', direction);
  }

  /** Stub — will jump directly to the given invoice page index. */
  goToPage(pageIndex: number): void {
    this.currentPageSubject.next(pageIndex);
  }
}
