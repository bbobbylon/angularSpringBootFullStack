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
 * New invoice creation form.
 *
 * Stub implementation — real submission will POST to /customer/invoice/addtocustomer/:id
 * once the full invoice creation backend integration is complete.
 * The customer dropdown is populated from a stub list; real data comes from GET /customer/invoice/new.
 */
@Component({
  selector: 'app-new-invoice',
  imports: [CommonModule, RouterModule, FormsModule, NavbarComponent],
  templateUrl: './new-invoice.component.html',
  styleUrl: './new-invoice.component.css',
})
export class NewInvoiceComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  newInvoiceState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;
  /** Stub loading flag — matches the typo in the template (`isLaoding$`). */
  readonly isLaoding$ = of(false);

  ngOnInit(): void {
    this.newInvoiceState$ = of({
      dataState: DataState.LOADED as DataState,
      appData: {
        statusCode: 200, message: '', timestamp: new Date(), status: 'OK',
        data: {
          user: { firstName: 'Test', lastName: 'User', roleName: 'ROLE_ADMIN', email: 'test@test.com' },
          customers: [
            { id: 1, name: 'Stub Customer A' },
            { id: 2, name: 'Stub Customer B' },
          ],
        },
      },
    });
  }

  /** Stub — will POST the new invoice to /customer/invoice/addtocustomer/:customerId. */
  newInvoice(form: NgForm): void {
    console.log('newInvoice stub:', form.value);
  }
}
