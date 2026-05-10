import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';

/**
 * New customer creation form.
 *
 * Stub implementation — real submission will POST to /customer/create
 * once the full customer creation backend integration is complete.
 */
@Component({
  selector: 'app-new-customer',
  imports: [CommonModule, RouterModule, FormsModule, NavbarComponent],
  templateUrl: './new-customer.component.html',
  styleUrls: ['./new-customer.component.css'],
})
export class NewCustomerComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  newCustomerState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;
  /** Stub loading flag — matches the typo in the template (`isLaoding$`). */
  readonly isLaoding$ = of(false);

  ngOnInit(): void {
    this.newCustomerState$ = of({
      dataState: DataState.LOADED as DataState,
      appData: {
        statusCode: 200, message: '', timestamp: new Date(), status: 'OK',
        data: {
          user: { firstName: 'Test', lastName: 'User', roleName: 'ROLE_ADMIN', email: 'test@test.com' },
        },
      },
    });
  }

  /** Stub — will POST the new customer to /customer/create. */
  createCustomer(form: NgForm): void {
    console.log('createCustomer stub:', form.value);
  }
}
