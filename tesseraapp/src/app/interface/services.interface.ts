/**
 * Mirrors the backend {@code Services} JPA entity.
 *
 * Represents a catalog entry for a service the company offers. Returned by
 * {@code GET /customer/invoice/new} so the new-invoice form can populate its
 * service dropdown with predefined offerings and their standard prices.
 */
export interface ServicesInterface {
  /** Auto-generated unique identifier. */
  id: number;
  /** Canonical display name of the service (e.g., 'Web Development'). */
  name: string;
  /** Human-readable summary of what the service entails. */
  description?: string;
  /** Standard base price for the service in the application's default currency. */
  price: number;
}
