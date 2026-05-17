import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, map, Observable, of, startWith } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { NewInvoiceDataInterface } from '../../interface/appstates.interface';
import { CustomerService } from '../../service/customer.service';
import { catchError } from 'rxjs/operators';

/**
 * New invoice creation form.
 *
 * On init, calls {@code GET /customer/invoice/new} to load the authenticated user
 * (for the navbar) and the full customer list (for the customer dropdown).
 * On submit, calls {@code POST /customer/invoice/addtocustomer/:customerId}.
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

  /**
   * Drives the template — emits loading, loaded, or error states for the creation
   * flow, including the navbar user on every resolved state.
   */
  newInvoiceState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<NewInvoiceDataInterface>>>;

  /**
   * Injected service used to fetch the initial page data and POST new invoices.
   */
  protected readonly customerService = inject(CustomerService);

  /**
   * Caches the most recent API response so the template can remain in
   * {@code DataState.LOADED} as the {@code startWith} value while a create
   * request is in flight.
   */
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<NewInvoiceDataInterface>>(null);

  /**
   * Controls the submit button's disabled state and spinner visibility
   * while a create request is in flight.
   */
  private isLoadingSubject = new BehaviorSubject<boolean>(false);

  /**
   * Observable of the current submission-in-progress state, consumed by the template
   * to show the spinner and disable the submit button.
   */
  protected isLoading$ = this.isLoadingSubject.asObservable();

  /**
   * Loads the authenticated user and full customer list from
   * {@code GET /customer/invoice/new} to populate the navbar and customer dropdown.
   */
  ngOnInit(): void {
    this.newInvoiceState$ = this.customerService.newInvoice$().pipe(
      map((response) => {
        this.dataSubject.next(response);
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADING }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error })),
    );
  }

  /**
   * Submits the invoice form to {@code POST /customer/invoice/addtocustomer/:customerId}.
   *
   * Extracts {@code customerId} from the form value to use as the path variable;
   * the remaining fields are sent as the invoice body. Resets the form on success.
   *
   * @param invoiceForm - the Angular template-driven form containing the invoice fields
   */
  createNewInvoice(invoiceForm: NgForm): void {
    this.dataSubject.next({ ...this.dataSubject.value, message: '' });
    this.isLoadingSubject.next(true);
    this.newInvoiceState$ = this.customerService.addInvoiceToCustomer$(invoiceForm.value.customerId, invoiceForm.value).pipe(
      map((response) => {
        console.log(response);
        invoiceForm.reset({ status: 'PENDING' });
        this.isLoadingSubject.next(false);
        this.dataSubject.next(response);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => of({ dataState: DataState.LOADED, error })),
    );
  }
}
