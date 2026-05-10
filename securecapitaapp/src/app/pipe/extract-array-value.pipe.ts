import { Pipe, PipeTransform } from '@angular/core';

/**
 * Converts a numeric count or an existing array into an iterable array so that
 * Angular's {@code @for} block can iterate over it.
 *
 * Usage:
 * - {@code totalPages | ExtractArrayValue: 'number'} — produces {@code Array(totalPages).fill(0)}
 * - {@code items | ExtractArrayValue: 'array'} — passes the array through unchanged
 */
@Pipe({ name: 'ExtractArrayValue', standalone: true })
export class ExtractArrayValuePipe implements PipeTransform {
  /**
   * Transforms the input value into an array.
   *
   * @param value - a number (when {@code type} is {@code 'number'}) or an existing array
   * @param type  - {@code 'number'} to create a zero-filled array of that length;
   *                {@code 'array'} to pass the value through as-is
   * @returns an array suitable for use in an {@code @for} block
   */
  transform(value: number | any[], type: 'number' | 'array'): any[] {
    return type === 'number' ? Array(value as number).fill(0) : (value as any[]);
  }
}
