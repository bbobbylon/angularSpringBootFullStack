import { CommonModule } from '@angular/common';
import { Component, inject, Input, OnInit } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router, RouterModule } from '@angular/router';
import { BehaviorSubject, catchError, map, Observable, of, startWith, switchMap } from 'rxjs';
import { DataState } from '../../enumeration/datastate.enum';
import { CustomerListData, CustomerStateInterface } from '../../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { UserInterface } from '../../interface/user.interface';
import { ExtractArrayValuePipe } from '../../pipe/extract-array-value.pipe';
import { CustomerService } from '../../service/customer.service';
import { NavbarComponent } from '../navbar/navbar.component';

/**
 * Customer detail view showing a single customer's profile fields, invoice count, and invoice history.
 *
 * On load, {@link ngOnInit} reads the {@code :id} route param and calls
 * {@code GET /customer/get/:id} to populate the view. Form submission is handled
 * by {@link update}, which calls {@code POST /customer/update} and restores the
 * last cached response on success.
 */
@Component({
  selector: 'app-customer-details',
  imports: [CommonModule, RouterModule, FormsModule, NavbarComponent, ExtractArrayValuePipe],
  templateUrl: './customer-details.component.html',
  standalone: true,
  styleUrl: './customer-details.component.css',
})
export class CustomerDetailsComponent implements OnInit {
  /**
   * The logged-in user, injected by the parent route component.
   * Used to display the user's name and avatar in the navbar.
   */
  @Input() user: UserInterface;
  /** Exposes the {@link DataState} enum to the template for switch-case rendering. */
  readonly DataState = DataState;
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerStateInterface>>(null);

  /**
   * Reserved for a future embedded customer list on the detail view (e.g. related customers).
   * Unused in the current stub implementation.
   */
  customersState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListData>>>;
  /**
   * Reserved for a future home-dashboard link from the detail view.
   * Unused in the current stub implementation.
   */
  homeState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListData>>>;
  /**
   * Drives the template — emits the full customer state (user + customer record) once loaded.
   *
   * Initialised with hardcoded stub data by {@link ngOnInit}. Will be replaced by the live
   * {@link ActivatedRoute} param stream from {@link aMethodThatDoesStuff} once the backend
   * endpoint is ready.
   */
  customerState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerStateInterface>>>;
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private readonly activatedRoute = inject(ActivatedRoute);
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  /**
   * Emits {@code true} while a form submission or navigation action is in progress.
   *
   * Bound to the submit button's {@code [disabled]} attribute to prevent duplicate requests.
   */
  protected isLoading$ = this.isLoadingSubject.asObservable();
  /**
   * The route parameter key used to extract the customer ID from the URL.
   *
   * Matches the {@code :id} segment defined in the route table ({@code path: 'customers/:id'}),
   * so renaming the route param only requires changing this constant.
   */
  private readonly CUSTOMER_ID: string = 'id';

  /**
   * Wires {@link customerState$} to the route's {@code :id} parameter so the view
   * reloads automatically whenever the URL changes.
   *
   * Reads the {@code id} path segment via {@link ActivatedRoute#paramMap}, coerces it to
   * a number with the unary {@code +} operator, and delegates to
   * {@link CustomerService#customerId$}. {@code switchMap} cancels any in-flight request
   * when a new param emission arrives, preventing stale responses from overwriting newer results.
   *
   * Intended to replace the body of {@link ngOnInit} once {@code GET /customers/:id} is ready.
   */
  ngOnInit(): void {
    this.customerState$ = this.activatedRoute.paramMap.pipe(
      switchMap((params: ParamMap) => {
        // params.get(this.CUSTOMER_ID) extracts the :id segment from the URL, e.g. /customers/123 → "123"
        return this.customerService.customerId$(+params.get(this.CUSTOMER_ID)).pipe(
          map((response) => {
            console.log('Fetched customer detail data:', response);
            this.dataSubject.next(response);
            return { dataState: DataState.LOADED, appData: response };
          }),
          startWith({ dataState: DataState.LOADING }), // emit the last cached data with a LOADING state while the request is in-flight so the template can show the spinner without losing the existing data
          catchError((error: string) => of({ dataState: DataState.ERROR, error })),
        );
      }),
    );
  }
  /**
   * Submits the customer edit form and persists the updated record via the service.
   *
   * Sets {@link isLoadingSubject} to {@code true} for the duration of the request so
   * the submit button is disabled and the spinner is shown. On success, caches the
   * response in {@link dataSubject} and restores the LOADED state. On error, clears
   * the loading flag and emits an ERROR state so the template can display the message.
   *
   * @param customerForm - the submitted NgForm containing the updated customer field values
   */
  update(customerForm: NgForm): void {
    this.isLoadingSubject.next(true);
    this.customerState$ = this.customerService.updateCustomer$(customerForm.value).pipe(
      map((response) => {
        console.log('Updating customer detail data:', response);
        this.isLoadingSubject.next(false);
        this.dataSubject.next({
          ...response,
          data: { ...response.data, customers: { ...response.data.customers, invoices: this.dataSubject.value?.data?.customers?.invoices } },
        }); // preserve the existing invoices list in the updated state since the update endpoint doesn't return it
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }), // optimistically update the view with the submitted values while the request is in-flight
      catchError((error: string) => {
        this.isLoadingSubject.next(false);
        return of({ dataState: DataState.ERROR, error });
      }),
    );
  }

  /**
   * Submits the customer edit form to update the customer record.
   *
   * Stub — will call {@code PUT /customer/update/:id} once the backend update
   * endpoint is implemented.
   *
   * @param form - the submitted NgForm containing the updated customer field values
   */
  updateCustomer(form: NgForm): void {
    console.log('updateCustomer stub:', form.value);
  }
}
