import { ChangeDetectionStrategy, Component, inject, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { StatsInterface } from '../../interface/stats.interface';
import { UserService } from '../../service/user.service';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Renders the summary stats panel on the home dashboard.
 *
 * <p>Receives stats from the parent via {@code @Input}: {@link HomeComponent} already has them,
 * because {@code GET /customer/list} returns the aggregates alongside the page.
 *
 * <h3>Why this deliberately does NOT self-fetch</h3>
 * An earlier note here proposed switching to {@code CustomerService.stats$()} so the panel could
 * load independently of the customer list. That was reconsidered and rejected: unlike the navbar —
 * which appears on seventeen screens, most of which have no reason to load customers — this
 * component renders on exactly one screen, and that screen must fetch the customer list regardless.
 * Self-fetching would issue a second request for figures already present in the first, and the two
 * could then disagree, because they would be two reads of a moving database rather than one.
 *
 * <p>If the panel ever needs to refresh without reloading the list, the right move is to re-fetch
 * the list, not to add a second source for the same numbers. The system-wide aggregates are
 * computed in SQL server-side, so they are accurate regardless of which page is displayed.
 */
@Component({
  selector: 'app-stats',
  imports: [DecimalPipe, RouterLink, TranslocoDirective],
  templateUrl: './stats.component.html',
  styleUrl: './stats.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatsComponent {
  /** Aggregated totals passed down from {@link HomeComponent} via the customer list response. */
  @Input() stats: StatsInterface | undefined;

  private readonly userService = inject(UserService);

  /**
   * Whether the current user has billing-admin access.
   *
   * <p>Drives the Total Billed card's link destination — admins navigate to the detailed Billing
   * Overview page; others fall back to the invoices list, which is always accessible and still
   * relevant. Uses the same authority set as {@code adminGuard}, so the card only ever promises a
   * route the guard will actually allow through.
   */
  // A getter, not a field: authority flags must follow the CURRENT token. Evaluated once at
  // construction they latch whatever was true then — and on a page refresh that is usually an
  // expired token, i.e. "no authorities at all", which would send an admin to the invoice list.
  // UserService memoises the decode, so per-change-detection evaluation is a string compare.
  get canViewBilling(): boolean {
    return this.userService.hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE', 'DELETE:USER');
  }
}
