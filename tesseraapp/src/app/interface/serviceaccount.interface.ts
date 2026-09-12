import { UserInterface } from './user.interface';

/**
 * The data payload returned by {@code GET/POST /admin/serviceaccounts} and
 * {@code DELETE /admin/serviceaccounts/:id} (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 *
 * <p>There is no dedicated "ServiceAccount" DTO on the backend — {@code ServiceAccountService}
 * returns the exact same {@code UserDTO} the human-user endpoints do, since a service account is
 * an ordinary {@code users} row with {@code origin = 'SERVICE_ACCOUNT'}
 * ({@code UserInterface.userType}). {@code serviceAccount} is the row created or acted on by a
 * mutation; {@code serviceAccounts} is the full list, refreshed after every mutation the same way
 * {@code ServicesListDataInterface} refreshes the services catalog.
 */
export interface ServiceAccountsDataInterface {
  serviceAccount?: UserInterface;
  serviceAccounts?: UserInterface[];
}
