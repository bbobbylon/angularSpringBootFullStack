/**
 * Aggregated dashboard statistics returned by {@code GET /customer/stats}
 * and embedded in the {@code GET /customer/list} response.
 *
 * All values are system-wide totals across all customers and invoices.
 *
 * @see StatsData
 */
export interface StatsInterface {
  /** Total number of customer records in the system. */
  totalCustomers: number;
  /** Total number of invoices across all customers. */
  totalInvoices: number;
  /** Sum of all invoice {@code totalAmount} values. */
  totalBilled: number;
}
