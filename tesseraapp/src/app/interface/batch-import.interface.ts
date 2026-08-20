import { UserInterface } from './user.interface';

/**
 * One failed row from a batch upload, mirroring the backend's
 * {@code com.bob.angularspringbootfullstack.dto.BatchImportError} record.
 *
 * {@code row} is 1-based and counts only data rows — the header row is never row 1 — matching
 * what a person counts looking at their own spreadsheet with the header excluded.
 */
export interface BatchImportErrorInterface {
  row: number;
  reason: string;
}

/**
 * The partial-success report returned by {@code POST /customer/batch} and
 * {@code POST /customer/invoice/batch}, mirroring the backend's
 * {@code com.bob.angularspringbootfullstack.dto.BatchImportResult} record.
 *
 * A batch upload is never all-or-nothing: {@code imported} counts the rows that were actually
 * persisted, and {@code failed} lists every row that was not, each with a reason a
 * non-technical user can act on without opening the browser console.
 */
export interface BatchImportResultInterface {
  imported: number;
  failed: BatchImportErrorInterface[];
}

/**
 * The {@code data} payload of the standard envelope returned by both batch-import endpoints.
 */
export interface BatchImportDataInterface {
  user: UserInterface;
  result: BatchImportResultInterface;
}
