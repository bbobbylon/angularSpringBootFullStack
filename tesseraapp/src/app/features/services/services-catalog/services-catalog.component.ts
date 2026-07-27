import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { CustomerService } from '../../../service/customer.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { NewInvoiceDataInterface } from '../../../interface/appstates.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { UserInterface } from '../../../interface/user.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Service/app catalog page — {@code /services}.
 *
 * Displays all services registered in the backend catalog so users can browse
 * available offerings and jump directly to a new invoice pre-selected for a
 * service. Data is fetched from {@code GET /customer/invoice/new}, which already
 * returns {@code availableServices} alongside the customer list — no additional
 * backend endpoint is needed for this view.
 *
 * The "Create Invoice" action navigates to {@code /invoice/new}, which is where
 * organisations choose a service and customer and submit a billing request that
 * flows into the main billing system tracked on the Billing Overview page.
 */
@Component({
  selector: 'app-services-catalog',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, TranslocoDirective],
  templateUrl: './services-catalog.component.html',
  styleUrl: './services-catalog.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServicesCatalogComponent implements OnInit {
  readonly DataState = DataState;

  private readonly customerService = inject(CustomerService);
  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly router = inject(Router);

  readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<NewInvoiceDataInterface>>>({
    dataState: DataState.LOADING,
  });

  readonly user = computed<UserInterface | undefined>(
    () => this.pageState().appData?.data?.user,
  );
  readonly services = computed<ServicesInterface[]>(
    () => this.pageState().appData?.data?.availableServices ?? [],
  );

  /** Total catalog value — the sum of all service base prices. */
  readonly catalogTotal = computed(() =>
    this.services().reduce((sum, svc) => sum + (svc.price ?? 0), 0),
  );

  // A getter, not a field: authority flags must follow the CURRENT token. Evaluated once at
  // construction they latch whatever was true then — and on a page refresh that is usually an
  // expired token, i.e. "no authorities at all". UserService memoises the decode.
  get isAdmin(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE', 'DELETE:USER');
  }

  ngOnInit(): void {
    this.customerService
      .newInvoice$()
      .pipe(
        map((response) => ({ dataState: DataState.LOADED, appData: response })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.pageState.set(state));
  }
}
