import { Component, inject, Input, OnInit } from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, ParamMap, Router, RouterModule } from '@angular/router';
import { BehaviorSubject, catchError, map, Observable, of, startWith, switchMap } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { UserInterface } from '../../interface/user.interface';
import { CustomerInvoiceUserInterface } from '../../interface/appstates.interface';
import { CustomerService } from '../../service/customer.service';
import { jsPDF } from 'jspdf';

// we can define this variable here if we want to clean up the code a bit and not have to use the 'this.' keyword
const INVOICE_ID = 'id';
/**
 * Single-invoice printable detail view.
 *
 * Stub implementation — real data will be wired to GET /customer/invoice/get/:id
 * once the full invoice detail backend integration is complete.
 */
@Component({
  selector: 'app-invoice-detail',
  imports: [CommonModule, RouterModule, NavbarComponent, DatePipe, DecimalPipe],
  templateUrl: './invoice-detail.component.html',
  styleUrl: './invoice-detail.component.css',
})
export class InvoiceDetailComponent implements OnInit {
  /**
   * The logged-in user, injected by the parent route component.
   * Used to display the user's name and avatar in the navbar.
   */
  @Input() user: UserInterface;
  /** Exposes the {@link DataState} enum to the template for switch-case rendering. */
  readonly DataState = DataState;
  /**
   * Drives the template — emits the full customer state (user and customer record) once loaded.
   *
   * Initialized with hardcoded stub data by {@link ngOnInit}. Will be replaced by the live
   * {@link ActivatedRoute} param stream from {@link ngOnInit } once the backend
   * endpoint is ready.
   */
  invoiceState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>>;
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>(null);
  private readonly activatedRoute = inject(ActivatedRoute);

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
    this.invoiceState$ = this.activatedRoute.paramMap.pipe(
      switchMap((params: ParamMap) => {
        // params.get(this.INVOICE_ID) extracts the :id segment from the URL, e.g., /customers/123 → "123"
        return this.customerService.invoice$(+params.get(INVOICE_ID)).pipe(
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
   * Captures the {@code #invoice} DOM section and downloads it as a PDF file.
   * <p>
   * The filename is derived from the invoice number, so downloaded files are
   * identifiable without opening them. Guards against missing data or a missing
   * DOM element to avoid silent failures.
   */
  exportAsPDF(): void {
    const invoice = this.dataSubject.value?.data?.invoice;
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
