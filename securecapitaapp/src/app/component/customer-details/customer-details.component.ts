import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, inject, Input, OnInit } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router, RouterModule } from '@angular/router';
import { BehaviorSubject, catchError, map, Observable, of, startWith, switchMap } from 'rxjs';
import { DataState } from '../../enumeration/datastate.enum';
import { CustomerStateInterface } from '../../interface/appstates.interface';
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
  imports: [CommonModule, RouterModule, FormsModule, NavbarComponent, ExtractArrayValuePipe, NgOptimizedImage],
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
  /**
   * Drives the template — emits the full customer state (user + customer record) once loaded.
   *
   * Initialised with hardcoded stub data by {@link ngOnInit}. Will be replaced by the live
   * {@link ActivatedRoute} param stream from {@link aMethodThatDoesStuff} once the backend
   * endpoint is ready.
   */
  customerState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerStateInterface>>>;
  readonly defaultImage = 'https://www.gravatar.com/avatar/?d=mp';
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerStateInterface>>(null);
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
   * Pool of local asset images used as deterministic fallback avatars.
   *
   * The image for a given customer is selected by {@code id % localDefaultImages.length},
   * ensuring the same customer always gets the same placeholder across renders.
   */
  private readonly localDefaultImages = [
    'assets/images/ali-lokhandwala-KUr51Y4dOyo-unsplash.jpg',
    'assets/images/anders-jilden-cYrMQA7a3Wc-unsplash.jpg',
    'assets/images/braden-jarvis-prSogOoFmkw-unsplash.jpg',
    'assets/images/cody-weiss-hEMYwIE6GEY-unsplash.jpg',
    'assets/images/cristofer-maximilian-KfBkfDGddsY-unsplash.jpg',
    'assets/images/dan-freeman-wAn4RfmXtxU-unsplash.jpg',
    'assets/images/henning-witzel-ukvgqriuOgo-unsplash.jpg',
    'assets/images/ian-dooley-DuBNA1QMpPA-unsplash.jpg',
    'assets/images/ilnur-kalimullin-CB0Qrf8ib4I-unsplash.jpg',
    'assets/images/j-dg-dhsMqSP0o_s-unsplash.jpg',
    'assets/images/jaanus-jagomagi-AZJAIiIn6BY-unsplash.jpg',
    'assets/images/jack-anstey-XVoyX7l9ocY-unsplash.jpg',
    'assets/images/jack-ward-rknrvCrfS1k-unsplash.jpg',
    'assets/images/jake-houglum-dxdA7qd7Y9o-unsplash.jpg',
    'assets/images/jonatan-pie-3l3RwQdHRHg-unsplash.jpg',
    'assets/images/jordan-mcqueen-sDHdRL9ilW0-unsplash.jpg',
    'assets/images/joshua-sortino-xZqr8WtYEJ0-unsplash.jpg',
    'assets/images/karsten-winegeart-ZBUesmAQapY-unsplash.jpg',
    'assets/images/karsten-winegeart-fd1cQ3mmBTE-unsplash.jpg',
    'assets/images/lisha-riabinina-HqZwKWqqpOA-unsplash.jpg',
    'assets/images/luca-bravo-ii5JY_46xH0-unsplash.jpg',
    'assets/images/max-bender-VmX3vmBecFE-unsplash.jpg',
    'assets/images/nasa-Q1p7bh3SHj8-unsplash.jpg',
    'assets/images/premium_photo-1669315452561-618adeb79a8d.avif',
    'assets/images/premium_photo-1675198764382-94d5c093df30.avif',
    'assets/images/premium_photo-1675827055694-010aef2cf08f.avif',
    'assets/images/premium_photo-1694475478052-c5247f63402e.avif',
    'assets/images/premium_photo-1695735927074-20d374c21ecc.avif',
    'assets/images/premium_photo-1773875204303-961af9cb4f5b.avif',
    'assets/images/randy-fath-wwHDqnJsG2E-unsplash.jpg',
    'assets/images/raul-cacho-oses-QZiDYEMUHO4-unsplash.jpg',
    'assets/images/redd-francisco-Dl_Ya8eNRpk-unsplash.jpg',
    'assets/images/roberto-carlos-roman-don-8cG8KEKIowk-unsplash.jpg',
    'assets/images/roberto-nickson-Jat5D3lH_FA-unsplash.jpg',
    'assets/images/saad-khan-pxAuY-HesQM-unsplash.jpg',
    'assets/images/sebastien-gabriel-Y8CW-2Dhk6Q-unsplash.jpg',
    'assets/images/thomas-habr-6NmnrAJPq7M-unsplash.jpg',
    'assets/images/tiago-aleixo-tveboMtwZ9c-unsplash.jpg',
    'assets/images/timo-wagner-fT6-YkB0nfg-unsplash.jpg',
    'assets/images/urban-vintage-78A265wPiO4-unsplash.jpg',
    'assets/images/viktor-mogilat-Ap8Ga6uWBmE-unsplash.jpg',
    'assets/images/yu-ko-gcCw9aiZTzQ-unsplash.jpg',
  ];

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

  /**
   * Returns a deterministic local fallback image path for the given customer ID.
   *
   * Uses modulo arithmetic against {@code localDefaultImages} so that each customer
   * always receives the same placeholder regardless of render order or page.
   *
   * @param id - the customer's numeric ID used to index into the image pool
   * @returns a relative path to an asset image under {@code assets/images/}
   */
  protected getDefaultImageC(id: number): string {
    return this.localDefaultImages[id % this.localDefaultImages.length];
  }
}
