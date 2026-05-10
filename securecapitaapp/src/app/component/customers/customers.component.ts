import { ChangeDetectionStrategy, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { ExtractArrayValuePipe } from '../../pipe/extract-array-value.pipe';

/**
 * All-customers list view with search and pagination.
 *
 * Stub implementation — real data will be wired to GET /customer/list and
 * GET /customer/search once the full customer list backend integration is complete.
 */
@Component({
  selector: 'app-customers',
  imports: [CommonModule, RouterModule, FormsModule, NavbarComponent, ExtractArrayValuePipe],
  templateUrl: './customers.component.html',
  styleUrl: './customers.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  customersState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;
  private currentPageSubject = new BehaviorSubject<number>(0);
  /** Observable of the current 0-based page index, used by the template to highlight the active page. */
  currentPage$ = this.currentPageSubject.asObservable();

  ngOnInit(): void {
    this.customersState$ = of({
      dataState: DataState.LOADED,
      appData: {
        statusCode: 200, message: '', timestamp: new Date(), status: 'OK',
        data: {
          user: { firstName: 'Test', lastName: 'User', roleName: 'ROLE_ADMIN', email: 'test@test.com' },
          page: { content: [], totalPages: 0 },
        },
      },
    });
  }

  /** Stub — will search customers by name via GET /customer/search. */
  searchCustomers(form: NgForm): void {
    console.log('searchCustomers stub:', form.value.name);
  }

  /** Stub — will navigate to the next or previous customer page. */
  goToNextOrPreviousPage(direction: string, name?: string): void {
    console.log('goToNextOrPreviousPage stub:', direction, name);
  }

  /** Stub — will jump directly to the given customer page index. */
  goToPage(pageIndex: number, name?: string): void {
    this.currentPageSubject.next(pageIndex);
  }
}
