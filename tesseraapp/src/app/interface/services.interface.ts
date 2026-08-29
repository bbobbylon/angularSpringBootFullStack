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
  /**
   * Whether the service is still offered.
   *
   * Optional because the public catalog endpoint ({@code GET /customer/invoice/new}) returns only
   * active entries, so consumers of that path never need to check it. The admin catalog
   * ({@code GET /admin/services/list}) returns retired entries too, and there the flag is what
   * distinguishes them.
   *
   * Retirement is a flag rather than a delete because invoices copy a service's name and price
   * into their own line items — deleting the row would not corrupt past invoices, but it would
   * destroy the catalog's history and make reinstating an offering a retyping exercise.
   */
  active?: boolean;
  /**
   * The organization this entry is private to, or absent/undefined for a globally shared entry
   * visible to every caller regardless of organization membership.
   *
   * `undefined`/absent here means "shared with everyone", not "unowned" — every pre-existing
   * catalog entry and the public catalog are global by default. Immutable after creation: the admin
   * edit form has no field for it, matching the backend, which excludes it from
   * `PUT /admin/services/update/{id}`.
   */
  organizationId?: number;
}
