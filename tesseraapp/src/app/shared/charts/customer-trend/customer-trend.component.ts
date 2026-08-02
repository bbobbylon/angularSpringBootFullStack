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
import { CustomerListDataInterface } from '../../../interface/appstates.interface';
import { CustomerInterface } from '../../../interface/customer.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Self-contained customer analytics panel embedded on the Customers list page.
 *
 * Fetches its own data (a large customer page for trend analysis) independently
 * of the parent's pagination fetch so the analytics panel never interferes with
 * the table's loading state. Shows:
 * <ul>
 *   <li>Monthly new-customer bar chart (acquisition trend)</li>
 *   <li>Customer status donut</li>
 *   <li>Type-split progress bars</li>
 * </ul>
 */
@Component({
  selector: 'app-customer-trend',
  standalone: true,
  imports: [DecimalPipe, RouterLink, TranslocoDirective],
  templateUrl: './customer-trend.component.html',
  styleUrl: './customer-trend.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerTrendComponent implements OnInit {
  private readonly customerService = inject(CustomerService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({
    dataState: DataState.LOADING,
  });
  readonly DataState = DataState;

  private readonly customers = computed<CustomerInterface[]>(
    () => this.state().appData?.data?.page?.content ?? [],
  );

  /**
   * Toggles whether this panel is expanded or collapsed.
   *
   * <p>**Expanded by default.** It previously started collapsed, which meant the analytics above
   * the customer list were invisible until the user thought to click a chevron — so the most
   * informative part of the page was, in practice, never seen. The toggle is kept so the panel can
   * still be folded away when the table itself is the focus.
   */
  readonly expanded = signal(true);

  // ── Monthly acquisition bars ─────────────────────────────────────────────

  readonly acquisitionBars = computed<{ label: string; count: number; barHeight: number }[]>(() => {
    const monthMap = new Map<string, number>();
    for (const c of this.customers()) {
      const d = new Date(c.createdAt);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      monthMap.set(key, (monthMap.get(key) ?? 0) + 1);
    }
    const sorted = [...monthMap.entries()].sort((a, b) => a[0].localeCompare(b[0])).slice(-6);
    const maxCount = Math.max(...sorted.map(([, v]) => v), 1);
    return sorted.map(([key, count]) => {
      const [yr, mo] = key.split('-').map(Number);
      const label = new Date(yr, mo - 1).toLocaleString('default', { month: 'short', year: '2-digit' });
      return { label, count, barHeight: (count / maxCount) * 100 };
    });
  });

  // ── Status donut ─────────────────────────────────────────────────────────

  private readonly statusMeta: Record<string, { label: string; color: string }> = {
    ACTIVE:   { label: 'Active',   color: 'var(--ok)' },
    PENDING:  { label: 'Pending',  color: 'var(--accent-strong)' },
    INACTIVE: { label: 'Inactive', color: 'var(--info)' },
    BANNED:   { label: 'Banned',   color: 'var(--danger)' },
  };

  readonly statusSegments = computed(() => {
    const counts = new Map<string, number>();
    for (const c of this.customers()) {
      const key = (c.status ?? 'UNKNOWN').toUpperCase();
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const total = [...counts.values()].reduce((s, n) => s + n, 0);
    if (!total) return [];
    const known = ['ACTIVE', 'PENDING', 'INACTIVE', 'BANNED'].filter((k) => counts.has(k));
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

  readonly customerTotal = computed(() => this.customers().length);

  // ── Type split ───────────────────────────────────────────────────────────

  readonly typeSplit = computed<{ label: string; count: number; pct: number }[]>(() => {
    const typeMap = new Map<string, number>();
    for (const c of this.customers()) {
      const key = c.type ?? 'UNKNOWN';
      typeMap.set(key, (typeMap.get(key) ?? 0) + 1);
    }
    const total = [...typeMap.values()].reduce((s, n) => s + n, 0) || 1;
    return [...typeMap.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([label, count]) => ({ label, count, pct: (count / total) * 100 }));
  });

  toggleExpanded(): void {
    this.expanded.update((v) => !v);
  }

  ngOnInit(): void {
    this.customerService
      .customers$(0, 500)
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
