import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoDirective } from '@jsverse/transloco';
import { TranslocoService } from '@jsverse/transloco';

/**
 * New customer creation form component.
 *
 * <p>POSTs the form values to {@code POST /customer/create} via
 * {@link CustomerService#newCustomer$} and resets the form to its defaults on success.
 *
 * <h3>This screen fetches nothing on init, deliberately</h3>
 * It used to call {@code customers$()} — pulling an entire page of customers — purely so the navbar
 * could show the caller's name, discarding everything else in the response. The navbar now reads
 * the user from {@link CurrentUserService}, so that request has no remaining purpose and is gone.
 *
 * <p>The consequence is that the form has nothing to wait for: {@link newCustomerState} starts at
 * {@code LOADED} rather than {@code LOADING}. The state machine is kept because the create flow
 * still uses it to surface errors, not because anything loads.
 */
@Component({
  selector: 'app-new-customer',
  imports: [RouterModule, FormsModule, NavbarComponent, TranslocoDirective],
  templateUrl: './new-customer.component.html',
  standalone: true,
  styleUrls: ['./new-customer.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewCustomerComponent {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;

  /**
   * Drives the template's rendering for the creation flow.
   *
   * <p>Starts {@code LOADED}: there is no init request to wait on, and starting at
   * {@code LOADING} would show a spinner that nothing would ever resolve.
   */
  newCustomerState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({
    dataState: DataState.LOADED,
  });

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
  /** Translates toast copy at call time, so a language switch applies to the next toast. */
  private readonly transloco = inject(TranslocoService);

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
    this.customerService
      .newCustomer$(newCustomerForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          newCustomerForm.reset({ type: 'INDIVIDUAL', status: 'ACTIVE' });
          this.isLoading.set(false);
          this.notification.onSuccess(this.transloco.translate('toasts.customerCreated'));
          this.newCustomerState.set({ dataState: DataState.LOADED });
        },
        error: (error: string) => {
          // Stays LOADED, not ERROR: the form must remain on screen with the user's typing intact
          // so they can correct and resubmit. The toast carries the failure.
          this.isLoading.set(false);
          this.notification.onError(error);
          this.newCustomerState.set({ dataState: DataState.LOADED, error });
        },
      });
  }
}
