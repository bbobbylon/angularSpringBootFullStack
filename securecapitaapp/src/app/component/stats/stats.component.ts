import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { StatsInterface } from '../../interface/stats.interface';

/**
 * Renders the summary stats panel on the home dashboard.
 *
 * Receives live stats from the parent via @Input — the parent (HomeComponent)
 * fetches them as part of the customer list response and passes them down.
 */
@Component({
  selector: 'app-stats',
  imports: [DecimalPipe],
  templateUrl: './stats.component.html',
  styleUrl: './stats.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatsComponent {
  @Input() stats: StatsInterface;
}
