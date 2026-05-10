import { Pipe, PipeTransform } from '@angular/core';

/**
 * Converts a number into an array of that length so @for can iterate over it.
 * Usage: totalPages | ExtractArrayValue: 'number'
 */
@Pipe({ name: 'ExtractArrayValue', standalone: true })
export class ExtractArrayValuePipe implements PipeTransform {
  transform(value: number | any[], type: 'number' | 'array'): any[] {
    return type === 'number' ? Array(value as number).fill(0) : (value as any[]);
  }
}
