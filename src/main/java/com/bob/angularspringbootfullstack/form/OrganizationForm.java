package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /admin/organization} (create) and
 * {@code PATCH /admin/organization/{id}/name} (rename) — both unscoped-tier-only Organization
 * CRUD operations (FUTURE-ENHANCEMENTS.md §3.2). Reused across both since each carries the same
 * single field; {@code OrganizationServiceImpl} owns the non-blank and uniqueness validation.
 */
@Data
public class OrganizationForm {

    /**
     * The organization's display name, e.g. {@code "Acme Partners"}.
     */
    @NotBlank(message = "Organization name is required")
    private String name;
}
