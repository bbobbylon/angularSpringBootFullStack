package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * Organization entity — the full {@code organizations} catalog row (SRS §4.6 FR-ORG, DB-4),
 * the tenant that {@code userorganizations} memberships and {@code Customer.organization_id}
 * attribute to.
 *
 * <p>Distinct from {@link OrganizationSummary}, which is a minimal id+name projection for
 * internal iteration (the scheduled report digest walks it, never exposed to the client as a
 * mutable entity): this is the row Organization CRUD
 * ({@code OrganizationController}, FUTURE-ENHANCEMENTS.md §3.2 "Self-service organization
 * management") reads and writes, including its lifecycle {@link #status}.
 *
 * <p>There is no hard delete: {@code status} ({@code 'ACTIVE'}/{@code 'INACTIVE'}, enforced by
 * {@code schema.sql}'s {@code CK_Organizations_Status}) is the retirement lever, matching
 * {@link com.bob.angularspringbootfullstack.query.OrganizationQuery}'s own scoping rule — "the
 * organization's own status is not consulted [there]; deactivating memberships is the
 * operational lever; retiring an org flips its rows inactive." Hard-deleting the row would
 * cascade-delete every {@code userorganizations} membership ({@code ON DELETE CASCADE}) and
 * orphan any {@code Customer.organization_id} still pointing at it — invisible to every
 * org-scoped admin thereafter, with no error to explain why.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class Organization {
    private Long id;
    @NotEmpty(message = "Organization name is required")
    private String name;
    private String status;
    /** Free-form description shown on the organization's profile panel; nullable. */
    private String description;
    /** Contact email for the organization itself, distinct from any one member's address; nullable. */
    private String contactEmail;
    /** Organization website URL; nullable. */
    private String website;
    /**
     * External tenant identifier, distinct from {@link #id} — admin-supplied, settable exactly
     * once (see {@code OrganizationService#setTenantUuid}); nullable until set.
     */
    private String tenantUuid;
    /**
     * The MFA methods this organization's members may enroll in, resolved from the stored CSV via
     * {@link com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod#parseCsv}. Empty means the
     * organization has not configured a policy — not "no methods allowed" — so no restriction is
     * imposed; see {@code OrganizationService#isMfaMethodAllowed}.
     */
    private Set<String> mfaAllowedMethods;
    /**
     * Free-form feature-flag labels attached to this organization. Nothing in the application reads
     * these yet — see FUTURE-ENHANCEMENTS.md for the known gap this deliberately leaves open rather
     * than inventing gates for behavior that doesn't exist.
     */
    private List<String> featureFlags;
    private LocalDateTime createdAt;
}
