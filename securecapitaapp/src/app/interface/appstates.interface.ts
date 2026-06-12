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
  /**
   * Which second factor the MFA panel is collecting (SRS FR-MFA-2/4):
   * 'sms' renders the "code sent to your phone" copy and submits to the SMS verify
   * endpoint; 'totp' renders authenticator copy and submits the challenge + code to
   * {@code POST /user/verify/totp}. Absent when MFA is not in play.
   */
  mfaMethod?: 'sms' | 'totp';
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
  eventsTotalElements?: number;
  eventsTotalPages?: number;
  roles?: RolesInterface[];
  /**
   * Opaque first-factor proof returned by {@code POST /user/login} when the account
   * has an authenticator enrolled (FR-MFA-4): the password step succeeded, but tokens
   * are withheld until this challenge plus a TOTP/recovery code are presented to
   * {@code POST /user/verify/totp}. Absent on every other response.
   */
  challenge?: string;
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
/**
 * Reactive state shape for the user registration flow.
 *
 * Drives the {@link RegisterComponent} template: {@code dataState} controls the loading
 * spinner and error alert; {@code registerSuccess} switches between the form view and the
 * success confirmation screen; {@code message} carries the server's confirmation text.
 */
export interface RegisterStateInterface {
  error?: string;
  dataState: DataState;
  message?: string;
  registerSuccess?: boolean;
  registerError?: boolean;
}

/**
 * Reactive state shape for the password reset request flow.
 *
 * Drives the {@link ResetPasswordComponent} template: {@code dataState} controls the
 * loading spinner and error alert; {@code resetPasswordSuccess} switches to the success
 * confirmation screen; {@code message} carries the server's confirmation text shown after
 * the reset email is dispatched.
 */
export interface ResetPasswordStateInterface {
  error?: string;
  dataState: DataState;
  message?: string;
  resetPasswordSuccess?: boolean;
  resetPasswordError?: boolean;
}
export type AccountType = 'account' | 'password';

/**
 * Request body shape sent to {@code PUT /user/new/password} to complete the
 * forgot-password reset flow.
 *
 * The {@code userID} is obtained from the prior {@code GET /user/verify/password/{key}}
 * response — by the time the user submits the new-password form, the reset link
 * has already been validated and the user's ID is known, so neither the URL key
 * nor the password itself ever appears in the URL.
 *
 * Field names must match the Spring backend's {@code NewPasswordForm.java}
 * exactly, since Spring's {@code @RequestBody @Valid} binding uses JSON property
 * names as binding keys.
 */
export interface NewPasswordFormInterface {
  userID: number;
  newPassword: string;
  confirmPassword: string;
}

export interface VerifyStateInterface {
  dataState: DataState;
  verifySuccess?: boolean;
  error?: string;
  message?: string;
  title?: string;
  type?: AccountType;
}
