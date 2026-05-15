import { Component, OnInit } from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { RouterModule } from '@angular/router';
import { Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { DataState } from '../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../interface/customhttpresponse.interface';

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
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  /**
   * Drives the invoice detail template — emits the invoice and its owning customer.
   *
   * Currently seeded with stub data. Will be replaced by a live stream from
   * {@code GET /customer/invoice/get/:id} once the invoice detail integration is complete.
   */
  invoiceState$: Observable<GlobalStateInterface<CustomHttpResponseInterface<any>>>;

  /**
   * Seeds {@link invoiceState$} with stub data so the template renders without a backend call.
   *
   * Will be replaced by an {@link ActivatedRoute} param stream + {@code switchMap} wired to
   * {@code GET /customer/invoice/get/:id} once the invoice detail backend integration is complete.
   */
  ngOnInit(): void {
    this.invoiceState$ = of({
      dataState: DataState.LOADED,
      appData: {
        statusCode: 200,
        message: '',
        timestamp: new Date(),
        status: 'OK',
        data: {
          user: { firstName: 'Test', lastName: 'User', roleName: 'ROLE_ADMIN', email: 'test@test.com' },
          invoice: {
            id: 1,
            invoiceNumber: 'STUB-0001',
            services: '1 Consulting 500',
            status: 'PENDING',
            total: 500,
            date: new Date(),
          },
          customer: {
            name: 'Stub Customer',
            address: '123 Main St',
            email: 'customer@example.com',
            phone: '555-0100',
          },
        },
      },
    });
  }

  /** Stub — will generate and download a PDF of the invoice. */
  exportAsPDF(): void {
    console.log('exportAsPDF stub');
  }
}
