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
import { DatePipe, DecimalPipe, NgClass } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { AnalyticsService } from '../../../service/analytics.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import { InvoiceListDataInterface, StatsDataInterface } from '../../../interface/appstates.interface';
import { InvoiceInterface } from '../../../interface/invoice.interface';
import { UserInterface } from '../../../interface/user.interface';

/**
 * One segment of the invoice-status donut ring.
 *
 * Uses the same {@code r=15.915} SVG trick as {@link InsightsComponent}: the
 * circumference ≈ 100, so a segment's percent maps directly onto
 * {@code stroke-dasharray} — no per-frame trigonometry in the template.
 */
interface StatusSegment {
  label: string;
  value: number;
  percent: number;
  color: string;
  dashArray: string;
  dashOffset: number;
}

/** One column in the monthly-revenue bar chart. */
interface MonthBar {
  /** Short label, e.g. "Jun '25". */
  label: string;
  revenue: number;
  count: number;
  /** 0–100: height relative to the tallest bar so the chart fills its container. */
  barHeight: number;
}

/** One row in the service-revenue breakdown table. */
interface ServiceRow {
  name: string;
  total: number;
  count: number;
  /** 0–100: width relative to the highest-revenue service. */
  barPercent: number;
}

/**
 * Billing overview page — analytics hub for admins.
 *
 * Visible only to users with UPDATE:USER / UPDATE:ROLE (org admins) or
 * DELETE:USER (super admins) — enforced by {@code adminGuard} in the route table AND
 * genuinely enforced server-side: this page's data comes from the admin-gated
 * {@code /admin/analytics/**} surface ({@link AnalyticsService}), which SecurityConfig's
 * {@code /admin/**} matcher plus a method-level {@code @PreAuthorize} lock to the same
 * UPDATE:USER / UPDATE:ROLE authorities. A plain ROLE_USER who bypasses the route guard
 * still receives a 403 from the API — the route gate and the API gate are in lockstep.
 * The presentation layer alone differs by role: super admins see a scope badge of
 * "All Organizations"; org admins see "Your Organization" (org-scoped backend filtering
 * of the aggregates is a future addition; both currently receive system-wide data).
 *
 * Data comes from two admin-only endpoints:
 * {@code GET /admin/analytics/summary} for KPI totals and
 * {@code GET /admin/analytics/invoices?page=0&size=200} for the detailed breakdown.
 * All visual derivations (monthly bars, status donut, service rows) are computed
 * signals that recalculate automatically when either source updates.
 */
@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, DatePipe, NgClass],
  templateUrl: './billing.component.html',
  styleUrl: './billing.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BillingComponent implements OnInit {
  readonly DataState = DataState;

  private readonly analytics = inject(AnalyticsService);
  private readonly userService = inject(UserService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly statsState = signal<GlobalStateInterface<CustomHttpResponseInterface<StatsDataInterface>>>({
    dataState: DataState.LOADING,
  });
  readonly invoicesState = signal<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  readonly user = computed<UserInterface | undefined>(() => this.statsState().appData?.data?.user);
  readonly stats = computed(() => this.statsState().appData?.data?.stats);
  readonly invoices = computed<InvoiceInterface[]>(
    () => this.invoicesState().appData?.data?.invoices?.content ?? [],
  );

  /**
   * Scope label driven by the user's highest authority.
   * DELETE:USER is the super-admin tier; UPDATE:USER / UPDATE:ROLE is org-admin.
   */
  readonly isSuperAdmin = this.userService.hasAnyAuthority('DELETE:USER');
  readonly scopeLabel = this.isSuperAdmin ? 'All Organizations' : 'Your Organization';

  // ── KPIs ──────────────────────────────────────────────────────────────────

  readonly totalBilled = computed(() => this.stats()?.totalBilled ?? 0);
  readonly totalInvoices = computed(() => this.stats()?.totalInvoices ?? 0);
  readonly totalCustomers = computed(() => this.stats()?.totalCustomers ?? 0);

  readonly avgInvoiceValue = computed(() => {
    const s = this.stats();
    return s?.totalInvoices ? s.totalBilled / s.totalInvoices : 0;
  });

  readonly collectionRate = computed(() => {
    const list = this.invoices();
    if (!list.length) return 0;
    const paid = list.filter((inv) => inv.status?.toUpperCase() === 'PAID').length;
    return (paid / list.length) * 100;
  });

  // ── Invoice status donut ──────────────────────────────────────────────────

  private readonly statusMeta: Record<string, { label: string; color: string }> = {
    PAID: { label: 'Paid', color: 'var(--ok)' },
    PENDING: { label: 'Pending', color: 'var(--accent-strong)' },
    OVERDUE: { label: 'Overdue', color: 'var(--danger)' },
    CANCELLED: { label: 'Cancelled', color: 'var(--text-faint)' },
  };

  readonly statusSegments = computed<StatusSegment[]>(() => {
    const list = this.invoices();
    if (!list.length) return [];

    const counts = new Map<string, number>();
    for (const inv of list) {
      const key = (inv.status ?? 'PENDING').toUpperCase();
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const total = [...counts.values()].reduce((sum, n) => sum + n, 0);

    const known = ['PAID', 'PENDING', 'OVERDUE', 'CANCELLED'].filter((k) => counts.has(k));
    const extra = [...counts.keys()].filter((k) => !known.includes(k));
    let cumulative = 0;

    return [...known, ...extra].map((key) => {
      const value = counts.get(key) ?? 0;
      const percent = (value / total) * 100;
      const seg: StatusSegment = {
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

  readonly statusTotal = computed(() => this.invoices().length);

  // ── Monthly revenue bar chart ─────────────────────────────────────────────

  readonly monthlyRevenue = computed<MonthBar[]>(() => {
    const list = this.invoices();
    if (!list.length) return [];

    const monthMap = new Map<string, { revenue: number; count: number }>();
    for (const inv of list) {
      const d = new Date(inv.invoiceDate);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      const prev = monthMap.get(key) ?? { revenue: 0, count: 0 };
      monthMap.set(key, {
        revenue: prev.revenue + (inv.totalAmount ?? 0),
        count: prev.count + 1,
      });
    }

    const sorted = [...monthMap.entries()].sort((a, b) => a[0].localeCompare(b[0])).slice(-6);
    const maxRevenue = Math.max(...sorted.map(([, v]) => v.revenue), 1);

    return sorted.map(([key, val]) => {
      const [yr, mo] = key.split('-').map(Number);
      const label = new Date(yr, mo - 1).toLocaleString('default', { month: 'short', year: '2-digit' });
      return {
        label,
        revenue: val.revenue,
        count: val.count,
        barHeight: (val.revenue / maxRevenue) * 100,
      };
    });
  });

  // ── Service revenue breakdown ─────────────────────────────────────────────

  readonly serviceRevenue = computed<ServiceRow[]>(() => {
    const list = this.invoices();
    if (!list.length) return [];

    const svcMap = new Map<string, { total: number; count: number }>();
    for (const inv of list) {
      for (const svc of inv.services ?? []) {
        const prev = svcMap.get(svc.name) ?? { total: 0, count: 0 };
        svcMap.set(svc.name, { total: prev.total + (svc.price ?? 0), count: prev.count + 1 });
      }
    }

    const sorted = [...svcMap.entries()].sort((a, b) => b[1].total - a[1].total).slice(0, 8);
    const maxTotal = Math.max(...sorted.map(([, v]) => v.total), 1);

    return sorted.map(([name, val]) => ({
      name,
      total: val.total,
      count: val.count,
      barPercent: (val.total / maxTotal) * 100,
    }));
  });

  // ── Recent invoices ───────────────────────────────────────────────────────

  readonly recentInvoices = computed<InvoiceInterface[]>(() =>
    [...this.invoices()]
      .sort((a, b) => new Date(b.invoiceDate).getTime() - new Date(a.invoiceDate).getTime())
      .slice(0, 6),
  );

  /** Returns a comma-separated service name string for an invoice row. Safe in templates — no arrow functions. */
  serviceNames(inv: InvoiceInterface): string {
    return (inv.services ?? []).map((s) => s.name).join(', ') || '—';
  }

  readonly isLoading = computed(
    () =>
      this.statsState().dataState === DataState.LOADING ||
      this.invoicesState().dataState === DataState.LOADING,
  );

  ngOnInit(): void {
    this.analytics
      .summary$()
      .pipe(
        map((response) => ({ dataState: DataState.LOADED, appData: response })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.statsState.set(state));

    this.analytics
      .invoices$(0, 200)
      .pipe(
        map((response) => ({ dataState: DataState.LOADED, appData: response })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.invoicesState.set(state));
  }
}