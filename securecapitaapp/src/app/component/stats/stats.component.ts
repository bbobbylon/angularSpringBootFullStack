import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

/**
 * Summary statistics displayed on the home dashboard.
 *
 * Uses placeholder values until the stats API is wired in.
 */
interface Stats {
  totalCustomers: number;
  totalInvoices: number;
  totalBilled: number;
}

/**
 * Renders the stats panel displayed on the home page.
 *
 * ChangeDetectionStrategy.OnPush keeps it lightweight as data updates.
 */
@Component({
  selector: 'app-stats',
  imports: [],
  templateUrl: './stats.component.html',
  styleUrl: './stats.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatsComponent {
  /** Stub stats values shown until backend data is connected. */
  protected readonly stats = signal<Stats | null>({
    totalCustomers: 42,
    totalInvoices: 128,
    totalBilled: 9450,
  });
}
