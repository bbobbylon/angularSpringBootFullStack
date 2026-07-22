import { ChangeDetectionStrategy, Component, inject, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { StatsInterface } from '../../interface/stats.interface';
import { UserService } from '../../service/user.service';

/**
 * Renders the summary stats panel on the home dashboard.
 *
 * Currently receives stats from the parent via {@code @Input} — the parent ({@link HomeComponent})
 * fetches them as part of the {@code GET /customer/list} response and passes them down.
 *
 * TODO: Once the rest of the application is complete, refactor this component to self-fetch
 *  using {@link CustomerService#stats$} (hits {@code GET /customer/stats}) so that stats
 *  load independently of the customer list. This will allow the stats panel to refresh
 *  without triggering a full customer list reload (e.g. after creating a new customer or invoice).
 *  When making this change: remove {@code @Input() stats}, inject {@link CustomerService},
 *  and restore the {@code statsState$} Observable pipeline in {@code ngOnInit}.
 */
@Component({
  selector: 'app-stats',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './stats.component.html',
  styleUrl: './stats.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatsComponent {
  /** Aggregated totals passed down from {@link HomeComponent} via the customer list response. */
  @Input() stats: StatsInterface | undefined;

  /**
   * Whether the current user has billing-admin access.
   *
   * Drives the Total Billed card's link destination — admins navigate to the
   * detailed Billing Overview page; others fall back to the invoices list, which
   * is always accessible and still relevant. Using the same authority set as
   * {@link adminGuard} so the card only promises a route the guard will actually
   * allow through.
   */
  readonly canViewBilling = inject(UserService).hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE', 'DELETE:USER');
}
