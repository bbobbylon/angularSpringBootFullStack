import { Component, inject, Input, OnInit, signal } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BehaviorSubject, map, Observable, of, startWith } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';
import { UserInterface } from '../../interface/user.interface';
import { CustomerListData } from '../../interface/appstates.interface';
import { CustomerService } from '../../service/customer.service';
import { catchError } from 'rxjs/operators';

/**
 * New customer creation form.
 *
 * Stub implementation — real submission will POST to /customer/create
 * once the full customer creation backend integration is complete.
 */
@Component({
  selector: 'app-new-customer',
  imports: [AsyncPipe, RouterModule, FormsModule, NavbarComponent],
  templateUrl: './new-customer.component.html',
  standalone: true,
  styleUrls: ['./new-customer.component.css'],
})
export class NewCustomerComponent implements OnInit {
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  newCustomerState1$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;

  newCustomerState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<CustomerListData>>>;
  @Input() user: UserInterface;
  readonly title = signal('securecapitaapp');
  protected readonly permissions = signal<string[]>([]);
  protected readonly customerService = inject(CustomerService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerListData>>(null);
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  protected isLoading$ = this.isLoadingSubject.asObservable();

  //TODO change functinoality to just get the user data instead of calling the customerService and fetching all customers, we just need the user data to prefill the form and then submit the form to create a new customer
  ngOnInit(): void {
    this.newCustomerState$ = this.customerService.customers$().pipe(
      map(response => {
        console.log('Fetched New customer data:', response);
        this.dataSubject.next(response);
        return { dataState: DataState.LOADED, appData: response };
      }),
      startWith({ dataState: DataState.LOADING }),
      catchError((error: string) => of({ dataState: DataState.ERROR, error })),
    );
  }
  createNewCustomer(newCustomerForm: NgForm): void {
    this.isLoadingSubject.next(true);
    this.newCustomerState$ = this.customerService.newCustomer$(newCustomerForm.value).pipe(
      map(response => {
        console.log('Fetched customer data:', response);
        newCustomerForm.reset({ type: "INDIVIDUAL", status: "ACTIVE" });
        this.isLoadingSubject.next(false);
        return { dataState: DataState.LOADED, appData: this.dataSubject.value  };
      }),
      startWith({ dataState: DataState.LOADED, appData: this.dataSubject.value }),
      catchError((error: string) => of({ dataState: DataState.LOADED, error })),
    );
  }

  /** Stub — will POST the new customer to /customer/create. */
  createCustomer(form: NgForm): void {
    console.log('createCustomer stub:', form.value);
  }
}
