package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request body for {@code POST /admin/organization} (create) and
 * {@code PATCH /admin/organization/{id}/name} (rename) — both unscoped-tier-only Organization
 * CRUD operations (FUTURE-ENHANCEMENTS.md §3.2). Reused across both, since rename only ever reads
 * {@link #name}; every field below it is create-only, letting an admin fill in an organization's
 * full setup — profile, tenant UUID, MFA policy, feature flags, attached customers, and a creation
 * confirmation email — in the single call that creates it, rather than a create-then-several-PATCHes
 * dance. {@code OrganizationServiceImpl} owns non-blank/uniqueness/format validation beyond what
 * annotations here can express (e.g. the UUID format check, the org-name uniqueness check).
 */
@Data
public class OrganizationForm {

    /**
     * The organization's display name, e.g. {@code "Acme Partners"}.
     */
    @NotBlank(message = "Organization name is required")
    private String name;

    /** Free-form description shown on the organization's profile panel; create-only, optional. */
    @Size(max = 500, message = "Description must be 500 characters or fewer")
    private String description;

    /** Contact email for the organization itself; create-only, optional. */
    @Email(message = "Contact email must be a valid email address")
    @Size(max = 255, message = "Contact email must be 255 characters or fewer")
    private String contactEmail;

    /** Organization website URL; create-only, optional. */
    @Size(max = 255, message = "Website must be 255 characters or fewer")
    private String website;

    /**
     * External tenant identifier, admin-supplied; create-only, optional. Must be a valid UUID
     * string if supplied — {@code OrganizationServiceImpl} validates the format, since a
     * {@code @Pattern} annotation would duplicate rather than reuse {@link java.util.UUID#fromString}.
     * Settable exactly once: if omitted here, it may be set later via
     * {@code PATCH /admin/organization/{id}/tenant-uuid}, but never overwritten once non-null.
     */
    private String tenantUuid;

    /**
     * The MFA methods this organization's members may enroll in, by
     * {@link com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod} name; create-only,
     * optional. Omitted or empty means no policy is configured — every method remains allowed,
     * not none.
     */
    private List<String> mfaAllowedMethods;

    /**
     * Free-form feature-flag labels; create-only, optional. Nothing in the application reads these
     * yet — see FUTURE-ENHANCEMENTS.md.
     */
    private List<String> featureFlags;

    /**
     * Existing, currently-unassigned customers to attach to this organization at creation time —
     * their invoices come along implicitly, since an invoice belongs to a customer, not directly to
     * an organization; create-only, optional.
     */
    private List<Long> customerIds;

    /**
     * When {@code true}, sends the creating administrator a confirmation email once the
     * organization is created; create-only, optional, defaults to {@code false}.
     */
    private boolean sendConfirmationEmail;
}
