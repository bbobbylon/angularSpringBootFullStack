import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { NewInvoiceDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { InvoiceLineItemInterface } from '../../../interface/invoice.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

/**
 * New invoice creation form.
 *
 * On init, calls {@code GET /customer/invoice/new} to load the authenticated user
 * (for the navbar) and the full customer list (for the customer dropdown).
 * On submit, calls {@code POST /customer/invoice/addtocustomer/:customerId}.
 *
 * State lives in {@link newInvoiceState}, a writable signal mutated via {@code .set()}
 * by both the init fetch and the create-invoice submission.
 */
@Component({
  selector: 'app-new-invoice',
  imports: [RouterModule, FormsModule, NavbarComponent],
  templateUrl: './new-invoice.component.html',
  styleUrl: './new-invoice.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewInvoiceComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /** Drives the template — loading, loaded, or error states for the creation flow. */
  newInvoiceState = signal<GlobalStateInterface<CustomHttpResponseInterface<NewInvoiceDataInterface>>>({
    dataState: DataState.LOADING,
  });

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

  /** Injected service used to fetch the initial page data and POST new invoices. */
  protected readonly customerService = inject(CustomerService);
  private readonly destroyRef = inject(DestroyRef);
  /**
   * Caches the most recent successful API response so the form stays in
   * {@code DataState.LOADED} while a create request is in flight.
   */
  private data = signal<CustomHttpResponseInterface<NewInvoiceDataInterface>>(null);
  /**
   * Tracks whether a create request is in flight. Controls the submit button's
   * disabled state and spinner visibility.
   */
  protected isLoading = signal(false);

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
   *
   * The state signal is set to LOADING synchronously and updated to LOADED/ERROR
   * from the subscribe callbacks, with {@code takeUntilDestroyed} tying the
   * subscription to the component lifecycle.
   */
  ngOnInit(): void {
    this.customerService.newInvoice$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.data.set(response);
          this.availableServices = response.data?.availableServices ?? [];
          this.newInvoiceState.set({ dataState: DataState.LOADED, appData: response });
        },
        error: (error: string) => {
          this.newInvoiceState.set({ dataState: DataState.ERROR, error });
        },
      });
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
    this.data.set({ ...this.data(), message: '' });
    this.isLoading.set(true);
    this.newInvoiceState.set({ dataState: DataState.LOADED, appData: this.data() });
    const invoicePayload = { ...invoiceForm.value, services: this.serviceLines };
    this.customerService.addInvoiceToCustomer$(invoiceForm.value.customerId, invoicePayload)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log(response);
          invoiceForm.reset({ status: 'PENDING' });
          this.serviceLines = [{ name: '', price: 0 }];
          this.isLoading.set(false);
          this.data.set(response);
          this.newInvoiceState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.newInvoiceState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }
}
