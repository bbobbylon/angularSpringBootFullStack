import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { ServicesCatalogService, ServicesListDataInterface } from '../../../service/services-catalog.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { ServicesInterface } from '../../../interface/services.interface';
import { UserInterface } from '../../../interface/user.interface';

/**
 * Administrative management of the services catalog — {@code /services/manage}
 * (ROADMAP §2 — "Create / manage services").
 *
 * <h3>Why this is a separate page from the catalog</h3>
 * The browse page at {@code /services} reads {@code GET /customer/invoice/new}, which returns
 * <em>active</em> services only — correctly, since its job is to show what can be put on an
 * invoice. An administrator needs the opposite: the full catalog including retired entries, so
 * they can see why something has disappeared from the invoice form and reinstate it. Bolting an
 * edit mode onto the browse page would have meant either showing users retired services or showing
 * administrators an incomplete catalog. Two audiences, two questions, two screens.
 *
 * <h3>Retire, never delete</h3>
 * There is deliberately no delete action. Invoices copy a service's name and price into their own
 * line items when raised, so removing the catalog row would not corrupt historical invoices — but
 * it would erase the catalog's own history and turn "bring that offering back" into a retyping
 * exercise. Retirement is reversible; deletion is not, and nothing here needs to be irreversible.
 *
 * <h3>State handling</h3>
 * Every mutation refreshes from the server rather than patching the local list. The catalog is a
 * short list read once, so the extra round trip is invisible, and it removes a whole category of
 * bug where the screen and the database quietly disagree after a partial failure.
 */
@Component({
  selector: 'app-services-admin',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, FormsModule],
  templateUrl: './services-admin.component.html',
  styleUrl: './services-admin.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServicesAdminComponent implements OnInit {
  readonly DataState = DataState;

  private readonly catalog = inject(ServicesCatalogService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly pageState = signal<GlobalStateInterface<CustomHttpResponseInterface<ServicesListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  protected readonly user = computed<UserInterface | undefined>(() => this.pageState().appData?.data?.user);

  protected readonly services = computed<ServicesInterface[]>(() => this.pageState().appData?.data?.services ?? []);

  protected readonly activeServices = computed(() => this.services().filter((service) => service.active !== false));
  protected readonly retiredServices = computed(() => this.services().filter((service) => service.active === false));

  /** Total value of the offerings currently available — retired entries excluded, since they cannot be sold. */
  protected readonly catalogValue = computed(() =>
    this.activeServices().reduce((sum, service) => sum + (service.price ?? 0), 0),
  );

  /** The id of the row being edited inline, or null when nothing is open. */
  protected readonly editingId = signal<number | null>(null);

  /** Whether the "add a service" form is open. */
  protected readonly isCreating = signal(false);

  /** Blocks duplicate submissions while a mutation is in flight. */
  protected readonly isSaving = signal(false);

  ngOnInit(): void {
    this.load();
  }

  /** Opens the create form and closes any open edit, so only one form is ever live. */
  protected startCreate(): void {
    this.editingId.set(null);
    this.isCreating.set(true);
  }

  /** Opens an inline edit for one row, closing the create form. */
  protected startEdit(service: ServicesInterface): void {
    this.isCreating.set(false);
    this.editingId.set(service.id);
  }

  /** Abandons whichever form is open without saving. */
  protected cancel(): void {
    this.isCreating.set(false);
    this.editingId.set(null);
  }

  /**
   * Creates a catalog entry from the add form.
   *
   * @param form - the submitted form carrying name, description and price
   */
  protected create(form: NgForm): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.catalog
      .create$({
        name: form.value.name,
        description: form.value.description,
        price: Number(form.value.price) || 0,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.isCreating.set(false);
          form.resetForm();
          this.notification.onSuccess(response.message ?? 'Service added to the catalog.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Saves an inline edit.
   *
   * @param serviceId - the entry being edited
   * @param form - the submitted form carrying name, description and price
   */
  protected save(serviceId: number, form: NgForm): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.catalog
      .update$(serviceId, {
        name: form.value.name,
        description: form.value.description,
        price: Number(form.value.price) || 0,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.editingId.set(null);
          this.notification.onSuccess(response.message ?? 'Service updated.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Retires or reinstates an entry.
   *
   * <p>No confirmation prompt: retirement is fully reversible with the button that replaces it, so
   * a dialog would be asking the user to confirm something they can undo in one click. Prompts
   * belong on actions that cannot be taken back.
   *
   * @param service - the entry to toggle
   */
  protected toggleActive(service: ServicesInterface): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.catalog
      .setActive$(service.id, service.active === false)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.notification.onSuccess(response.message ?? 'Service updated.');
          this.load();
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /** Fetches the full catalog and folds it into {@link pageState}. */
  private load(): void {
    this.catalog
      .list$()
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
