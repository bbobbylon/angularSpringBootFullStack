import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

interface Stats {
  totalCustomers: number;
  totalInvoices: number;
  totalBilled: number;
}

@Component({
  selector: 'app-stats',
  imports: [],
  templateUrl: './stats.component.html',
  styleUrl: './stats.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatsComponent {
  protected readonly stats = signal<Stats | null>({
    totalCustomers: 42,
    totalInvoices: 128,
    totalBilled: 9450,
  });
}
