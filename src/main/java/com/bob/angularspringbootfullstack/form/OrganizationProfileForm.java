package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for {@code PATCH /admin/organization/{id}/profile} — the unscoped-tier-only
 * organization profile/settings editor (dashboard revamp, 2026-08-22). Every field is optional:
 * a blank or omitted value clears that column rather than failing validation, since a profile is
 * filled in incrementally and there is no required field on an organization beyond its name
 * (already covered by {@link OrganizationForm}).
 */
@Data
public class OrganizationProfileForm {

    /** Free-form description shown on the organization's profile panel. */
    @Size(max = 500, message = "Description must be 500 characters or fewer")
    private String description;

    /** Contact email for the organization itself, distinct from any one member's address. */
    @Email(message = "Contact email must be a valid email address")
    @Size(max = 255, message = "Contact email must be 255 characters or fewer")
    private String contactEmail;

    /** Organization website URL. */
    @Size(max = 255, message = "Website must be 255 characters or fewer")
    private String website;
}
