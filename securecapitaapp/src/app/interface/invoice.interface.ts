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
  /**
   * Name of the service this invoice covers, returned as a flat string by the API.
   *
   * Although the backend {@code Invoice} entity holds a {@code @ManyToOne} to a
   * {@code Services} record, the API response maps just the service name here
   * rather than nesting the full object.
   */
  services: string;
  /** Payment state (e.g., 'Pending', 'Paid', 'Overdue'). */
  status: string;
  /**
   * Final total amount after any adjustments, discounts, or taxes.
   *
   * Maps to {@code totalAmount} on the Java {@code Invoice} entity.
   */
  totalAmount: number;
  /**
   * Date the invoice was issued to the customer.
   *
   * Maps to {@code invoiceDate} on the Java {@code Invoice} entity.
   */
  invoiceDate: Date;
}
