import { Component, inject, OnInit } from '@angular/core';
import { AsyncPipe, NgClass } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, combineLatest, map, Observable, of, startWith, switchMap } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { CustomerListData } from '../../interface/appstates.interface';
import { CustomerService } from '../../service/customer.service';
import { ExtractArrayValuePipe } from '../../pipe/extract-array-value.pipe';

/**
 * All-customers list view with search and pagination.
 *
 * Fetches from GET /customer/list (no search term) or GET /customer/search
 * (with search term). Pagination and search are driven by BehaviorSubjects so
 * that any change automatically re-fetches via combineLatest + switchMap.
 */
@Component({
  selector: 'app-customers',
  imports: [AsyncPipe, NgClass, RouterModule, FormsModule, NavbarComponent, ExtractArrayValuePipe],
  templateUrl: './customers.component.html',
  styleUrl: './customers.component.css',
  standalone: true,
})
export class CustomersComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  readonly defaultImage = 'https://www.gravatar.com/avatar/?d=mp';
  customersState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListData>>>;

  private readonly customerService = inject(CustomerService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerListData>>(null);
  private currentPageSubject = new BehaviorSubject<number>(0);
  currentPage$ = this.currentPageSubject.asObservable();
  private currentSearchSubject = new BehaviorSubject<string>('');
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
  ngOnInit(): void {
    this.customersState$ = combineLatest([this.currentPageSubject, this.currentSearchSubject]).pipe(
      switchMap(([page, name]) =>
        (name ? this.customerService.searchCustomers$(name, page) : this.customerService.customers$(page)).pipe(
          map(response => {
            this.dataSubject.next(response);
            return { dataState: DataState.LOADED, appData: response };
          }),
          startWith({ dataState: DataState.LOADING }),
          catchError((error: string) => of({ dataState: DataState.ERROR, error })),
        ),
      ),
    );
  }

  searchCustomers(form: NgForm): void {
    this.currentSearchSubject.next(form.value.name ?? '');
    this.currentPageSubject.next(0);
  }

  searchCustomers1(searchForm: NgForm): void {
    this.currentPageSubject.next(0);
    this.customersState$ = this.customerService.searchCustomers$(searchForm.value.name).pipe(
      map(response => {
        //this.notification.onDefault(response.message);
        console.log(response);
        this.dataSubject.next(response);
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => {
        //this.notification.onError(error);
        return of({ dataState: DataState.ERROR, error });
      }),
    );
  }

  goToNextOrPreviousPage(direction: string, name?: string): void {
    this.currentSearchSubject.next(name ?? '');
    const step = direction === 'forward' ? 1 : -1;
    this.currentPageSubject.next(this.currentPageSubject.value + step);
  }

  goToPage(pageIndex: number, name?: string): void {
    this.currentSearchSubject.next(name ?? '');
    this.currentPageSubject.next(pageIndex);
  }
  protected getDefaultImageC(id: number): string {
    return this.localDefaultImages[id % this.localDefaultImages.length];
  }
}
