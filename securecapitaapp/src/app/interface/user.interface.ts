/**
 * Shape of the user object returned by the backend's {@code UserDTO}.
 *
 * Field names must exactly match the JSON keys Jackson serialises — a mismatch
 * means the field will always be {@code undefined} in Angular.  One non-obvious
 * case: Lombok generates {@code isNotLocked()} for a {@code boolean isNotLocked}
 * field, and Jackson strips the {@code is} prefix from boolean getters, so the
 * JSON key is {@code "notLocked"} (not {@code "isNotLocked"}).
 */
export interface UserInterface {
  id: number;
  username: string;
  email: string;
  phoneNumber: string;
  firstName?: string;
  lastName?: string;
  address?: string;
  title?: string;
  bio?: string;
  imageUrl?: string;
  enabled: boolean;
  notLocked: boolean;
  using2FA: boolean;
  createdAt: Date;
  roleName: string;
  permissions: string;
}
