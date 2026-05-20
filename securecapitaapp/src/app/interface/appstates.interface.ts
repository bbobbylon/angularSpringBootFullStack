import { DataState } from '../enumeration/datastate.enum';
import { UserInterface } from './user.interface';
import { UserEventsInterface } from './user-events.interface';
import { RolesInterface } from './roles.interface';
import { CustomerInterface } from './customer.interface';
import { StatsInterface } from './stats.interface';
import { InvoiceInterface } from './invoice.interface';
import { ServicesInterface } from './services.interface';

/**
 * Represents the reactive state of the login flow.
 *
 * Each field is optional because only a subset is populated depending on the current
 * {@link DataState}: on success {@code loginSuccess} is set; on an MFA challenge only
 * {@code isUsingMfa} and {@code phone} are populated; on error only {@code error} is set.
 */
export interface LoginStateInterface {
  dataState: DataState;
  loginSuccess?: boolean;
  error?: string;
  message?: string;
  isUsingMfa?: boolean;
  phone?: string;
}
/**
 * Holds the authenticated user's full profile payload returned after a successful login.
 *
 * Both tokens are JWTs: {@code access_token} is short-lived and sent with every API
 * request as a Bearer header; {@code refresh_token} is long-lived and used to obtain a
 * new access token without re-authentication. {@code events} and {@code roles} are
 * lazy-loaded and may be absent in lightweight responses.
 */
export interface ProfileInterface {
  user?: UserInterface;
  access_token: string;
  refresh_token: string;
  events?: UserEventsInterface[];
  roles?: RolesInterface[];
}

/**
 * Mirrors the Spring Boot 3.3+ {@code Page<T>} JSON structure.
 *
 * In Spring Boot 3.3+, pagination metadata was moved into a nested {@code page} sub-object
 * rather than being top-level fields. This interface matches that serialized shape, so
 * Angular's HTTP client can deserialize paginated responses without a custom converter.
 */
export interface PageInterface<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

/**
 * The data payload carried by most customer lists API responses.
 *
 * Bundles the authenticated {@link UserInterface} with an optional paginated
 * {@link PageInterface} and optional statistics. Not every endpoint populates all
 * fields — for example, the create-customer endpoint returns {@code user} and a
 * single customer but no {@code page} or stats.
 */
export interface CustomerListDataInterface {
  user: UserInterface;
  page?: PageInterface<CustomerInterface>;
  stats?: StatsInterface;
  statsData?: StatsDataInterface;
}

/**
 * The data payload returned by the {@code GET /customer/stats} endpoint.
 *
 * Contains system-wide aggregated totals (total customers, invoices, and billed amount)
 * alongside the authenticated user. Consumed by {@link StatsComponent} to render the
 * summary panel at the top of the dashboard.
 */
export interface StatsDataInterface {
  user: UserInterface;
  stats: StatsInterface;
}

/**
 * The data payload returned by the {@code GET /customer/get/:id} endpoint.
 *
 * Pairs the authenticated {@link UserInterface} with the full record of a single
 * {@link CustomerInterface}. The backend places the customer under the key
 * {@code "customers"} (plural) in the response map — this interface mirrors that name
 * so Angular's HTTP client deserializes it correctly.
 *
 * Consumed by {@link CustomerDetailsComponent} to render the customer detail view.
 */
export interface CustomerStateInterface {
  user: UserInterface;
  customers: CustomerInterface;
}

/**
 * The data payload returned by {@code GET /customer/invoice/new}.
 *
 * Returns the authenticated user, the full unpaginated customer list (for the
 * customer dropdown), and the full services catalog (for the service line-item
 * dropdown) so the new-invoice form can be fully populated from a single request.
 */
export interface NewInvoiceDataInterface {
  user: UserInterface;
  customers?: CustomerInterface[];
  availableServices?: ServicesInterface[];
}

/**
 * The data payload returned by {@code GET /customer/invoice/list}.
 *
 * Bundles the authenticated user with a paginated page of invoices.
 */
export interface InvoiceListDataInterface {
  user: UserInterface;
  invoices?: PageInterface<InvoiceInterface>;
}

/**
 * The data payload returned by {@code GET /customer/invoice/get/:id}.
 *
 * Contains the authenticated user, the matching invoice, and the customer the
 * invoice belongs to — all three are returned in a single response so the
 * invoice detail view can render without a second API call.
 */
export interface CustomerInvoiceUserInterface {
  user: UserInterface;
  invoice: InvoiceInterface;
  customer: CustomerInterface;
}
export interface RegisterStateInterface {
  error?: string;
  dataState: DataState;
  message?: string;
  registerSuccess?: boolean;
  registerError?: boolean;
}
