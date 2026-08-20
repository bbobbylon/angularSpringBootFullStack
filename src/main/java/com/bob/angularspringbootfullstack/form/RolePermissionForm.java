package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code PATCH /admin/role/{id}} (Role CRUD — edit), gated to
 * {@code ROLE_APPLICATION_ADMIN}.
 * <p>
 * Carries only the permission string: a role's name is immutable once created, since
 * {@link com.bob.angularspringbootfullstack.enumeration.RoleType} ties its compile-time tier
 * ladder to the exact name and renaming a row out from under it would strand anyone currently
 * holding that role.
 */
@Data
public class RolePermissionForm {

    /**
     * The role's new comma-delimited permission string, e.g. {@code "READ:USER,UPDATE:USER"}.
     */
    @NotBlank(message = "Permission string is required")
    private String permission;
}
