import { ChangeDetectionStrategy, Component, inject, OnInit, Signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { NewInvoiceDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { InvoiceLineItemInterface } from '../../../interface/invoice.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { catchError } from 'rxjs/operators';
import { toSignal } from '@angular/core/rxjs-interop';

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
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewInvoiceComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the template — emits loading, loaded, or error states for the creation
   * flow, including the navbar user on every resolved state.
   */
  newInvoiceState: Signal<GlobalStateInterface<CustomHttpResponseInterface<NewInvoiceDataInterface>>>;
  /**
   * The list of service line items the user is building for this invoice.
   * Starts with one empty row. Submitted alongside the form fields in {@link createNewInvoice}.
   * Kept outside the NgForm so the form's validity check stays focused on scalar fields.
   */
  serviceLines: InvoiceLineItemInterface[] = [{ name: '', price: 0 }];

  /**
   * Snapshot of the service catalog received from the API on init.
   * Stored separately from the observable so {@link onServiceSelected} can look up
   * the selected service by ID without needing async access to the template state.
   */
  availableServices: ServicesInterface[] = [];

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
   * Appends a blank service line to {@link serviceLines} so the user can select
   * an additional service for this invoice.
   */
  addServiceLine(): void {
    this.serviceLines.push({ name: '', price: 0 });
  }

  /**
   * Removes the service line at the given index from {@link serviceLines}.
   *
   * The remove button is only rendered when there are two or more lines, so
   * the last remaining line can never be deleted.
   *
   * @param index - zero-based position of the line to remove
   */
  removeServiceLine(index: number): void {
    this.serviceLines.splice(index, 1);
  }

  /**
   * Copies the name and standard price from the selected catalog entry into
   * the corresponding service line, replacing any previously entered values.
   *
   * The DOM {@code change} event always delivers the selected {@code <option>}
   * value as a string, so {@code +serviceId} coerces it to a number before
   * comparing against the numeric {@code id} on {@link ServicesInterface}.
   *
   * @param index     - zero-based index of the service line to update
   * @param serviceId - the string value of the selected option (catalog service ID)
   */
  onServiceSelected(index: number, serviceId: string): void {
    const service = this.availableServices.find((s) => s.id === +serviceId);
    if (service) {
      this.serviceLines[index].name = service.name;
      this.serviceLines[index].price = service.price;
    }
  }
  /**
   * Loads the authenticated user and full customer list from
   * {@code GET /customer/invoice/new} to populate the navbar and customer dropdown.
   */
  ngOnInit(): void {
    const newInvoice$ = this.customerService.newInvoice$().pipe(
      map((response) => {
        this.dataSubject.next(response);
        this.availableServices = response.data?.availableServices ?? [];
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADING }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error })),
    );
    this.newInvoiceState = toSignal(newInvoice$, { initialValue: { dataState: DataState.LOADING } });
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
    const invoicePayload = { ...invoiceForm.value, services: this.serviceLines };
    const newInvoice$ = this.customerService.addInvoiceToCustomer$(invoiceForm.value.customerId, invoicePayload).pipe(
      map((response) => {
        console.log(response);
        invoiceForm.reset({ status: 'PENDING' });
        this.serviceLines = [{ name: '', price: 0 }];
        this.isLoadingSubject.next(false);
        this.dataSubject.next(response);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => of({ dataState: DataState.LOADED, error })),
    );
    this.newInvoiceState = toSignal(newInvoice$);
  }
}
