import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { CustomerService } from '../../../service/customer.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { InvoiceListDataInterface } from '../../../interface/appstates.interface';
import { InvoiceInterface } from '../../../interface/invoice.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Self-contained invoice analytics panel embedded on the Invoices list page.
 *
 * Fetches a large invoice page independently of the parent's paginated table so
 * the analytics view never affects table loading state. Displays:
 * <ul>
 *   <li>Monthly revenue bar chart</li>
 *   <li>Invoice status donut</li>
 *   <li>Collection rate + avg value KPIs</li>
 * </ul>
 */
@Component({
  selector: 'app-invoice-trend',
  standalone: true,
  imports: [DecimalPipe, RouterLink, TranslocoDirective],
  templateUrl: './invoice-trend.component.html',
  styleUrl: './invoice-trend.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoiceTrendComponent implements OnInit {
  private readonly customerService = inject(CustomerService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>({
    dataState: DataState.LOADING,
  });
  readonly DataState = DataState;

  /**
   * Toggles whether this panel is expanded or collapsed.
   *
   * <p>**Expanded by default**, matching {@code CustomerTrendComponent}: analytics that require a
   * click to reveal are analytics nobody reads. The toggle remains for folding the panel away.
   */
  readonly expanded = signal(true);

  private readonly invoices = computed<InvoiceInterface[]>(
    () => this.state().appData?.data?.invoices?.content ?? [],
  );

  readonly invoiceTotal = computed(() => this.invoices().length);

  // ── KPIs ─────────────────────────────────────────────────────────────────

  readonly collectionRate = computed(() => {
    const list = this.invoices();
    if (!list.length) return 0;
    return (list.filter((i) => i.status?.toUpperCase() === 'PAID').length / list.length) * 100;
  });

  readonly avgInvoiceValue = computed(() => {
    const list = this.invoices();
    if (!list.length) return 0;
    return list.reduce((sum, i) => sum + (i.totalAmount ?? 0), 0) / list.length;
  });

  readonly totalRevenue = computed(() =>
    this.invoices().reduce((sum, i) => sum + (i.totalAmount ?? 0), 0),
  );

  // ── Monthly revenue bars ─────────────────────────────────────────────────

  readonly revenueBars = computed<{ label: string; revenue: number; count: number; barHeight: number }[]>(() => {
    const monthMap = new Map<string, { revenue: number; count: number }>();
    for (const inv of this.invoices()) {
      const d = new Date(inv.invoiceDate);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      const prev = monthMap.get(key) ?? { revenue: 0, count: 0 };
      monthMap.set(key, { revenue: prev.revenue + (inv.totalAmount ?? 0), count: prev.count + 1 });
    }
    const sorted = [...monthMap.entries()].sort((a, b) => a[0].localeCompare(b[0])).slice(-6);
    const maxRevenue = Math.max(...sorted.map(([, v]) => v.revenue), 1);
    return sorted.map(([key, val]) => {
      const [yr, mo] = key.split('-').map(Number);
      const label = new Date(yr, mo - 1).toLocaleString('default', { month: 'short', year: '2-digit' });
      return { label, revenue: val.revenue, count: val.count, barHeight: (val.revenue / maxRevenue) * 100 };
    });
  });

  // ── Status donut ─────────────────────────────────────────────────────────

  private readonly statusMeta: Record<string, { label: string; color: string }> = {
    PAID:      { label: 'Paid',      color: 'var(--ok)' },
    PENDING:   { label: 'Pending',   color: 'var(--accent-strong)' },
    OVERDUE:   { label: 'Overdue',   color: 'var(--danger)' },
    CANCELLED: { label: 'Cancelled', color: 'var(--text-faint)' },
  };

  readonly statusSegments = computed(() => {
    const counts = new Map<string, number>();
    for (const inv of this.invoices()) {
      const key = (inv.status ?? 'PENDING').toUpperCase();
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const total = [...counts.values()].reduce((s, n) => s + n, 0);
    if (!total) return [];
    const known = ['PAID', 'PENDING', 'OVERDUE', 'CANCELLED'].filter((k) => counts.has(k));
    const extra = [...counts.keys()].filter((k) => !known.includes(k));
    let cumulative = 0;
    return [...known, ...extra].map((key) => {
      const value = counts.get(key) ?? 0;
      const percent = (value / total) * 100;
      const seg = {
        label: this.statusMeta[key]?.label ?? key,
        value,
        percent,
        color: this.statusMeta[key]?.color ?? 'var(--text-faint)',
        dashArray: `${percent} ${100 - percent}`,
        dashOffset: 25 - cumulative,
      };
      cumulative += percent;
      return seg;
    });
  });

  toggleExpanded(): void {
    this.expanded.update((v) => !v);
  }

  ngOnInit(): void {
    this.customerService
      .invoices$(0, 500)
      .pipe(
        map((r) => ({ dataState: DataState.LOADED, appData: r })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((s) => this.state.set(s));
  }
}
