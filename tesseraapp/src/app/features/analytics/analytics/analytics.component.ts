import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { AnalyticsService } from '../../../service/analytics.service';
import { UserService } from '../../../service/user.service';
import { OrganizationService } from '../../../service/organization.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { GlobalStateInterface } from '../../../interface/global-state.interface';
import { CustomHttpResponseInterface } from '../../../interface/customhttpresponse.interface';
import {
  CustomerListDataInterface,
  InvoiceListDataInterface,
} from '../../../interface/appstates.interface';
import { CustomerInterface } from '../../../interface/customer.interface';
import { InvoiceInterface } from '../../../interface/invoice.interface';
import { UserInterface } from '../../../interface/user.interface';
import { OrganizationInterface } from '../../../interface/organization.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/** One point on the dual-trend SVG line/area chart. */
interface TrendPoint {
  label: string;
  customers: number;
  revenue: number;
  /** 0–100 normalized X position for the SVG viewBox. */
  x: number;
  /** SVG Y position for customer line (viewBox height = 60, chart from y=5 to y=55). */
  custY: number;
  /** SVG Y position for revenue line. */
  revY: number;
}

/** One column in the stacked invoice-status bar chart. */
interface StackedBar {
  label: string;
  paid: number;
  pending: number;
  overdue: number;
  other: number;
  total: number;
  paidPct: number;
  pendingPct: number;
  overduePct: number;
  otherPct: number;
  /** Height of this column relative to the tallest month (0–100). */
  barHeight: number;
}

/** One service row in the utilization table. */
interface ServiceUtil {
  name: string;
  count: number;
  total: number;
  barPct: number;
}

/**
 * Comprehensive analytics hub — {@code /analytics}.
 *
 * Presents cross-entity data trends that span customers and invoices in a single
 * view. Loads two data sets in parallel (customers for growth analysis, invoices
 * for revenue analysis) and derives all visuals as computed signals. The data is
 * fetched from the admin-only {@code /admin/analytics/**} surface ({@link
 * AnalyticsService}) rather than the application-wide {@code /customer/**} endpoints,
 * so access is genuinely enforced server-side (UPDATE:USER / UPDATE:ROLE), not just by
 * the route's {@code adminGuard}.
 *
 * Charts rendered here:
 * <ul>
 *   <li>SVG dual-area line chart — monthly customer acquisitions overlaid with
 *       monthly revenue (each series independently normalized so neither flattens
 *       the other).</li>
 *   <li>Stacked monthly invoice-status bars — PAID / PENDING / OVERDUE per month,
 *       showing collection momentum over time.</li>
 *   <li>Service utilization horizontal bars — which service lines generate the
 *       most invoice events.</li>
 *   <li>KPI scorecards — month-over-month customer and revenue growth rates,
 *       collection rate, and pending pipeline value.</li>
 * </ul>
 */
@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [NavbarComponent, RouterLink, DecimalPipe, FormsModule, TranslocoDirective],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsComponent implements OnInit {
  readonly DataState = DataState;

  private readonly analytics = inject(AnalyticsService);
  private readonly userService = inject(UserService);
  private readonly organizationService = inject(OrganizationService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly customersState = signal<GlobalStateInterface<CustomHttpResponseInterface<CustomerListDataInterface>>>({
    dataState: DataState.LOADING,
  });
  readonly invoicesState = signal<GlobalStateInterface<CustomHttpResponseInterface<InvoiceListDataInterface>>>({
    dataState: DataState.LOADING,
  });

  /** True while {@link emailReport} is in flight — disables the button and swaps its label. */
  readonly isEmailingReport = signal(false);

  readonly user = computed<UserInterface | undefined>(
    () => this.customersState().appData?.data?.user,
  );

  /**
   * The organization catalog for the org filter {@code <select>}, populated only for an
   * {@link isUnscopedTier} caller ({@code OrganizationController#requireUnscopedTier}'s frontend
   * mirror — see {@code OrganizationsComponent}'s Javadoc for why role name, not authority, is
   * the right check). A scoped caller (e.g. {@code ROLE_ORGANIZATION_ADMIN}) already only ever
   * sees their own organization's data server-side, so the filter would have nothing to narrow
   * and is hidden rather than shown with a single, redundant option.
   */
  readonly organizations = signal<OrganizationInterface[]>([]);
  /** The org filter's current selection, or {@code undefined} for "all organizations in scope". */
  readonly selectedOrganizationId = signal<number | undefined>(undefined);
  /** Guards {@link organizations} against being re-fetched every time {@link user} recomputes. */
  private organizationsRequested = false;

  /** Whether the signed-in user may see (and use) the organization filter. */
  protected get isUnscopedTier(): boolean {
    const role = this.user()?.roleName;
    return role === 'ROLE_ADMIN' || role === 'ROLE_APPLICATION_ADMIN';
  }

  private readonly customers = computed<CustomerInterface[]>(
    () => this.customersState().appData?.data?.page?.content ?? [],
  );
  private readonly invoices = computed<InvoiceInterface[]>(
    () => this.invoicesState().appData?.data?.invoices?.content ?? [],
  );

  readonly isLoading = computed(
    () =>
      this.customersState().dataState === DataState.LOADING ||
      this.invoicesState().dataState === DataState.LOADING,
  );

  // A getter, not a field: authority flags must follow the CURRENT token. Evaluated once at
  // construction they latch whatever was true then — and on a page refresh that is usually an
  // expired token, i.e. "no authorities at all". UserService memoises the decode.
  get isAdmin(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE', 'DELETE:USER');
  }

  // ── KPIs ────────────────────────────────────────────────────────────────

  /** MoM customer count change as a signed percentage. */
  readonly momCustomerGrowth = computed<number>(() => {
    const monthly = this.monthlyCustomerData();
    if (monthly.length < 2) return 0;
    const prev = monthly[monthly.length - 2].count;
    const curr = monthly[monthly.length - 1].count;
    return prev === 0 ? 0 : ((curr - prev) / prev) * 100;
  });

  /** MoM revenue change as a signed percentage. */
  readonly momRevenueGrowth = computed<number>(() => {
    const monthly = this.monthlyInvoiceData();
    if (monthly.length < 2) return 0;
    const prev = monthly[monthly.length - 2].revenue;
    const curr = monthly[monthly.length - 1].revenue;
    return prev === 0 ? 0 : ((curr - prev) / prev) * 100;
  });

  readonly collectionRate = computed<number>(() => {
    const list = this.invoices();
    if (!list.length) return 0;
    const paid = list.filter((i) => i.status?.toUpperCase() === 'PAID').length;
    return (paid / list.length) * 100;
  });

  readonly pendingPipelineValue = computed<number>(() =>
    this.invoices()
      .filter((i) => i.status?.toUpperCase() === 'PENDING')
      .reduce((sum, i) => sum + (i.totalAmount ?? 0), 0),
  );

  // ── Monthly customer data (for dual chart + acquisition bars) ──────────

  private readonly monthlyCustomerData = computed<{ key: string; label: string; count: number }[]>(() => {
    const map = new Map<string, number>();
    for (const c of this.customers()) {
      const d = new Date(c.createdAt);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      map.set(key, (map.get(key) ?? 0) + 1);
    }
    return [...map.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .slice(-12)
      .map(([key, count]) => {
        const [yr, mo] = key.split('-').map(Number);
        return {
          key,
          label: new Date(yr, mo - 1).toLocaleString('default', { month: 'short', year: '2-digit' }),
          count,
        };
      });
  });

  // ── Monthly invoice/revenue data (for dual chart + stacked bars) ───────

  private readonly monthlyInvoiceData = computed<
    { key: string; label: string; revenue: number; paid: number; pending: number; overdue: number; other: number }[]
  >(() => {
    const map = new Map<string, { revenue: number; paid: number; pending: number; overdue: number; other: number }>();
    for (const inv of this.invoices()) {
      const d = new Date(inv.invoiceDate);
      const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
      const prev = map.get(key) ?? { revenue: 0, paid: 0, pending: 0, overdue: 0, other: 0 };
      const status = inv.status?.toUpperCase() ?? 'OTHER';
      map.set(key, {
        revenue: prev.revenue + (inv.totalAmount ?? 0),
        paid: prev.paid + (status === 'PAID' ? 1 : 0),
        pending: prev.pending + (status === 'PENDING' ? 1 : 0),
        overdue: prev.overdue + (status === 'OVERDUE' ? 1 : 0),
        other: prev.other + (!['PAID', 'PENDING', 'OVERDUE'].includes(status) ? 1 : 0),
      });
    }
    return [...map.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .slice(-12)
      .map(([key, val]) => {
        const [yr, mo] = key.split('-').map(Number);
        return {
          key,
          label: new Date(yr, mo - 1).toLocaleString('default', { month: 'short', year: '2-digit' }),
          ...val,
        };
      });
  });

  // ── SVG dual-area line chart ─────────────────────────────────────────────

  /** Data points with SVG (x, custY, revY) coordinates ready for template binding. */
  readonly trendPoints = computed<TrendPoint[]>(() => {
    const custMonths = this.monthlyCustomerData();
    const revMonths = this.monthlyInvoiceData();

    // Merge on key, using the union of all keys
    const allKeys = [
      ...new Set([...custMonths.map((m) => m.key), ...revMonths.map((m) => m.key)]),
    ].sort();
    if (!allKeys.length) return [];

    const custMap = new Map(custMonths.map((m) => [m.key, m.count]));
    const revMap = new Map(revMonths.map((m) => [m.key, m.revenue]));

    const points = allKeys.slice(-10).map((key) => {
      const [yr, mo] = key.split('-').map(Number);
      return {
        key,
        label: new Date(yr, mo - 1).toLocaleString('default', { month: 'short', year: '2-digit' }),
        customers: custMap.get(key) ?? 0,
        revenue: revMap.get(key) ?? 0,
      };
    });

    const maxCust = Math.max(...points.map((p) => p.customers), 1);
    const maxRev = Math.max(...points.map((p) => p.revenue), 1);
    const n = points.length;

    return points.map((p, i) => ({
      label: p.label,
      customers: p.customers,
      revenue: p.revenue,
      x: n === 1 ? 50 : (i / (n - 1)) * 96 + 2,
      custY: 55 - (p.customers / maxCust) * 48,
      revY: 55 - (p.revenue / maxRev) * 48,
    }));
  });

  /** SVG polyline points string for the customer line. */
  readonly customerLinePath = computed<string>(() =>
    this.trendPoints()
      .map((p) => `${p.x},${p.custY}`)
      .join(' '),
  );

  /** SVG polyline points string for the revenue line. */
  readonly revenueLinePath = computed<string>(() =>
    this.trendPoints()
      .map((p) => `${p.x},${p.revY}`)
      .join(' '),
  );

  /** SVG filled area path under the customer line. */
  readonly customerAreaPath = computed<string>(() => {
    const pts = this.trendPoints();
    if (!pts.length) return '';
    const line = pts.map((p) => `${p.x},${p.custY}`).join(' L ');
    return `M ${pts[0].x},56 L ${line} L ${pts[pts.length - 1].x},56 Z`;
  });

  /** SVG filled area path under the revenue line. */
  readonly revenueAreaPath = computed<string>(() => {
    const pts = this.trendPoints();
    if (!pts.length) return '';
    const line = pts.map((p) => `${p.x},${p.revY}`).join(' L ');
    return `M ${pts[0].x},56 L ${line} L ${pts[pts.length - 1].x},56 Z`;
  });

  // ── Stacked invoice-status bars ──────────────────────────────────────────

  readonly stackedBars = computed<StackedBar[]>(() => {
    const months = this.monthlyInvoiceData().slice(-8);
    const maxTotal = Math.max(...months.map((m) => m.paid + m.pending + m.overdue + m.other), 1);

    return months.map((m) => {
      const total = m.paid + m.pending + m.overdue + m.other;
      const safe = total || 1;
      return {
        label: m.label,
        paid: m.paid,
        pending: m.pending,
        overdue: m.overdue,
        other: m.other,
        total,
        paidPct: (m.paid / safe) * 100,
        pendingPct: (m.pending / safe) * 100,
        overduePct: (m.overdue / safe) * 100,
        otherPct: (m.other / safe) * 100,
        barHeight: (total / maxTotal) * 100,
      };
    });
  });

  // ── Customer acquisition bar chart ───────────────────────────────────────

  readonly acquisitionBars = computed<{ label: string; count: number; barHeight: number }[]>(() => {
    const months = this.monthlyCustomerData().slice(-8);
    const maxCount = Math.max(...months.map((m) => m.count), 1);
    return months.map((m) => ({
      label: m.label,
      count: m.count,
      barHeight: (m.count / maxCount) * 100,
    }));
  });

  // ── Customer status distribution (donut) ─────────────────────────────────

  private readonly customerStatusMeta: Record<string, { label: string; color: string }> = {
    ACTIVE: { label: 'Active', color: 'var(--ok)' },
    PENDING: { label: 'Pending', color: 'var(--accent-strong)' },
    INACTIVE: { label: 'Inactive', color: 'var(--info)' },
    BANNED: { label: 'Banned', color: 'var(--danger)' },
  };

  readonly customerStatusSegments = computed(() => {
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
        label: this.customerStatusMeta[key]?.label ?? key,
        value,
        percent,
        color: this.customerStatusMeta[key]?.color ?? 'var(--text-faint)',
        dashArray: `${percent} ${100 - percent}`,
        dashOffset: 25 - cumulative,
      };
      cumulative += percent;
      return seg;
    });
  });

  readonly customerTotal = computed(() => this.customers().length);

  // ── Customer type split ───────────────────────────────────────────────────

  readonly customerTypeSplit = computed<{ label: string; count: number; pct: number; color: string }[]>(() => {
    const typeMap = new Map<string, number>();
    for (const c of this.customers()) {
      const key = c.type ?? 'UNKNOWN';
      typeMap.set(key, (typeMap.get(key) ?? 0) + 1);
    }
    const total = [...typeMap.values()].reduce((s, n) => s + n, 0) || 1;
    const colors = ['var(--accent-strong)', 'var(--info)', 'var(--ok)', 'var(--warn)', 'var(--danger)'];
    return [...typeMap.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([label, count], i) => ({ label, count, pct: (count / total) * 100, color: colors[i % colors.length] }));
  });

  // ── Service utilization ───────────────────────────────────────────────────

  readonly serviceUtil = computed<ServiceUtil[]>(() => {
    const svcMap = new Map<string, { count: number; total: number }>();
    for (const inv of this.invoices()) {
      for (const svc of inv.services ?? []) {
        const prev = svcMap.get(svc.name) ?? { count: 0, total: 0 };
        svcMap.set(svc.name, { count: prev.count + 1, total: prev.total + (svc.price ?? 0) });
      }
    }
    const sorted = [...svcMap.entries()].sort((a, b) => b[1].count - a[1].count).slice(0, 10);
    const maxCount = Math.max(...sorted.map(([, v]) => v.count), 1);
    return sorted.map(([name, val]) => ({
      name,
      count: val.count,
      total: val.total,
      barPct: (val.count / maxCount) * 100,
    }));
  });

  // ── Chart crosshair tooltip ─────────────────────────────────────────────

  readonly hoveredTrendIdx = signal<number | null>(null);
  /** Left position of the tooltip, as a percentage of the chart-wrap width (0–100). */
  readonly tooltipLeft = signal(0);
  /** The actual data point currently under the cursor, or null when off-chart. */
  readonly hoveredPoint = computed<TrendPoint | null>(() => {
    const i = this.hoveredTrendIdx();
    return i !== null ? (this.trendPoints()[i] ?? null) : null;
  });

  /**
   * Tracks mouse position over the dual-area SVG chart.
   *
   * Converts the cursor's X fraction within the wrapper into an SVG-space X
   * coordinate (viewBox 0–100), then finds the nearest TrendPoint. Clamping
   * the tooltip to 8–92% prevents it from overflowing the panel edges.
   */
  onChartMove(event: MouseEvent): void {
    const wrap = event.currentTarget as HTMLElement;
    const fraction = Math.max(0, Math.min(1, event.offsetX / wrap.offsetWidth));
    const svgX = fraction * 100;
    const pts = this.trendPoints();
    if (!pts.length) return;
    let nearestIdx = 0;
    let minDist = Math.abs(pts[0].x - svgX);
    for (let i = 1; i < pts.length; i++) {
      const d = Math.abs(pts[i].x - svgX);
      if (d < minDist) { minDist = d; nearestIdx = i; }
    }
    this.hoveredTrendIdx.set(nearestIdx);
    this.tooltipLeft.set(Math.min(Math.max(fraction * 100, 8), 92));
  }

  onChartLeave(): void {
    this.hoveredTrendIdx.set(null);
  }

  /**
   * Emails the signed-in administrator their own report digest — the "Email me this report"
   * button (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails"). Mirrors
   * {@code InvoiceDetailComponent.emailInvoice} exactly: guarded by the in-flight signal against
   * double-clicks, and success/failure both surface through {@link NotificationsService} rather
   * than any local state this component would otherwise need to render.
   */
  protected emailReport(): void {
    if (this.isEmailingReport()) return;
    this.isEmailingReport.set(true);

    this.analytics
      .emailReport$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isEmailingReport.set(false);
          this.notification.onSuccess(response.message ?? 'Report emailed.');
        },
        error: (error: Error) => {
          this.isEmailingReport.set(false);
          this.notification.onError(error.message);
        },
      });
  }

  constructor() {
    // Fetches the org catalog for the filter <select> the first (and only the first) time the
    // caller resolves as an unscoped tier — `user` flips from undefined to populated once
    // `loadData`'s customers$ call resolves, so this fires once per page visit, not on every
    // signal recomputation.
    effect(() => {
      if (this.isUnscopedTier && !this.organizationsRequested) {
        this.organizationsRequested = true;
        this.organizationService
          .organizations$()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: (response) => this.organizations.set(response.data?.organizations ?? []),
            error: (error: string) => this.notification.onError(error),
          });
      }
    });
  }

  ngOnInit(): void {
    this.loadData();
  }

  /**
   * Loads the customer and invoice data sets that back every chart on this page, narrowed to
   * {@link selectedOrganizationId} when the filter is active. Called once on init and again by
   * {@link onOrganizationFilterChange} whenever the filter's selection changes — both calls run
   * through this one method so the two data sets and the filter can never drift out of sync.
   */
  private loadData(): void {
    const organizationId = this.selectedOrganizationId();

    this.analytics
      .customers$(0, 500, organizationId)
      .pipe(
        map((r) => ({ dataState: DataState.LOADED, appData: r })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((s) => this.customersState.set(s));

    this.analytics
      .invoices$(0, 500, organizationId)
      .pipe(
        map((r) => ({ dataState: DataState.LOADED, appData: r })),
        startWith({ dataState: DataState.LOADING }),
        catchError((error: string) => {
          this.notification.onError(error);
          return of({ dataState: DataState.ERROR, error });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((s) => this.invoicesState.set(s));
  }

  /**
   * Handles the org filter {@code <select>}'s change event, re-loading both data sets scoped to
   * the newly selected organization (or back to the caller's full scope for the "All
   * organizations" option).
   *
   * @param value - the select's raw string value; empty string means "no filter"
   */
  protected onOrganizationFilterChange(value: string): void {
    this.selectedOrganizationId.set(value ? Number(value) : undefined);
    this.loadData();
  }
}
