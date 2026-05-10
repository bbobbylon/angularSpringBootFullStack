/**
 * Represents an invoice record as returned by the backend API.
 *
 * Mirrors the {@code Invoice} JPA entity. Returned nested inside a
 * {@code CustomerInterface} when the full customer detail is fetched.
 */
export interface InvoiceInterface {
  /** Auto-generated unique identifier. */
  id: number;
  /** Human-readable invoice reference code (e.g., 'A3F9KQ2B'). */
  invoiceNumber: string;
  /** Name of the service this invoice covers. */
  services: string;
  /** Payment state (e.g., 'Pending', 'Paid', 'Overdue'). */
  status: string;
  /** Final billed amount after any adjustments or taxes. */
  total: number;
  /** Date the invoice was issued. */
  createdAt: Date;
}
