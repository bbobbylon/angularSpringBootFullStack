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
   * Transforms the input value based on the requested {@code type}.
   *
   * @param value - a number (when {@code type} is {@code 'number'}) or an array
   * @param type  - {@code 'number'} to create a zero-filled array of that length;
   *                {@code 'array'} to pass the value through as-is;
   *                {@code 'sum'} to sum the numeric {@code field} across all array elements
   * @param field - the property name to sum when {@code type} is {@code 'sum'}
   * @returns an array for {@code 'number'} and {@code 'array'} modes; a number for {@code 'sum'} mode
   */
  transform(value: number | any[], type: 'number' | 'array' | 'sum', field?: string): any[] | number {
    if (type === 'number') return Array(value as number).fill(0);
    if (type === 'sum') return (value as any[])?.reduce((acc, item) => acc + (item[field!] ?? 0), 0) ?? 0;
    return value as any[];
  }
}
