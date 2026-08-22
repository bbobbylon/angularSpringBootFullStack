package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code PATCH /admin/organization/{id}/status} — the unscoped-tier-only
 * retirement lever for an organization (FUTURE-ENHANCEMENTS.md §3.2; see
 * {@code Organization}'s Javadoc for why status, not a hard delete, retires an organization).
 * {@code OrganizationServiceImpl} validates the value is {@code ACTIVE} or {@code INACTIVE}.
 */
@Data
public class OrganizationStatusForm {

    /**
     * The organization's new status: {@code "ACTIVE"} or {@code "INACTIVE"}.
     */
    @NotBlank(message = "Status is required")
    private String status;
}
