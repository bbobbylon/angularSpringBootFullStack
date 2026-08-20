import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpEvent } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
// To re-enable the commented `tap(console.log)` calls below, add `tap` back here:
// import { catchError, tap } from 'rxjs/operators';
import { catchError } from 'rxjs/operators';
import {
  CustomerInvoiceUserInterface,
  CustomerListDataInterface,
  CustomerStateInterface,
  InvoiceListDataInterface,
  NewInvoiceDataInterface,
  StatsDataInterface,
} from '../interface/appstates.interface';
import { CustomHttpResponseInterface } from '../interface/customhttpresponse.interface';
import { CustomerInterface } from '../interface/customer.interface';
import { InvoiceInterface } from '../interface/invoice.interface';
import { BatchImportDataInterface } from '../interface/batch-import.interface';
import { environment } from '../../environments/environment';

/**
 * Central HTTP service for all customer and invoice API calls.
 *
 * Each method returns a typed Observable wrapping the server's standard
 * {@link CustomHttpResponseInterface} envelope. Errors are normalized by
 * {@link handleError} into a single error Observable so callers can handle
 * failures uniformly via {@code catchError}.
 */
@Injectable({
  providedIn: 'root',
})
export class CustomerService {
  private http = inject(HttpClient);
  private readonly server = environment.apiUrl;

  /**
   * Fetches aggregated dashboard statistics: total customers, invoices, and billed amount.
   *
   * Currently unused by the dashboard — stats are fetched alongside the customer list via
   * {@code customers$} and passed to {@link StatsComponent} via {@code @Input}.
   *
   * <p>An earlier note here proposed wiring this into {@link StatsComponent} so the panel could
   * refresh independently of the list. That was reconsidered and rejected; the reasoning lives on
   * {@code StatsComponent} itself, next to the code it governs. In short: the panel renders on the
   * one screen that must load customers anyway, so self-fetching would issue a second request for
   * figures already present in the first — and the two could then disagree, being two reads of a
   * moving database rather than one.
   *
   * <p>Kept because it is the correct call for any *other* consumer that wants the system-wide
   * totals without a page of customers attached.
   *
   * @returns Observable emitting a {@link StatsDataInterface} response containing the system-wide totals
   */
  stats$ = (): Observable<CustomHttpResponseInterface<StatsDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<StatsDataInterface>>(`${this.server}/customer/stats`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches a paginated page of customers.
   *
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @param sort - column to order by as `field,direction` (e.g. `customerName,desc`); omitted
   *               when absent rather than sent as an empty query param
   * @returns Observable emitting a {@link CustomerListDataInterface} response containing the page and stats
   */
  customers$ = (page = 0, size = 20, sort?: string): Observable<CustomHttpResponseInterface<CustomerListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<CustomerListDataInterface>>(
        `${this.server}/customer/list?page=${page}&size=${size}${sort ? `&sort=${encodeURIComponent(sort)}` : ''}`,
      )
      .pipe(/* tap(console.log), */ catchError(this.handleError));
  /**
   * Fetches a single customer's complete record by numeric ID.
   *
   * Calls GET /customers/:id and returns the customer and the authenticated user wrapped
   * in the standard {@link CustomHttpResponseInterface} envelope. Used by
   * {@link CustomerDetailsComponent} to populate the detail view when navigating to
   * {@code /customers/:id}.
   *
   * @param customerId - the numeric ID of the customer to retrieve
   * @returns Observable emitting a {@link CustomerStateInterface} response containing
   *          the customer record and the currently authenticated user
   */
  customerId$ = (customerId: number): Observable<CustomHttpResponseInterface<CustomerStateInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<CustomerStateInterface>>(`${this.server}/customer/get/${customerId}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Sends updated customer fields to the backend via PUT /customer/update/:id.
   *
   * The customer ID is taken from the {@code id} field on the customer object and
   * placed in the URL path — the backend extracts it as a {@code @PathVariable} and
   * ignores any ID in the request body. Requires {@code UPDATE:CUSTOMER} or
   * {@code UPDATE:USER} authority; Spring Security will return 403 otherwise.
   *
   * @param customer - the full customer object with updated field values; must include {@code id}
   * @returns Observable emitting a {@link CustomerStateInterface} response with the updated record
   */
  updateCustomer$ = (customer: CustomerInterface): Observable<CustomHttpResponseInterface<CustomerStateInterface>> =>
    this.http
      .put<CustomHttpResponseInterface<CustomerStateInterface>>(`${this.server}/customer/update/${customer.id}`, customer)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * POSTs a new customer record to the backend.
   *
   * @param customer - the customer data to create; all required fields must be populated
   * @returns Observable emitting a {@link CustomerListDataInterface} response containing
   *          the authenticated user and the newly created customer
   */
  newCustomer$ = (customer: CustomerInterface): Observable<CustomHttpResponseInterface<CustomerListDataInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<CustomerListDataInterface>>(`${this.server}/customer/create`, customer)
      .pipe(/* tap(console.log), */ catchError(this.handleError));
  /**
   * Fetches a paginated page of all invoices.
   *
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @param sort - column to order by as `field,direction` (e.g. `invoiceDate,desc`); omitted
   *               when absent rather than sent as an empty query param
   * @returns Observable emitting an {@link InvoiceListDataInterface} response containing the page and authenticated user
   */
  invoices$ = (page = 0, size = 20, sort?: string): Observable<CustomHttpResponseInterface<InvoiceListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<InvoiceListDataInterface>>(
        `${this.server}/customer/invoice/list?page=${page}&size=${size}${sort ? `&sort=${encodeURIComponent(sort)}` : ''}`,
      )
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches a single invoice and its associated customer by invoice ID.
   *
   * Calls {@code GET /customer/invoice/get/:id}. The backend resolves the invoice
   * once and returns the authenticated user, the invoice, and the linked customer
   * in a single response — no second round-trip is needed.
   *
   * @param invoiceId - the numeric ID of the invoice to retrieve
   * @returns Observable emitting a {@link CustomerInvoiceUserInterface} response containing
   *          the invoice, its customer, and the authenticated user
   */
  invoice$ = (invoiceId: number): Observable<CustomHttpResponseInterface<CustomerInvoiceUserInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>(`${this.server}/customer/invoice/get/${invoiceId}`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Fetches the customer list needed to populate the new-invoice form dropdown.
   *
   * @returns Observable emitting a {@link NewInvoiceDataInterface} response with the authenticated user
   *          and an unpaginated list of all customers
   */
  newInvoice$ = (): Observable<CustomHttpResponseInterface<NewInvoiceDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<NewInvoiceDataInterface>>(`${this.server}/customer/invoice/new`)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Creates a new invoice and links it to the specified customer.
   *
   * @param customerId - ID of the customer to attach the invoice to
   * @param invoice - invoice fields to persist (services, amount, invoiceDate, status)
   * @returns Observable emitting a {@link NewInvoiceDataInterface} response with the refreshed customer list
   */
  addInvoiceToCustomer$ = (customerId: number, invoice: InvoiceInterface): Observable<CustomHttpResponseInterface<NewInvoiceDataInterface>> =>
    this.http
      .post<CustomHttpResponseInterface<NewInvoiceDataInterface>>(`${this.server}/customer/invoice/addtocustomer/${customerId}`, invoice)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Applies edits to an existing invoice (ROADMAP §2 — "Edit invoices").
   *
   * Calls {@code PATCH /customer/invoice/update/:id}. Only the editable fields are read
   * server-side: the invoice number is an external reference already printed on documents the
   * customer holds, and reassigning ownership is a separate operation, so neither can be changed
   * through this call regardless of what the body contains.
   *
   * Until this existed, a wrong amount could only be addressed by raising a second invoice —
   * leaving the incorrect one in the customer's history and in every revenue total derived from it.
   *
   * @param invoiceId - the numeric ID of the invoice to edit
   * @param invoice - the edited fields (status, amount, totalAmount, invoiceDate, services)
   * @returns Observable emitting the envelope carrying the updated invoice
   */
  updateInvoice$ = (invoiceId: number, invoice: Partial<InvoiceInterface>): Observable<CustomHttpResponseInterface<CustomerInvoiceUserInterface>> =>
    this.http
      .patch<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>(`${this.server}/customer/invoice/update/${invoiceId}`, invoice)
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Emails a server-rendered PDF copy of an invoice to its owning customer (the "Email Invoice"
   * button on the invoice detail screen).
   *
   * <p>400s if the invoice is a draft (no customer attached yet) — the backend has no address to
   * send it to. The response carries no invoice/customer payload, only the standard envelope's
   * {@code user}, since nothing about the invoice itself changes.
   *
   * @param invoiceId the ID of the invoice to email
   * @returns Observable emitting the envelope once the send completes
   */
  emailInvoice$ = (invoiceId: number): Observable<CustomHttpResponseInterface<{ user: unknown }>> =>
    this.http
      .post<CustomHttpResponseInterface<{ user: unknown }>>(`${this.server}/customer/invoice/${invoiceId}/email`, {})
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Uploads a CSV/XLSX file for bulk customer creation via POST /customer/batch
   * (POST-SUBMISSION-UPGRADES.md #8).
   *
   * The file travels as {@code multipart/form-data} under the key {@code "file"} — the same
   * key {@code UserController#updateProfileImage} uses for its own upload, so both share one
   * convention. The response is 200 even when every row failed; callers read {@code
   * data.result.failed} to render the per-row report rather than treating a partial failure as
   * an HTTP error.
   *
   * @param file - the `.csv` or `.xlsx` file chosen by the user
   * @returns Observable emitting a {@link BatchImportDataInterface} response containing the
   *          authenticated user and the row-by-row import result
   */
  importCustomers$ = (file: File): Observable<CustomHttpResponseInterface<BatchImportDataInterface>> => {
    const formData = new FormData();
    formData.append('file', file);
    return this.http
      .post<CustomHttpResponseInterface<BatchImportDataInterface>>(`${this.server}/customer/batch`, formData)
      .pipe(catchError(this.handleError));
  };

  /**
   * Uploads a CSV/XLSX file for bulk invoice creation via POST /customer/invoice/batch
   * (POST-SUBMISSION-UPGRADES.md #8). Each row links to an existing customer by email —
   * see {@link importCustomers$} for the shared multipart convention and partial-success
   * response contract.
   *
   * @param file - the `.csv` or `.xlsx` file chosen by the user
   * @returns Observable emitting a {@link BatchImportDataInterface} response containing the
   *          authenticated user and the row-by-row import result
   */
  importInvoices$ = (file: File): Observable<CustomHttpResponseInterface<BatchImportDataInterface>> => {
    const formData = new FormData();
    formData.append('file', file);
    return this.http
      .post<CustomHttpResponseInterface<BatchImportDataInterface>>(`${this.server}/customer/invoice/batch`, formData)
      .pipe(catchError(this.handleError));
  };

  downloadCustomerReport$ = (): Observable<HttpEvent<Blob>> =>
    this.http
      .get<Blob>(`${this.server}/customer/download/report`, { reportProgress: true, observe: 'events', responseType: 'blob' as 'json' })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  downloadInvoiceReport$ = (): Observable<HttpEvent<Blob>> =>
    this.http
      .get<Blob>(`${this.server}/customer/invoice/download/report`, { reportProgress: true, observe: 'events', responseType: 'blob' as 'json' })
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Searches for customers whose name contains the given term via GET /customer/search.
   *
   * The search term is URI-encoded before being appended to the query string.
   * Results are paginated identically to {@link customers$}.
   *
   * @param name - the substring to match against customer names
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @param sort - column to order by as `field,direction`; omitted when absent
   * @returns Observable emitting a {@link CustomerListDataInterface} response containing the matching page
   */
  searchCustomers$ = (
    customerName: string,
    page = 0,
    size = 20,
    sort?: string,
  ): Observable<CustomHttpResponseInterface<CustomerListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<CustomerListDataInterface>>(
        `${this.server}/customer/search?name=${encodeURIComponent(customerName)}&page=${page}&size=${size}${sort ? `&sort=${encodeURIComponent(sort)}` : ''}`,
      )
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Searches for invoices whose invoice number or owning customer's name contains the given
   * term via GET /customer/invoice/search.
   *
   * Results are paginated identically to {@link invoices$}.
   *
   * @param term - the substring to match against invoice numbers and customer names
   * @param page - zero-based page index (defaults to 0)
   * @param size - number of records per page (defaults to 20)
   * @param sort - column to order by as `field,direction`; omitted when absent
   * @returns Observable emitting an {@link InvoiceListDataInterface} response containing the matching page
   */
  searchInvoices$ = (
    term: string,
    page = 0,
    size = 20,
    sort?: string,
  ): Observable<CustomHttpResponseInterface<InvoiceListDataInterface>> =>
    this.http
      .get<CustomHttpResponseInterface<InvoiceListDataInterface>>(
        `${this.server}/customer/invoice/search?term=${encodeURIComponent(term)}&page=${page}&size=${size}${sort ? `&sort=${encodeURIComponent(sort)}` : ''}`,
      )
      .pipe(/* tap(console.log), */ catchError(this.handleError));

  /**
   * Normalizes HTTP errors into a single Observable<never> so all callers
   * receive a consistent Error instance regardless of whether the failure
   * was a client-side network event or a structured server error response.
   *
   * @param error - the HttpErrorResponse from Angular's HttpClient
   * @returns Observable that immediately errors with a human-readable message
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage: string;

    if (error.error instanceof ErrorEvent) {
      errorMessage = `An error occurred: ${error.error.message}`;
    } else {
      if (error.error?.reason) {
        errorMessage = error.error.reason as string;
        // console.log(error.error);
        // console.log(errorMessage);
        // console.log(error);
      } else {
        errorMessage = `Server returned code: ${error.status}, error message is: ${error.message}`;
      }
    }
    console.error(errorMessage);

    return throwError(() => new Error(errorMessage));
  }
}
