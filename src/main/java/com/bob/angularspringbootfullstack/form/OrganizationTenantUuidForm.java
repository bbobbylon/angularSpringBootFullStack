package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code PATCH /admin/organization/{id}/tenant-uuid} — sets an organization's
 * external tenant identifier the one time it has none. {@code OrganizationServiceImpl} refuses the
 * write outright if the organization already has a tenant UUID; there is no "change" endpoint,
 * because the identifier is meant to be settable exactly once.
 */
@Data
public class OrganizationTenantUuidForm {

    /** The UUID to set, e.g. {@code "3fa85f64-5717-4562-b3fc-2c963f66afa6"}. Must be a valid UUID. */
    @NotBlank(message = "Tenant UUID is required")
    private String tenantUuid;
}
