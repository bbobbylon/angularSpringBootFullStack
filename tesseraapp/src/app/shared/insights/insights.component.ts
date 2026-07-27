import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { UserService } from '../../service/user.service';
import { StatsInterface } from '../../interface/stats.interface';
import { CustomerInterface } from '../../interface/customer.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * One slice of the customer-status donut.
 *
 * {@code dashArray}/{@code dashOffset} are pre-computed for the SVG `r=15.915`
 * technique (circumference ≈ 100), so a slice's percentage maps directly onto
 * `stroke-dasharray` without any per-frame math in the template.
 */
interface DonutSegment {
  label: string;
  value: number;
  percent: number;
  color: string;
  dashArray: string;
  dashOffset: number;
}

/**
 * Dashboard insights panel rendered on the home screen below the metric cards.
 *
 * Branches on authority, mirroring {@link NavbarComponent#canManageUsers}: staff
 * accounts (UPDATE:USER / UPDATE:ROLE) see operational analytics — a customer-status
 * donut plus billing ratios — while standard accounts see a quick-actions launcher
 * instead. This is a presentation-only gate (NFR-SEC-4); it changes what renders, not
 * what the API permits.
 *
 * Inputs are signal-based ({@code input()}), so the derived {@link segments} /
 * {@link avgInvoiceValue} computeds recalculate automatically when the parent passes
 * fresh data. No backend call is added: the donut tallies the customer page the home
 * view already fetched, and the ratios are derived from the real {@link StatsInterface}
 * totals — nothing here is fabricated.
 */
@Component({
  selector: 'app-insights',
  standalone: true,
  imports: [DecimalPipe, RouterLink, TranslocoDirective],
  templateUrl: './insights.component.html',
  styleUrl: './insights.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InsightsComponent {
  /** System-wide totals (real values straight from the customer-list response). */
  readonly stats = input<StatsInterface | undefined>();
  /** The customer page already loaded by the parent; the donut's fallback source. */
  readonly customers = input<CustomerInterface[] | undefined>();
  /**
   * System-wide status → count map from the backend's GROUP BY aggregation.
   * Preferred over {@link customers} when present so the donut reflects the whole
   * table; absent on older/cached responses, in which case the loaded page is tallied.
   */
  readonly statusBreakdown = input<Record<string, number> | undefined>();

  private readonly userService = inject(UserService);

  /**
   * Whether to show the analytics view instead of the quick-actions view. Uses the
   * same staff authorities the navbar and adminGuard check, so the three stay in sync.
   */
  readonly isAdmin = this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE');

  /** Status → display label + theme-token color. Drives both donut and legend. */
  private readonly statusMeta: Record<string, { label: string; color: string }> = {
    ACTIVE: { label: 'Active', color: 'var(--ok)' },
    PENDING: { label: 'Pending', color: 'var(--accent-strong)' },
    INACTIVE: { label: 'Inactive', color: 'var(--info)' },
    BANNED: { label: 'Banned', color: 'var(--danger)' },
  };

  /**
   * The donut's data source, resolved once per change.
   *
   * Prefers the backend's system-wide {@link statusBreakdown}; falls back to tallying
   * the loaded {@link customers} page. {@code systemWide} drives the honest scope label
   * in the template so the chart never overstates what it actually represents.
   */
  private readonly source = computed<{ counts: Map<string, number>; total: number; systemWide: boolean }>(() => {
    const counts = new Map<string, number>();

    const breakdown = this.statusBreakdown();
    if (breakdown && Object.keys(breakdown).length) {
      for (const [status, count] of Object.entries(breakdown)) {
        counts.set(status.toUpperCase(), count);
      }
      const total = [...counts.values()].reduce((sum, n) => sum + n, 0);
      return { counts, total, systemWide: true };
    }

    for (const customer of this.customers() ?? []) {
      const key = (customer.status ?? 'UNKNOWN').toUpperCase();
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const total = [...counts.values()].reduce((sum, n) => sum + n, 0);
    return { counts, total, systemWide: false };
  });

  /** Total customers represented by the donut (system-wide when available). */
  readonly donutTotal = computed(() => this.source().total);

  /** Honest scope caption: "system-wide" vs. "loaded" so the chart never misleads. */
  readonly donutScope = computed(() => (this.source().systemWide ? 'system-wide' : `${this.source().total} loaded`));

  /**
   * Folds the resolved {@link source} counts into ready-to-render donut slices.
   *
   * Known statuses are ordered (Active → Pending → Inactive → Banned) for a stable
   * legend; any unexpected status is appended with a neutral color so the chart never
   * silently drops data. The running {@code cumulative} percentage feeds each slice's
   * {@code dashOffset} so slices sit end-to-end around the ring.
   */
  readonly segments = computed<DonutSegment[]>(() => {
    const { counts, total } = this.source();
    if (!total) return [];

    const known = ['ACTIVE', 'PENDING', 'INACTIVE', 'BANNED'].filter((k) => counts.has(k));
    const extra = [...counts.keys()].filter((k) => !known.includes(k));
    let cumulative = 0;

    return [...known, ...extra].map((key) => {
      const value = counts.get(key) ?? 0;
      const percent = (value / total) * 100;
      const segment: DonutSegment = {
        label: this.statusMeta[key]?.label ?? key,
        value,
        percent,
        color: this.statusMeta[key]?.color ?? 'var(--text-faint)',
        dashArray: `${percent} ${100 - percent}`,
        dashOffset: 25 - cumulative,
      };
      cumulative += percent;
      return segment;
    });
  });

  /** Mean invoice value (total billed ÷ invoice count); 0 when there are no invoices. */
  readonly avgInvoiceValue = computed(() => {
    const s = this.stats();
    return s?.totalInvoices ? s.totalBilled / s.totalInvoices : 0;
  });

  /** Mean invoices per customer; 0 when there are no customers. */
  readonly invoicesPerCustomer = computed(() => {
    const s = this.stats();
    return s?.totalCustomers ? s.totalInvoices / s.totalCustomers : 0;
  });
}
