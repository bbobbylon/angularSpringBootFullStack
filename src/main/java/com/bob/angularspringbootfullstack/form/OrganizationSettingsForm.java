package com.bob.angularspringbootfullstack.form;

import lombok.Data;

import java.util.List;

/**
 * Request body for {@code PATCH /admin/organization/{id}/settings} — the unscoped-tier-only editor
 * for an organization's enforcement-relevant settings (MFA-allowed-methods policy, feature-flag
 * labels), as distinct from {@link OrganizationProfileForm}'s display-only fields.
 *
 * <p>Both fields are independently nullable, mirroring {@link OrganizationProfileForm}: a
 * {@code null} field leaves that setting untouched, an empty list clears it. This lets the client
 * update the MFA policy without disturbing feature flags (or vice versa) in one request.
 */
@Data
public class OrganizationSettingsForm {

    /**
     * The MFA methods this organization's members may enroll in, by
     * {@link com.bob.angularspringbootfullstack.enumeration.OrgMfaMethod} name. {@code null} leaves
     * the current policy unchanged; an empty list clears it back to "no policy configured" (every
     * method allowed, not none).
     */
    private List<String> mfaAllowedMethods;

    /**
     * Free-form feature-flag labels. {@code null} leaves the current flags unchanged; an empty list
     * clears them.
     */
    private List<String> featureFlags;
}
