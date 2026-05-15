import { Pipe, PipeTransform } from '@angular/core';

/**
 * Converts a numeric count or an existing array into an iterable array so that
 * Angular's {@code @for} block can iterate over it, or sums a numeric field
 * across all elements of an array.
 *
 * Usage:
 * - {@code totalPages | ExtractArrayValue: 'number'} — produces {@code Array(totalPages).fill(0)}
 * - {@code items | ExtractArrayValue: 'array'} — passes the array through unchanged
 * - {@code items | ExtractArrayValue: 'sum': 'totalAmount'} — sums {@code item.totalAmount} across all items
 */
@Pipe({ name: 'ExtractArrayValue', standalone: true })
export class ExtractArrayValuePipe implements PipeTransform {
  /**
   * Overload for {@code 'number'} and {@code 'array'} modes — always returns {@code any[]}.
   *
   * Declared separately from the {@code 'sum'} overload so Angular's strict template
   * compiler knows the return type is iterable when the pipe is used inside {@code @for}.
   */
  transform(value: number | any[], type: 'number' | 'array'): any[];
  /**
   * Overload for {@code 'sum'} mode — sums {@code field} across all array elements.
   *
   * @param value - the array of objects to reduce
   * @param type  - must be {@code 'sum'}
   * @param field - the numeric property name to accumulate
   * @returns the total as a {@code number}
   */
  transform(value: any[], type: 'sum', field: string): number;
  transform(value: number | any[], type: 'number' | 'array' | 'sum', field?: string): any[] | number {
    if (type === 'number') return Array(value as number).fill(0);
    if (type === 'sum') return (value as any[])?.reduce((acc, item) => acc + (item[field!] ?? 0), 0) ?? 0;
    return value as any[];
  }
}
