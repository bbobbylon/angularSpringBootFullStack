import { Component, signal } from '@angular/core';

/**
 * Customer detail shell component.
 *
 * Hosts the single-customer view until backend wiring is complete.
 */
@Component({
  selector: 'app-customer',
  templateUrl: './customer.component.html',
  styleUrls: ['./customer.component.css'],
})
export class CustomerComponent {
  /** Temporary title used by the placeholder template. */
  protected readonly title = signal('securecapitaapp');
}
