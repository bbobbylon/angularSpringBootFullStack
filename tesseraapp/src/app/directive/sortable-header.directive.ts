import { Directive, EventEmitter, HostBinding, HostListener, Output } from '@angular/core';

/**
 * Attribute directive centralizing the "clickable, keyboard-activatable {@code <th>}" pattern
 * used by every sortable table column in this app — {@code customers}, {@code invoices}, and
 * {@code users} each hand-rolled the identical {@code (click)}/{@code (keydown.enter)} handlers
 * plus {@code role="button"}/{@code tabindex="0"} on the header cell itself
 * (FUTURE-ENHANCEMENTS.md §3.7). Centralizing it here means a future keyboard-interaction fix only
 * has to land in one place instead of being hand-applied to every sortable table.
 *
 * <h3>Usage</h3>
 * ```html
 * <th appSortableHeader (sort)="toggleSort('customerName')" scope="col">
 *   {{ t('common.name') }} <i [class]="'bi ' + sortIconClass('customerName')"></i>
 * </th>
 * ```
 *
 * <h3>Why a directive, not a component</h3>
 * A {@code <th>} must stay a real table-header cell for the table's own layout and accessibility
 * semantics ({@code scope="col"}, column sizing, {@code <thead>} structure) — replacing it with a
 * component's own host element would either lose that or require re-templating every column's
 * label-plus-sort-icon markup, which differs per column. An attribute directive layers behavior
 * onto the existing {@code <th>} without touching what it renders.
 *
 * <h3>The one gap this closes over the original hand-rolled markup</h3>
 * The original per-column markup only wired {@code click} and {@code Enter}. A real
 * {@code <button>} also activates on {@code Space}, and {@code role="button"} on a non-button
 * element promises the same activation keys without actually providing them — this directive adds
 * the {@code Space} handler (and prevents its default page-scroll behavior) so that promise is
 * now kept everywhere the directive is used, not just where a developer remembered to add it.
 */
@Directive({
  selector: 'th[appSortableHeader]',
  standalone: true,
})
export class SortableHeaderDirective {
  /** Emitted on click, Enter, or Space — the caller decides which field this column sorts by. */
  @Output() readonly sort = new EventEmitter<void>();

  @HostBinding('class.pointer') protected readonly pointerClass = true;
  @HostBinding('attr.role') protected readonly role = 'button';
  @HostBinding('attr.tabindex') protected readonly tabindex = '0';

  @HostListener('click')
  @HostListener('keydown.enter')
  @HostListener('keydown.space', ['$event'])
  protected onActivate(event?: Event): void {
    // Space's default action is scrolling the page — only relevant for the keydown.space
    // binding, but harmless to call on click/Enter since preventDefault is a no-op there.
    event?.preventDefault();
    this.sort.emit();
  }
}
