import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { SortableHeaderDirective } from './sortable-header.directive';

/**
 * Specs for {@link SortableHeaderDirective} — the shared "clickable, keyboard-activatable
 * {@code <th>}" behavior extracted from customers/invoices/users (FUTURE-ENHANCEMENTS.md §3.7).
 *
 * <p>Driven through a real host template and rendered DOM, the same way {@code has-authority}'s
 * specs are, since what matters is the observable contract (attributes present, the right events
 * activate it) rather than which internal host binding produced them.
 */
describe('SortableHeaderDirective', () => {
  @Component({
    standalone: true,
    imports: [SortableHeaderDirective],
    template: `
      <table>
        <thead>
          <tr>
            <th appSortableHeader (sort)="onSort()" scope="col">Name</th>
          </tr>
        </thead>
      </table>
    `,
  })
  class HostComponent {
    onSort = vi.fn();
  }

  let fixture: ComponentFixture<HostComponent>;
  const th = (): HTMLElement => fixture.nativeElement.querySelector('th');

  const mount = (): void => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  };

  it('marks the header as a keyboard-reachable button', () => {
    mount();

    expect(th().getAttribute('role')).toBe('button');
    expect(th().getAttribute('tabindex')).toBe('0');
    expect(th().classList.contains('pointer')).toBe(true);
  });

  it('emits sort on click', () => {
    mount();

    th().dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(fixture.componentInstance.onSort).toHaveBeenCalledTimes(1);
  });

  it('emits sort on Enter', () => {
    mount();

    th().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    expect(fixture.componentInstance.onSort).toHaveBeenCalledTimes(1);
  });

  it('emits sort on Space and prevents the page-scroll default', () => {
    mount();

    const event = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    th().dispatchEvent(event);

    expect(fixture.componentInstance.onSort).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
  });
});
