import { Component, OnInit } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { ExtractArrayValuePipe } from '../../pipe/extract-array-value.pipe';

/**
 * Customer detail view showing profile fields, invoice count, and invoice history.
 *
 * Stub implementation — real data will be wired to GET /customer/get/:id once
 * the full customer detail backend integration is complete.
 */
@Component({
  selector: 'app-customer-details',
  imports: [CommonModule, RouterModule, FormsModule, NavbarComponent, DatePipe, ExtractArrayValuePipe],
  templateUrl: './customer-details.component.ts.html',
  styleUrl: './customer-details.component.ts.css',
})
export class CustomerDetailsComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  customerState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;
  /** Stub loading flag — will be driven by a real BehaviorSubject when wired to the API. */
  readonly isLoading$ = of(false);

  ngOnInit(): void {
    this.customerState$ = of({
      dataState: DataState.LOADED,
      appData: {
        statusCode: 200, message: '', timestamp: new Date(), status: 'OK',
        data: {
          user: { firstName: 'Test', lastName: 'User', roleName: 'ROLE_ADMIN', email: 'test@test.com' },
          customer: {
            id: 1, name: 'Stub Customer', email: 'customer@example.com',
            address: '123 Main St', type: 'INDIVIDUAL', status: 'ACTIVE',
            imageUrl: 'https://www.gravatar.com/avatar/?d=mp', phone: '555-0100',
            invoices: [],
          },
        },
      },
    });
  }

  /** Stub — will submit the updated customer to PUT /customer/update/:id. */
  updateCustomer(form: NgForm): void {
    console.log('updateCustomer stub:', form.value);
  }
}
