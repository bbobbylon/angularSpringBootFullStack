import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { saveAs } from 'file-saver';
import { CustomerService } from '../../service/customer.service';
import { NotificationsService } from '../../service/notifications-service';
import { BatchImportResultInterface } from '../../interface/batch-import.interface';

/**
 * The "Import" control that sits beside Customers' and Invoices' "Export" button
 * (POST-SUBMISSION-UPGRADES.md #8, FUTURE-ENHANCEMENTS.md §3.3 "P2-2 — Batch upload").
 *
 * <h3>Why one component for two different imports</h3>
 * Customers and invoices import from different column schemas against different endpoints, but
 * the surrounding interaction — pick a file, upload it, read a per-row pass/fail report — is
 * identical. {@link kind} selects which endpoint and which help text to show; everything else
 * (the panel, the file picker, the result table) is written once. This mirrors why {@code
 * PageSizeSelectComponent} exists rather than six copies of a `<select>`.
 *
 * <h3>Partial success, not all-or-nothing</h3>
 * The backend returns 200 even when every row failed — a batch upload is a report, not a
 * pass/fail gate. {@link result} always reflects the last response; the template renders the
 * imported count plus a table of every rejected row with its reason, letting the caller fix
 * just the bad rows and re-upload rather than guessing what a generic "import failed" meant.
 *
 * <h3>Not a security boundary</h3>
 * The Import button is always rendered; the request itself is gated server-side (the same
 * {@code UPDATE:CUSTOMER}/{@code UPDATE:USER} authority every other write here requires) — a
 * caller without it gets a 403 from the upload attempt, surfaced as an error toast, exactly like
 * every other write in the app that has no client-side authority check of its own.
 */
@Component({
  selector: 'app-batch-import',
  standalone: true,
  imports: [TranslocoDirective],
  templateUrl: './batch-import.component.html',
  styleUrl: './batch-import.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BatchImportComponent {
  /** Which schema/endpoint this instance imports — selects help text and the service call. */
  readonly kind = input.required<'customers' | 'invoices'>();

  /**
   * Fires once after a response is received that actually imported at least one row, so the
   * host list can refresh. Not fired for a response where every row failed — there is nothing
   * new to show, and re-fetching would just flash the same page.
   */
  readonly imported = output<void>();

  private readonly customerService = inject(CustomerService);
  private readonly notification = inject(NotificationsService);
  private readonly transloco = inject(TranslocoService);

  /** Whether the import panel is expanded. Collapsed by default so the toolbar stays compact. */
  protected readonly open = signal(false);

  /** The file chosen in the panel's file input, or {@code null} before one is picked. */
  protected readonly selectedFile = signal<File | null>(null);

  /** True while the upload request is in flight — disables the Upload button and file input. */
  protected readonly uploading = signal(false);

  /** The most recent import result, or {@code undefined} before any upload completes. */
  protected readonly result = signal<BatchImportResultInterface | undefined>(undefined);

  /** Opens the panel, or closes it and clears any prior file/result if it is already open. */
  protected toggle(): void {
    if (this.open()) {
      this.reset();
    } else {
      this.open.set(true);
    }
  }

  /** Records the file chosen via the native file input. */
  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files?.[0] ?? null);
  }

  /**
   * Downloads a blank, header-only XLSX shaped exactly like what {@link upload} sends
   * (FUTURE-ENHANCEMENTS.md §3.3, "Downloadable batch-upload templates") — removes the need to
   * reverse-engineer the expected columns from the help text or from a failed first attempt.
   * Selects the endpoint the same way {@link upload} selects it: by {@link kind}.
   */
  protected downloadTemplate(): void {
    const request$ = this.kind() === 'customers' ? this.customerService.downloadCustomerBatchTemplate$() : this.customerService.downloadInvoiceBatchTemplate$();
    const filename = this.kind() === 'customers' ? 'customer_batch_template.xlsx' : 'invoice_batch_template.xlsx';
    request$.subscribe({
      next: (blob) => saveAs(blob, filename),
      error: (error: string) => this.notification.onError(error),
    });
  }

  /**
   * Uploads {@link selectedFile} through the endpoint {@link kind} selects, then renders the
   * per-row result. Clears the chosen file afterward either way, so a second upload always
   * starts from an explicit new choice rather than silently resubmitting the same file.
   */
  protected upload(): void {
    const file = this.selectedFile();
    if (!file || this.uploading()) return;

    this.uploading.set(true);
    const request$ = this.kind() === 'customers' ? this.customerService.importCustomers$(file) : this.customerService.importInvoices$(file);
    request$.subscribe({
      next: (response) => {
        this.uploading.set(false);
        this.selectedFile.set(null);
        const importResult = response.data?.result;
        this.result.set(importResult);
        if (!importResult) return;
        const total = importResult.imported + importResult.failed.length;
        const summary = this.transloco.translate('batchImport.resultSummary', { imported: importResult.imported, total });
        if (importResult.imported > 0) {
          this.notification.onSuccess(summary);
          this.imported.emit();
        } else {
          this.notification.onError(summary);
        }
      },
      error: (error: string) => {
        this.uploading.set(false);
        this.notification.onError(error);
      },
    });
  }

  /** Collapses the panel and clears the chosen file and any prior result. */
  protected reset(): void {
    this.open.set(false);
    this.selectedFile.set(null);
    this.result.set(undefined);
  }
}
