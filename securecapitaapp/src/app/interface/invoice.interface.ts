/**
 * A single line item on an invoice, mirroring the backend {@code InvoiceLineItem} embeddable.
 *
 * Stored in the {@code invoiceserviceitems} table and returned as a nested array
 * inside each {@link InvoiceInterface}.
 */
export interface InvoiceLineItemInterface {
  /** Human-readable name of the service rendered (e.g., 'Web Development'). */
  name: string;
  /** Price charged for this line item in the application's default currency. */
  price: number;
}

/**
 * Represents an invoice record as returned by the backend API.
 *
 * Mirrors the {@code Invoice} JPA entity. {@code services} is a proper array of
 * {@link InvoiceLineItemInterface} objects stored in a separate collection table,
 * not a flat comma-separated string.
 */
export interface InvoiceInterface {
  /** Auto-generated unique identifier. */
  id: number;
  /** Human-readable invoice reference code (e.g., 'A3F9KQ2B'). */
  invoiceNumber: string;
  /**
   * The line items on this invoice — each represents one service rendered.
   *
   * Fetched eagerly from the {@code invoiceserviceitems} table by the backend
   * and serialized as a JSON array so the frontend receives proper objects.
   */
  services: InvoiceLineItemInterface[];
  /** Payment state (e.g., 'PENDING', 'PAID', 'OVERDUE'). */
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
