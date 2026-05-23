import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, ParamMap, Router, RouterModule } from '@angular/router';
import { BehaviorSubject, catchError, map, of, startWith, switchMap } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { UserInterface } from '../../../interface/user.interface';
import { CustomerInvoiceUserInterface } from '../../../interface/appstates.interface';
import { CustomerService } from '../../../service/customer.service';
import { jsPDF } from 'jspdf';

const INVOICE_ID = 'id';

@Component({
  selector: 'app-invoice-detail',
  imports: [CommonModule, RouterModule, NavbarComponent, DatePipe, DecimalPipe],
  templateUrl: './invoice-detail.component.html',
  styleUrl: './invoice-detail.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoiceDetailComponent implements OnInit {
  @Input() user: UserInterface;
  readonly DataState = DataState;
  invoiceState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>>({ dataState: DataState.LOADING });
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private dataSubject = new BehaviorSubject<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>(null);
  private readonly activatedRoute = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.activatedRoute.paramMap.pipe(
      switchMap((params: ParamMap) =>
        this.customerService.invoice$(+params.get(INVOICE_ID)).pipe(
          map((response) => {
            this.dataSubject.next(response);
            return { dataState: DataState.LOADED, appData: response } as GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>;
          }),
          startWith({ dataState: DataState.LOADING } as GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>),
          catchError((error: string) => of({ dataState: DataState.ERROR, error } as GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>)),
        ),
      ),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((state) => this.invoiceState.set(state));
  }

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
