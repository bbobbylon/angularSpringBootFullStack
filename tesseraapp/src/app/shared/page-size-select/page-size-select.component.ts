import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * The row-count choices offered by every pager in the application.
 *
 * <p>Exported so callers can express rules in terms of the list rather than restating its
 * contents — {@code totalElements > PAGE_SIZE_OPTIONS[0]} is the honest way to ask "could
 * changing the size ever produce a second page?", and it stays correct if the list is edited.
 *
 * <p>The values are the ones the dashboard already shipped with. Every screen's existing default
 * (10 for the admin directory, the services catalog and a customer's invoices; 20 for customers and
 * invoices; 50 for the security dashboard's triage tables) is a member, so adopting one shared list
 * changed nobody's first page.
 */
export const PAGE_SIZE_OPTIONS: readonly number[] = [10, 20, 50, 100];

/**
 * The "Rows per page" control that sits beside every pager in the application.
 *
 * <h3>Why this is a component rather than six copies of a {@code <select>}</h3>
 * The dashboard grew a pager per screen — customers, invoices, the admin user directory, both
 * triage tables on the security overview, the services catalog and a customer's invoice history —
 * but only the home page ever grew the control that makes a pager useful. Reproducing that markup
 * six more times would have meant six chances to offer a different set of sizes, and six labels to
 * keep translated. It exists once so those cannot drift apart.
 *
 * <h3>The API deliberately is not two-way bindable</h3>
 * The output is named {@code sizeSelected}, not {@code sizeChange}, which means Angular will not
 * accept {@code [(size)]="pageSize"}. That is the point. Changing the page size is never just
 * changing the page size: the current page index has to return to zero in the same breath, because
 * page 4 of a 10-row listing does not exist once the rows-per-page becomes 100, and the request for
 * it either 404s or lands somewhere arbitrary. A banana-in-a-box would let a caller write the size
 * and quietly skip the reset. Requiring an explicit handler means the two halves cannot be
 * separated by accident.
 *
 * <h3>Labelling</h3>
 * The {@code <select>} is wrapped by its {@code <label>} rather than linked with {@code for}/
 * {@code id}. Both are valid HTML, but the security overview renders two of these on one page, and
 * a hardcoded id would give that page duplicate ids and a label pointing at whichever control the
 * browser found first. Wrapping has no identifier to collide.
 *
 * @see PAGE_SIZE_OPTIONS for the shared choice list
 */
@Component({
  selector: 'app-page-size-select',
  standalone: true,
  imports: [TranslocoDirective],
  templateUrl: './page-size-select.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageSizeSelectComponent {
  /** The size currently in effect, which marks the matching option as selected. */
  readonly size = input.required<number>();

  /** The choices to offer. Overridable, but no caller currently needs to. */
  readonly options = input<readonly number[]>(PAGE_SIZE_OPTIONS);

  /**
   * Emits the newly chosen row count.
   *
   * <p>Named {@code sizeSelected} rather than {@code sizeChange} so that no caller can two-way
   * bind this control and forget to reset the page index — see the class-level note.
   */
  readonly sizeSelected = output<number>();

  /**
   * Translates the raw {@code <select>} value into a number and emits it.
   *
   * <p>Re-selecting the value already in effect emits nothing. Browsers do not fire {@code change}
   * in that case anyway, so this is belt-and-braces against a programmatic dispatch — but it costs
   * one comparison and saves a round trip that could not change the answer.
   *
   * @param value - the selected option's value, as the DOM reports it
   */
  protected choose(value: string): void {
    const next = Number(value);
    if (!Number.isFinite(next) || next === this.size()) return;
    this.sizeSelected.emit(next);
  }
}
