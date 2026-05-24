import { DatePipe, NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule, NgForm } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { DataState } from '../../../enumeration/datastate.enum';
import { CustomerStateInterface } from '../../../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { UserInterface } from '../../../interface/user.interface';
import { ExtractArrayValuePipe } from '../../../pipe/extract-array-value.pipe';
import { CustomerService } from '../../../service/customer.service';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';

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
  imports: [NgClass, DatePipe, RouterModule, FormsModule, NavbarComponent, ExtractArrayValuePipe],
  templateUrl: './customer-details.component.html',
  standalone: true,
  styleUrl: './customer-details.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerDetailsComponent implements OnInit {
  /**
   * The logged-in user, injected by the parent route component.
   * Used to display the user's name and avatar in the navbar.
   */
  @Input() user: UserInterface;
  /** Bound automatically by the router via {@code withComponentInputBinding()} — matches the {@code :id} segment in {@code customers/:id}. */
  @Input() id: number;
  /** Exposes the {@link DataState} enum to the template for switch-case rendering. */
  readonly DataState = DataState;
  /** Drives the template — emits the full customer state (user + customer record) once loaded. */
  customerState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerStateInterface>>>({ dataState: DataState.LOADING });
  private readonly avatarColors = ['0D8ABC', '2ECC71', 'E74C3C', '9B59B6', 'F39C12', '1ABC9C', 'E67E22'];
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private data = signal<CustomHttpResponseInterface<CustomerStateInterface>>(null);
  private readonly destroyRef = inject(DestroyRef);
  /**
   * Tracks whether a form submission is in progress.
   *
   * Bound to the submit button's {@code [disabled]} attribute and the spinner
   * in the template to prevent duplicate requests.
   */
  protected isLoading = signal(false);
  /**
   * Pool of local asset images used as deterministic fallback avatars.
   *
   * The image for a given customer is selected by {@code id % localDefaultImages.length},
   * ensuring the same customer always gets the same placeholder across renders.
   */

  /**
   * Fetches the customer record for the {@link id} bound from the {@code :id} route param.
   *
   * {@code withComponentInputBinding()} in {@code app.config.ts} sets {@link id} before
   * this method runs, so no {@code ActivatedRoute} subscription is needed.
   */
  ngOnInit(): void {
    this.customerService.customerId$(this.id).pipe(
      map((response) => {
        console.log('Fetched customer detail data:', response);
        this.data.set(response);
        return { dataState: DataState.LOADED, appData: response } as GlobalStateInterface<CustomHttpResponseInterface<CustomerStateInterface>>;
      }),
      startWith({ dataState: DataState.LOADING } as GlobalStateInterface<CustomHttpResponseInterface<CustomerStateInterface>>),
      catchError((error: string) => of({ dataState: DataState.ERROR, error } as GlobalStateInterface<CustomHttpResponseInterface<CustomerStateInterface>>)),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((state) => this.customerState.set(state));
  }
  /**
   * Submits the customer edit form and persists the updated record via the service.
   *
   * Sets {@link isLoading} to {@code true} for the duration of the request so
   * the submit button is disabled and the spinner is shown. On success, caches the
   * response in the {@link data} signal and restores the LOADED state. On error, clears
   * the loading flag and emits an ERROR state so the template can display the message.
   *
   * @param customerForm - the submitted NgForm containing the updated customer field values
   */
  update(customerForm: NgForm): void {
    this.isLoading.set(true);
    this.customerState.set({ dataState: DataState.LOADED, appData: this.data() });
    this.customerService.updateCustomer$(customerForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log('Updating customer detail data:', response);
          this.isLoading.set(false);
          this.data.set({
            ...response,
            data: { ...response.data, customers: { ...response.data.customers, invoices: this.data()?.data?.customers?.invoices } },
          });
          this.customerState.set({ dataState: DataState.LOADED, appData: this.data() });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.customerState.set({ dataState: DataState.ERROR, error });
        },
      });
  }

  /**
   * Returns a deterministic local fallback image path for the given customer ID.
   *
   * Uses modulo arithmetic against {@code localDefaultImages} so that each customer
   * always receives the same placeholder regardless of render order or page.
   *
   * @param id - the customer's numeric ID used to index into the image pool
   * @returns a relative path to an asset image under {@code assets/images/}
   */
  protected getAvatarColor(id: number): string {
    return '#' + this.avatarColors[id % this.avatarColors.length];
  }
}
