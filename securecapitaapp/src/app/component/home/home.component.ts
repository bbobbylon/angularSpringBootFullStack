import { Component, Pipe, PipeTransform, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { StatsComponent } from '../stats/stats.component';

/**
 * Transforms a number, a paginated object, or an array into a plain array.
 *
 * Used in templates to generate index arrays for pagination controls:
 *   - number        → Array(n) filled with indices [0, 1, … n-1]
 *   - { totalPages } → Array(totalPages) filled with indices
 *   - array          → returned as-is
 */
@Pipe({ name: 'ExtractArrayValue', standalone: true })
export class ExtractArrayValuePipe implements PipeTransform {
  /**
   * @param value - a page count, paginated response, or raw array
   * @param _mode - reserved for future use; currently unused
   * @returns an array suitable for *ngFor iteration
   */
  transform(value: number | { totalPages?: number } | unknown[], _mode?: string): unknown[] {
    if (typeof value === 'number') {
      return Array.from({ length: value }, (_, i) => i);
    }
    if (value && typeof value === 'object' && !Array.isArray(value) && typeof (value as { totalPages?: number }).totalPages === 'number') {
      return Array.from({ length: (value as { totalPages: number }).totalPages }, (_, i) => i);
    }
    if (Array.isArray(value)) {
      return value;
    }
    return [];
  }
}

/** Represents a single customer row in the home table. */
interface Customer {
  id: number;
  imageUrl?: string;
  name?: string;
  email?: string;
  phone?: string;
  status?: string;
  type?: string;
}

/** Generic paginated response wrapper used for the customer list. */
interface Page<T> {
  content: T[];
  totalPages: number;
}

/**
 * Shape of the data the home template consumes.
 * user is omitted here — the NavbarComponent fetches and displays the current
 * user independently. page and stats will be replaced with real backend data
 * when the customers and statistics endpoints are built.
 */
interface HomeStateData {
  data: {
    page: Page<Customer>;
    stats: { totalCustomers: number };
  };
}

/**
 * Main dashboard component displayed after login.
 *
 * Currently renders stub/dummy data for the customer table and stats panel.
 * Real data will be wired in once the customers and statistics backend
 * endpoints are implemented. The navbar and stats are delegated to their
 * own standalone components.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, ExtractArrayValuePipe, NavbarComponent, StatsComponent],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
})
export class HomeComponent {
  readonly title = signal('securecapitaapp');

  /** Dummy state — page and stats will be replaced with real API calls once
   *  the customers and statistics backend endpoints are implemented. */
  homeState$: Observable<{ appData: HomeStateData }> = of({
    appData: {
      data: {
        stats: { totalCustomers: 0 },
        page: {
          content: [
            {
              id: 1,
              imageUrl: 'assets/images/runeOfATK.jpg',
              name: 'John Doe',
              email: 'john@example.com',
              phone: '555-1234',
              status: 'ACTIVE',
              type: 'REGULAR',
            },
          ],
          totalPages: 1,
        },
      },
    },
  });

  /** Tracks file upload progress; null when no upload is in progress. */
  fileStatus$: Observable<{ percent: number; type: string } | null> = of({ percent: 0, type: 'idle' });

  /** The currently visible page index (0-based). */
  currentPage$: Observable<number> = of(0);

  /**
   * Triggers a report download or export for the current data set.
   * Stub — implementation pending backend report endpoint.
   */
  report(): void {
    console.log('report clicked');
  }

  /**
   * Navigates to the next or previous page of the customer list.
   *
   * @param direction - 'forward' to go to the next page, 'backward' to go to the previous
   */
  goToNextOrPreviousPage(direction: 'forward' | 'backward'): void {
    console.log('navigate', direction);
  }

  /**
   * Jumps directly to a specific page index in the customer list.
   *
   * @param pageIndex - the 0-based index of the page to navigate to
   */
  goToPage(pageIndex: number): void {
    console.log('goToPage', pageIndex);
  }
}
