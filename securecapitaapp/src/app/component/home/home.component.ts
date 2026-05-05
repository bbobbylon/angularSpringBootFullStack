import { Component, Pipe, PipeTransform, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { Observable, of } from 'rxjs';
import { NavbarComponent } from '../navbar/navbar.component';
import { StatsComponent } from '../stats/stats.component';

@Pipe({ name: 'ExtractArrayValue', standalone: true })
export class ExtractArrayValuePipe implements PipeTransform {
  transform(value: number | { totalPages?: number } | any, mode?: string): unknown[] {
    if (typeof value === 'number') {
      return Array.from({ length: value }, (_, i) => i);
    }
    if (value && typeof value === 'object' && typeof value.totalPages === 'number') {
      return Array.from({ length: value.totalPages }, (_, i) => i);
    }
    if (Array.isArray(value)) {
      return value;
    }
    return [];
  }
}

interface Customer {
  id: number;
  imageUrl?: string;
  name?: string;
  email?: string;
  phone?: string;
  status?: string;
  type?: string;
}

interface Page<T> {
  content: T[];
  totalPages: number;
}

interface HomeState {
  appData: {
    data: {
      user: { name: string; id: number };
      stats: { totalCustomers: number };
      page: Page<Customer>;
    };
  };
}

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, ExtractArrayValuePipe, NavbarComponent, StatsComponent],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
})
export class HomeComponent {
  readonly title = signal('securecapitaapp');

  // Dummy observables used by the template to avoid template errors
  homeState$: Observable<HomeState> = of({
    appData: {
      data: {
        user: { name: 'Demo User', id: 0 },
        stats: { totalCustomers: 0 },
        page: {
          content: [
            {
              id: 1,
              imageUrl: 'assets/images/avatar-placeholder.png',
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

  fileStatus$: Observable<{ percent: number; type: string } | null> = of({ percent: 0, type: 'idle' });
  currentPage$: Observable<number> = of(0);

  // Dummy methods used in template
  report(): void {
    console.log('report clicked');
  }

  goToNextOrPreviousPage(direction: 'forward' | 'backward'): void {
    console.log('navigate', direction);
  }

  goToPage(pageIndex: number): void {
    console.log('goToPage', pageIndex);
  }
}
