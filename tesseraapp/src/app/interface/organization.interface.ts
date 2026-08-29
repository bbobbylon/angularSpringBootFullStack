import { UserInterface } from './user.interface';
import { StatsInterface } from './stats.interface';
import { CustomerInterface } from './customer.interface';
import { InvoiceInterface } from './invoice.interface';

/**
 * Organization catalog row — mirrors the backend's {@code Organization} model
 * (SRS §4.6 FR-ORG, FUTURE-ENHANCEMENTS.md §3.2 "Self-service organization management").
 *
 * There is no hard delete: {@code status} ({@code 'ACTIVE'}/{@code 'INACTIVE'}) is the
 * retirement lever — the backend model's Javadoc explains why a hard delete would cascade
 * away every membership row and orphan any {@code Customer.organization_id} still pointing
 * at the dead organization.
 *
 * {@code description}/{@code contactEmail}/{@code website} are the profile fields added by the
 * dashboard revamp (2026-08-22) — all optional, editable only by an unscoped tier via
 * {@code OrganizationService.updateOrganizationProfile$}.
 *
 * {@code tenantUuid}/{@code mfaAllowedMethods}/{@code featureFlags} are the org-setup fields
 * (2026-08-28) — mirrors the backend's {@code Organization} model additions. {@code tenantUuid}
 * is settable exactly once ({@code OrganizationService.setTenantUuid$}); {@code mfaAllowedMethods}
 * empty means "no policy configured" (every method allowed), not "none allowed"; both are edited
 * together via {@code OrganizationService.updateOrganizationSettings$}.
 */
export interface OrganizationInterface {
  id?: number;
  name?: string;
  status?: string;
  createdAt?: string;
  description?: string;
  contactEmail?: string;
  website?: string;
  tenantUuid?: string;
  mfaAllowedMethods?: string[];
  featureFlags?: string[];
}

/**
 * The data payload returned by the Organization CRUD + membership endpoints
 * ({@code GET/POST/PATCH/DELETE /admin/organization/**}).
 *
 * {@code organization} is the created/renamed/status-updated row, present on every mutation
 * response except add/remove-member. {@code organizations} is the caller's refreshed in-scope
 * catalog, returned by the list endpoint and by every catalog mutation. {@code members} is
 * populated only by the members-list endpoint — the roster an admin picks a member to remove
 * from, or checks before adding one. {@code orgRoles} rides alongside {@code members}, keyed by
 * user id, since a member's per-organization capacity ({@code userorganizations.org_role}) is
 * not part of {@code UserInterface} — it is only meaningful in the context of the one
 * organization being viewed (per-organization roles, 2026-08-26/2026-08-27, FUTURE-ENHANCEMENTS.md
 * §3.2). {@code stats}/{@code events}/{@code totalEvents}/{@code invite}/{@code invites} are
 * populated only by their respective dashboard-revamp endpoints (2026-08-22) — the same
 * one-envelope-many-optional-keys shape {@code members} already established for this interface.
 * {@code customers}/{@code invoices} are populated only by the org setup read endpoints
 * ({@code GET /admin/organization/:id/customers}/{@code /invoices}, 2026-08-28) — an invoice is
 * scoped to an organization only through the customer it belongs to. {@code config}/{@code domains}/
 * {@code domain} are populated only by the per-organization SSO endpoints
 * ({@code GET/PUT/PATCH/DELETE /admin/organization/:id/sso} and its {@code /domains} sub-resource,
 * FUTURE-ENHANCEMENTS.md §3.1 Stage 1) — {@code config} is nullable even on success, since a
 * not-yet-configured organization legitimately has no IdP row.
 */
export interface OrganizationCatalogInterface {
  organization?: OrganizationInterface;
  organizations?: OrganizationInterface[];
  members?: UserInterface[];
  orgRoles?: Record<number, string>;
  stats?: OrganizationStatsInterface;
  events?: OrganizationEventInterface[];
  totalEvents?: number;
  invite?: OrganizationInviteInterface;
  invites?: OrganizationInviteInterface[];
  customers?: CustomerInterface[];
  invoices?: InvoiceInterface[];
  config?: OrganizationIdentityProviderInterface;
  domains?: OrgSsoDomainInterface[];
  domain?: OrgSsoDomainInterface;
}

/** The data payload of {@code GET /user/organization/invite/:code} (invite preview). */
export interface OrganizationInvitePreviewInterface {
  organizationName?: string;
}

/**
 * One organization's KPI row for the dashboard-style Organizations page's card grid
 * ({@code GET /admin/organization/:id/stats}) — mirrors the backend's {@code OrganizationStats}.
 * {@code stats}/{@code statusBreakdown} are the exact same shapes the Analytics summary already
 * returns, just narrowed to this one organization.
 */
export interface OrganizationStatsInterface {
  memberCount: number;
  stats: StatsInterface;
  statusBreakdown: Record<string, number>;
}

/**
 * One organization's audit-trail row ({@code GET /admin/organization/:id/events}) — mirrors the
 * backend's {@code OrganizationEvent}. {@code actorEmail} is nullable: an event whose acting
 * administrator's account was later deleted still keeps its row (the FK is {@code ON DELETE
 * SET NULL}), so the activity log renders "system" rather than dropping the entry.
 */
export interface OrganizationEventInterface {
  id?: number;
  type?: string;
  description?: string;
  actorEmail?: string;
  detail?: string;
  createdAt?: string;
}

/**
 * A pending, single-use organization invite ({@code GET/POST/DELETE
 * /admin/organization/:id/invites}) — mirrors the backend's {@code OrganizationInvite}.
 * {@code code} is the redeemable token embedded in the shared join link
 * ({@code /organizations/join/:code}).
 */
export interface OrganizationInviteInterface {
  id?: number;
  organizationId?: number;
  invitedByEmail?: string;
  code?: string;
  roleName?: string;
  expirationDate?: string;
  createdAt?: string;
}

/**
 * One organization's external IdP configuration for enterprise single sign-on
 * ({@code GET/PUT/PATCH/DELETE /admin/organization/:id/sso}) — mirrors the backend's
 * {@code OrganizationIdentityProvider} (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external
 * IdP", Stage 1). The client secret itself is never returned by any endpoint: {@code
 * secretConfigured} is a presence flag only, the same non-enumeration-of-secrets discipline this
 * app already applies to passwords/tokens — a form editing an existing configuration must treat a
 * blank secret field as "keep the current one," never as "clear it." {@code samlMetadataUri} is
 * populated only when {@code protocol} is {@code "SAML"} (Stage 3); {@code oidcIssuerUri}/
 * {@code oidcClientId} only when it is {@code "OIDC"} — the backend nulls out the other protocol's
 * fields whenever an organization switches between them.
 */
export interface OrganizationIdentityProviderInterface {
  id?: number;
  organizationId?: number;
  protocol?: string;
  displayName?: string;
  status?: string;
  oidcIssuerUri?: string;
  oidcClientId?: string;
  secretConfigured?: boolean;
  samlMetadataUri?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * One email domain routed to an organization's SSO configuration
 * ({@code GET/POST/DELETE /admin/organization/:id/sso/domains}) — mirrors the backend's
 * {@code OrgSsoDomain}. Domain uniqueness is global ({@code UQ_OrgSsoDomains_Domain}), so a domain
 * claimed by one organization can never simultaneously be claimed by another.
 */
export interface OrgSsoDomainInterface {
  id?: number;
  organizationId?: number;
  domain?: string;
  createdAt?: string;
}
