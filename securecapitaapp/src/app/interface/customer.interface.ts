import { InvoiceInterface } from './invoice.interface';

/**
 * Represents a customer record as returned by the backend API.
 *
 * Mirrors the {@code Customer} JPA entity. The {@code invoices} field is
 * optional because not all endpoints include the full invoice list.
 */
export interface CustomerInterface {
  /** Auto-generated unique identifier. */
  id: number;
  /** Full name or business name of the customer. */
  name: string;
  /** Primary contact email address. */
  email: string;
  /** Physical or mailing address. */
  address: string;
  /** Customer category (e.g., 'INDIVIDUAL', 'CORPORATE'). */
  type: string;
  /** Account standing (e.g., 'ACTIVE', 'INACTIVE', 'BANNED', 'PENDING'). */
  status: string;
  /** URL pointing to the customer's profile picture or business logo. */
  imageUrl: string;
  /** Primary contact phone number. */
  phoneNumber: string;
  /** Timestamp of when the customer record was first created. */
  createdAt: Date;
  /** All invoices associated with this customer — omitted on list endpoints. */
  invoices?: InvoiceInterface[];
}
