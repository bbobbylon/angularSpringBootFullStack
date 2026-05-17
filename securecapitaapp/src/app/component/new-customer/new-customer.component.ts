import { Component, inject, Input, OnInit, signal } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, map, Observable, of, startWith } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { UserInterface } from '../../interface/user.interface';
import { CustomerListDataInterface } from '../../interface/appstates.interface';
import { CustomerService } from '../../service/customer.service';
import { catchError } from 'rxjs/operators';

/**
 * New customer creation form component.
 *
 * On init, fetches the authenticated user via {@link CustomerService#customers$} so
 * the navbar can display the current user. On submit, POSTs the form values to
 * {@code POST /customer/create} via {@link CustomerService#newCustomer$} and resets
 * the form to its default values on success.
 *
 * TODO: Replace the {@code customers$()} init call with a lighter user-only endpoint
 * once one exists — we only need the {@code user} field for the navbar.
 */
@Component({
  selector: 'app-new-customer',
  imports: [AsyncPipe, RouterModule, FormsModule, NavbarComponent],
  templateUrl: './new-customer.component.html',
  standalone: true,
  styleUrls: ['./new-customer.component.css'],
})
export class NewCustomerComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the template — emits loading, loaded, or error states for the creation
   * flow, including the navbar user on every resolved state.
   */
  newCustomerState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>;

  /**
   * The currently authenticated user, passed in when this component is used in an
   * embedded context. Falls back to the API-fetched user when used as a routed page.
   */
  @Input() user: UserInterface;

  /**
   * Application title signal — retained for potential future page-title binding.
   */
  readonly title = signal('securecapitaapp');

  /**
   * Placeholder for the current user's permission set.
   *
   * Reserved for future role-based UI gating on this form (e.g., hiding admin-only fields).
   */
  protected readonly permissions = signal<string[]>([]);

  /**
   * Injected service used to fetch the initial user data and POST new customers.
   */
  protected readonly customerService = inject(CustomerService);

  /**
   * Caches the most recent API response so the template can remain in
   * {@code DataState.LOADED} as the {@code startWith} value while a create
   * request is in flight.
   */
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerListDataInterface>>(null);

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

  //TODO change functinoality to just get the user data instead of calling the customerService and fetching all customers, we just need the user data to prefill the form and then submit the form to create a new customer
  /**
   * Fetches the authenticated user's data on component init to populate the navbar.
   *
   * Uses {@link CustomerService#customers$} as a temporary approach — only the
   * {@code user} field of the response is needed; the customer page data is discarded.
   *
   * TODO: Replace with a lighter user-only endpoint once available.
   */
  ngOnInit(): void {
    this.newCustomerState$ = this.customerService.customers$().pipe(
      map((response) => {
        console.log('Fetched New customer data:', response);
        this.dataSubject.next(response);
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADING }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error })),
    );
  }

  /**
   * Submits the new customer form to {@code POST /customer/create}.
   *
   * Sets the loading flag while the request is in flight, resets the form to its
   * default values ({@code type: 'INDIVIDUAL', status: 'ACTIVE'}) on success, and
   * remains in {@code DataState.LOADED} with an error message on failure.
   *
   * @param newCustomerForm - the Angular template-driven form containing the customer fields
   */
  createNewCustomer(newCustomerForm: NgForm): void {
    this.isLoadingSubject.next(true);
    this.newCustomerState$ = this.customerService.newCustomer$(newCustomerForm.value).pipe(
      map((response) => {
        console.log('Fetched customer data:', response);
        newCustomerForm.reset({ type: 'INDIVIDUAL', status: 'ACTIVE' });
        this.isLoadingSubject.next(false);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => of({ dataState: DataState.LOADED, error })),
    );
  }
}
