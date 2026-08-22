import { UserInterface } from './user.interface';

/**
 * Organization catalog row — mirrors the backend's {@code Organization} model
 * (SRS §4.6 FR-ORG, FUTURE-ENHANCEMENTS.md §3.2 "Self-service organization management").
 *
 * There is no hard delete: {@code status} ({@code 'ACTIVE'}/{@code 'INACTIVE'}) is the
 * retirement lever — the backend model's Javadoc explains why a hard delete would cascade
 * away every membership row and orphan any {@code Customer.organization_id} still pointing
 * at the dead organization.
 */
export interface OrganizationInterface {
  id?: number;
  name?: string;
  status?: string;
  createdAt?: string;
}

/**
 * The data payload returned by the Organization CRUD + membership endpoints
 * ({@code GET/POST/PATCH/DELETE /admin/organization/**}).
 *
 * {@code organization} is the created/renamed/status-updated row, present on every mutation
 * response except add/remove-member. {@code organizations} is the caller's refreshed in-scope
 * catalog, returned by the list endpoint and by every catalog mutation. {@code members} is
 * populated only by the members-list endpoint — the roster an admin picks a member to remove
 * from, or checks before adding one.
 */
export interface OrganizationCatalogInterface {
  organization?: OrganizationInterface;
  organizations?: OrganizationInterface[];
  members?: UserInterface[];
}
