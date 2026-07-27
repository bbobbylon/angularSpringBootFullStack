import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { UserInterface } from '../../../interface/user.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * New customer creation form component.
 *
 * On init, fetches the authenticated user via {@link CustomerService#customers$} so
 * the navbar can display the current user. On submit, POSTs the form values to
 * {@code POST /customer/create} via {@link CustomerService#newCustomer$} and resets
 * the form to its default values on success.
 *
 * State is held in {@link newCustomerState}, a writable signal mutated via {@code .set()}
 * from the init fetch and the create-customer event handler.
 *
 * TODO: Replace the {@code customers$()} init call with a lighter user-only endpoint
 * once one exists — we only need the {@code user} field for the navbar.
 */
@Component({
  selector: 'app-new-customer',
  imports: [RouterModule, FormsModule, NavbarComponent, TranslocoDirective],
  templateUrl: './new-customer.component.html',
  standalone: true,
  styleUrls: ['./new-customer.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewCustomerComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /** Drives the template's loading/success/error rendering for the creation flow. */
  newCustomerState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  /**
   * The currently authenticated user, passed in when this component is used in an
   * embedded context. Falls back to the API-fetched user when used as a routed page.
   */
  @Input() user: UserInterface | undefined;

  /** Application title signal — retained for potential future page-title binding. */
  readonly title = signal('tesseraapp');

  /**
   * Placeholder for the current user's permission set.
   *
   * Reserved for future role-based UI gating on this form (e.g., hiding admin-only fields).
   */
  protected readonly permissions = signal<string[]>([]);

  /** Injected service used to fetch the initial user data and POST new customers. */
  protected readonly customerService = inject(CustomerService);
  /**
   * Tracks whether a create request is in flight. Controls the submit button's
   * disabled state and spinner visibility.
   */
  protected isLoading = signal(false);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  /**
   * Caches the most recent successful API response so the form stays in
   * {@code DataState.LOADED} while a create request is in flight.
   */
  private data = signal<CustomHttpResponseInterface<CustomerListDataInterface> | undefined>(undefined);

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
    this.customerService
      .customers$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Fetched New customer data:', response);
          this.data.set(response);
          this.newCustomerState.set({ dataState: DataState.LOADED, appData: response });
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.newCustomerState.set({ dataState: DataState.ERROR, error });
        },
      });
  }

  /**
   * Submits the new customer form to {@code POST /customer/create}.
   *
   * Sets the loading flag while the request is in flight, resets the form to its
   * default values ({@code type: 'INDIVIDUAL', status: 'ACTIVE'}) on success, and
   * surfaces a server error on failure. The signal state stays in LOADED throughout
   * (matching the previous startWith behavior) so the form remains visible during
   * submission — only the spinner overlay reacts via the {@link isLoading} signal.
   *
   * @param newCustomerForm - the Angular template-driven form containing the customer fields
   */
  createNewCustomer(newCustomerForm: NgForm): void {
    this.isLoading.set(true);
    this.newCustomerState.set({ dataState: DataState.LOADED, appData: this.data() });
    this.customerService
      .newCustomer$(newCustomerForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Fetched customer data:', response);
          newCustomerForm.reset({ type: 'INDIVIDUAL', status: 'ACTIVE' });
          this.isLoading.set(false);
          this.notification.onSuccess('Customer created successfully');
          this.newCustomerState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.newCustomerState.set({ dataState: DataState.LOADED, error, appData: this.data() });
        },
      });
  }
}
