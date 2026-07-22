/* eslint-disable @typescript-eslint/no-explicit-any */
import { Pipe, PipeTransform } from '@angular/core';

/**
 * A multipurpose Angular pipe that either converts a plain number into an
 * iterable array (so {@code @for} can loop over it), passes an existing array
 * through unchanged, or sums a named numeric field across every object in an
 * array.
 *
 * <h3>Why this pipe exists</h3>
 * Angular's {@code @for} block requires an iterable (array, Set, etc.).
 * When the server returns a simple integer like {@code totalPages: 5}, there
 * is nothing to iterate over directly.  This pipe bridges that gap by turning
 * the number {@code 5} into {@code [0, 0, 0, 0, 0]}, giving {@code @for}
 * five elements to loop through.  The actual values are irrelevant — only the
 * loop index {@code $index} / {@code i} is used inside the template.
 *
 * <h3>Where it is used</h3>
 * <ul>
 *   <li>
 *     {@code home.component.html} — pagination buttons:
 *     {@code state?.appData?.data?.page?.page?.totalPages | ExtractArrayValue: 'number'}
 *   </li>
 *   <li>
 *     {@code customers.component.html} — pagination buttons (same pattern as home)
 *   </li>
 *   <li>
 *     {@code customer-details.component.html} — "Total Billed" badge:
 *     {@code state?.appData?.data?.customers.invoices | ExtractArrayValue: 'sum': 'totalAmount'}
 *   </li>
 * </ul>
 *
 * <h3>Registered as standalone</h3>
 * {@code standalone: true} means this pipe does NOT need to be declared inside
 * an {@code NgModule}.  Any component that wants to use it simply adds
 * {@code ExtractArrayValuePipe} to its own {@code imports} array.
 */
@Pipe({ name: 'ExtractArrayValue', standalone: true })
export class ExtractArrayValuePipe implements PipeTransform {
  /**
   * Overload 1 — {@code 'number'} and {@code 'array'} modes.
   *
   * Declaring this as a separate overload (instead of using {@code any[]})
   * tells the Angular template compiler that the return value is always an
   * array when these two modes are chosen.  That lets {@code @for} accept
   * the result without a type error.
   *
   * @param value - a plain number (for {@code 'number'}) or an existing
   *                array (for {@code 'array'})
   * @param type  - {@code 'number'} to build a new array of that length,
   *                or {@code 'array'} to return the value unchanged
   * @returns an array that {@code @for} can iterate over
   */
  transform(value: number | any[], type: 'number' | 'array'): any[];

  /**
   * Overload 2 — {@code 'sum'} mode.
   *
   * A separate overload so the compiler knows the return is a {@code number}
   * (not an array) when {@code 'sum'} is passed.  This prevents accidental
   * use inside {@code @for}.
   *
   * Used in {@code customer-details.component.html} to total all
   * {@code totalAmount} fields across a customer's invoice list:
   * {@code customers. Invoices | ExtractArrayValue: 'sum': 'totalAmount'}
   *
   * @param value - the array of objects to reduce (e.g., an array of invoices)
   * @param type  - must be {@code 'sum'}
   * @param sumField - the numeric property name to accumulate
   *                   (e.g. {@code 'totalAmount'}, {@code 'price'})
   * @returns the running total as a {@code number}
   */
  transform(value: any[], type: 'sum', sumField: string): number;

  /**
   * Overload 3 — {@code 'filter-sum'} mode.
   *
   * Narrows the array to only items where {@code filterField === filterValue},
   * then sums {@code sumField} across those items.  Used to produce per-status
   * invoice totals without a separate filter pipe.
   *
   * Template usage in {@code customer-details.component.html}:
   * {@code customers. Invoices | ExtractArrayValue: 'filter-sum': 'totalAmount': 'status': 'PAID'}
   * → sums {@code totalAmount} for every invoice whose {@code status} is {@code 'PAID'}.
   *
   * @param value       - the array of objects to filter and reduce
   * @param type        - must be {@code 'filter-sum'}
   * @param sumField    - the numeric property to accumulate (e.g. {@code 'totalAmount'})
   * @param filterField - the property to match against (e.g. {@code 'status'})
   * @param filterValue - the value that {@code filterField} must equal (e.g. {@code 'PAID'})
   * @returns the sum of {@code sumField} for matching items only
   */
  transform(value: any[], type: 'filter-sum', sumField: string, filterField: string, filterValue: string): number;

  /**
   * Implementation that satisfies all overloads above.
   *
   * TypeScript requires one concrete implementation signature that is broad
   * enough to cover every overload.  This signature is intentionally hidden
   * from callers — only the typed overloads above are visible.
   *
   * @param value       - number or array depending on the chosen mode.
   * @param type        - {@code 'number'}, {@code 'array'}, {@code 'sum'}, or {@code 'filter-sum'}.
   * @param sumField    - numeric field to accumulate (required for {@code 'sum'} and {@code 'filter-sum'}).
   * @param filterField - field to match against (required for {@code 'filter-sum'}).
   * @param filterValue - value to match (required for {@code 'filter-sum'}).
   * @returns {@code any[]} for the first two modes, {@code number} for the sum modes
   *
   */
  transform(
    value: number | any[],
    type: 'number' | 'array' | 'sum' | 'filter-sum',
    sumField?: string,
    filterField?: string,
    filterValue?: string,
  ): any[] | number {
    if (type === 'number') {
      /**
       * Array(n) creates a sparse array with n slots.
       * .fill(0) populates every slot with 0 so Angular can iterate over it.
       * Template usage: totalPages (e.g., 5) → [0,0,0,0,0]
       * The @for then uses $index (0,1,2,3,4) to render a one-page button each.
       * */
      return Array(value as number).fill(0);
    }
    if (type === 'sum') {
      /**
       * Optional chaining (?.) guards against a null/undefined array on the first load.
       * reduce() walks every item, adding item[sumField] to the accumulator.
       * ?? 0 handles the case where the item[sumField] is undefined (missing field).
       * The outer ?? 0 handles a null/undefined array (returns 0 instead of crashing).
       * Template usage: invoices | ExtractArrayValue: 'sum': 'totalAmount'
       * → adds up every invoice's totalAmount and displays it as "Total Billed"
       * */
      return (value as any[])?.reduce((acc, item) => acc + (item[sumField!] ?? 0), 0) ?? 0;
    }
    if (type === 'filter-sum') {
      /**
       * First, .filter() keeps only items where item[filterField] === filterValue.
       * Then .reduce() sums the sumField across those matched items only.
       * Template usage: invoices | ExtractArrayValue: 'filter-sum': 'totalAmount': 'status': 'PAID'
       * → sums totalAmount for PAID invoices only
       * */
      return (value as any[])?.filter((item) => item[filterField!] === filterValue)?.reduce((acc, item) => acc + (item[sumField!] ?? 0), 0) ?? 0;
    }
    /**
     * 'array' mode — the value is already an array, return it as-is.
     * Useful when a template slot sometimes receives a number and sometimes
     * a pre-built array, and you want a single pipe call to handle both.
     * */
    return value as any[];
  }
}
