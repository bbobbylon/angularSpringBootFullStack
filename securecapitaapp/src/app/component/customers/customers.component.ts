import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Customers list placeholder view.
 *
 * Provides the shell for the customers table and filters.
 * The detailed implementation will bind to the customers API.
 */
@Component({
  selector: 'app-customers',
  imports: [],
  templateUrl: './customers.component.html',
  styleUrl: './customers.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersComponent { }
