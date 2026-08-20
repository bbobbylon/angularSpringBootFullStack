package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /admin/role} (Role CRUD — create), gated to
 * {@code ROLE_APPLICATION_ADMIN}.
 * <p>
 * {@code name} is validated further in {@code RoleServiceImpl#createRole} against the
 * {@code ROLE_SOMETHING} naming convention every seeded
 * {@link com.bob.angularspringbootfullstack.enumeration.RoleType} constant already follows —
 * bean validation here only guarantees the field is present, since the shape check needs a
 * regex the service layer owns.
 */
@Data
public class RoleForm {

    /**
     * The new role's name, e.g. {@code ROLE_BILLING_REVIEWER}. Normalized to uppercase and
     * checked against the {@code ROLE_} naming convention by the service layer.
     */
    @NotBlank(message = "Role name is required")
    private String name;

    /**
     * The comma-delimited permission string Spring Security will split into authorities for
     * anyone holding this role, e.g. {@code "READ:USER,READ:CUSTOMER"}.
     */
    @NotBlank(message = "Permission string is required")
    private String permission;
}
