import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe, NgClass } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { UserInterface } from '../../../interface/user.interface';
import { CustomerInvoiceUserInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { jsPDF } from 'jspdf';
import { NotificationsService } from '../../../service/notifications-service';
import { FormsModule, NgForm } from '@angular/forms';
import { UserService } from '../../../service/user.service';
import { RequiresAuthorityDirective } from '../../../directive/has-authority.directive';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Single-invoice view — {@code /invoice/:id/:invoiceNumber}.
 *
 * <p>Doubles as the printable document: the {@code #invoice} element is deliberately styled as
 * white paper and is what {@link InvoiceDetailComponent#exportAsPDF} hands to jsPDF. Anything
 * added to this screen that is <em>not</em> part of the document — the edit panel, the action bar
 * — must live outside that element, or it lands in the customer's PDF.
 *
 * <h3>Editing (ROADMAP §2)</h3>
 * Invoices were create-only, so correcting a wrong amount meant raising a second invoice and
 * leaving the first in the customer's history and in every revenue total derived from it. The edit
 * panel changes status, dates and amounts through {@code PATCH /customer/invoice/update/:id}. The
 * invoice number is not editable — it is an external reference already printed on whatever the
 * customer received, and changing it would break the correspondence between their copy and ours.
 */
@Component({
  selector: 'app-invoice-detail',
  imports: [NgClass, DatePipe, DecimalPipe, RouterModule, NavbarComponent, FormsModule, RequiresAuthorityDirective, TranslocoDirective],
  templateUrl: './invoice-detail.component.html',
  styleUrl: './invoice-detail.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoiceDetailComponent implements OnInit {
  @Input() user: UserInterface | undefined;
  /** Bound automatically by the router via {@code withComponentInputBinding()} — matches the {@code :id} segment in {@code invoice/:id/:invoiceNumber}. */
  @Input() id!: number;
  readonly DataState = DataState;
  invoiceState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>>({ dataState: DataState.LOADING });
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private data = signal<CustomHttpResponseInterface<CustomerInvoiceUserInterface> | undefined>(undefined);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  private readonly userService = inject(UserService);

  /**
   * Whether this account may edit invoices.
   *
   * <p>The same authority pair the backend enforces on {@code PATCH /customer/invoice/update/:id}
   * ({@code @PreAuthorize("hasAnyAuthority('UPDATE:CUSTOMER','UPDATE:USER')")}). Gating on the
   * authorities rather than a role name means {@code ROLE_MODERATOR} — which may edit customers
   * but is not staff — keeps the ability it was granted.
   */
  // A getter, not a field: authority flags must follow the CURRENT token. Evaluated once at
  // construction they latch whatever was true then — and on a page refresh that is usually an
  // expired token, i.e. "no authorities at all". UserService memoises the decode.
  protected get canEditInvoice(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:CUSTOMER', 'UPDATE:USER');
  }

  /** Whether the edit panel is open. Closed by default: this page is primarily a document. */
  protected readonly isEditing = signal(false);

  /** Blocks duplicate submits while a save is in flight. */
  protected readonly isSaving = signal(false);

  /** Blocks duplicate submits while an "Email Invoice" send is in flight. */
  protected readonly isEmailing = signal(false);

  ngOnInit(): void {
    this.customerService.invoice$(this.id).pipe(
      map((response) => {
        this.data.set(response);
        return { dataState: DataState.LOADED, appData: response } as GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>;
      }),
      startWith({ dataState: DataState.LOADING } as GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>),
      catchError((error: string) => {
        this.notification.onError(error);
        return of({ dataState: DataState.ERROR, error } as GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>);
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((state) => this.invoiceState.set(state));
  }

  /** Opens or closes the edit panel. */
  protected toggleEdit(): void {
    this.isEditing.update((open) => !open);
  }

  /**
   * Saves the edited invoice and refreshes the document with the server's version.
   *
   * <p>The response is written back into state rather than the submitted form values, so what the
   * user sees afterwards is what was actually persisted. The two can differ — the service ignores
   * fields it does not consider editable — and showing the optimistic version would tell the user
   * a change had been saved that had not.
   *
   * @param form - the submitted edit form (status, invoiceDate, amount, totalAmount)
   */
  protected saveInvoice(form: NgForm): void {
    if (this.isSaving()) return;
    this.isSaving.set(true);

    this.customerService
      .updateInvoice$(this.id, {
        status: form.value.status,
        invoiceDate: form.value.invoiceDate,
        amount: Number(form.value.amount) || 0,
        totalAmount: Number(form.value.totalAmount) || 0,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isSaving.set(false);
          this.isEditing.set(false);
          // The update response carries no `customer` key, so the existing one is preserved —
          // otherwise saving an invoice would blank the "bill to" block on the document.
          const merged = {
            ...response,
            data: { ...response.data!, customer: this.data()?.data?.customer },
          } as CustomHttpResponseInterface<CustomerInvoiceUserInterface>;
          this.data.set(merged);
          this.invoiceState.set({ dataState: DataState.LOADED, appData: merged });
          this.notification.onSuccess(response.message ?? 'Invoice updated.');
        },
        error: (error: Error) => {
          this.isSaving.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  /**
   * Emails this invoice's PDF to its customer via the backend's server-side render
   * ({@code POST /customer/invoice/:id/email}) — distinct from {@link exportAsPDF}, which renders
   * client-side via jsPDF and never leaves the browser. 400s (surfaced as an error toast) if the
   * invoice is still a draft with no customer attached.
   */
  protected emailInvoice(): void {
    if (this.isEmailing()) return;
    this.isEmailing.set(true);

    this.customerService
      .emailInvoice$(this.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isEmailing.set(false);
          this.notification.onSuccess(response.message ?? 'Invoice emailed.');
        },
        error: (error: Error) => {
          this.isEmailing.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  exportAsPDF(): void {
    const invoice = this.data()?.data?.invoice;
    if (!invoice) return;

    const element = document.getElementById('invoice');
    if (!element) return;

    const pdf = new jsPDF();
    const filename = `invoice-${invoice.invoiceNumber}.pdf`;
    pdf.html(element, {
      margin: 5,
      windowWidth: 1000,
      width: 200,
      callback: (doc) => doc.save(filename),
    });
  }
}
