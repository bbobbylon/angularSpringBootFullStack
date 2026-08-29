package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for {@code POST /admin/organization/{id}/sso/domains} — claiming an email domain
 * for an organization's SSO routing (FUTURE-ENHANCEMENTS.md §3.1's email-domain discovery UX).
 */
@Data
public class OrgSsoDomainForm {

    @NotBlank(message = "Domain is required")
    @Size(max = 255, message = "Domain must be 255 characters or fewer")
    private String domain;
}
