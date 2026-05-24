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

@Component({
  selector: 'app-invoice-detail',
  imports: [NgClass, DatePipe, DecimalPipe, RouterModule, NavbarComponent],
  templateUrl: './invoice-detail.component.html',
  styleUrl: './invoice-detail.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoiceDetailComponent implements OnInit {
  @Input() user: UserInterface;
  /** Bound automatically by the router via {@code withComponentInputBinding()} — matches the {@code :id} segment in {@code invoice/:id/:invoiceNumber}. */
  @Input() id: number;
  readonly DataState = DataState;
  invoiceState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>>({ dataState: DataState.LOADING });
  protected readonly router = inject(Router);
  protected readonly customerService = inject(CustomerService);
  private data = signal<CustomHttpResponseInterface<CustomerInvoiceUserInterface>>(null);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

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
