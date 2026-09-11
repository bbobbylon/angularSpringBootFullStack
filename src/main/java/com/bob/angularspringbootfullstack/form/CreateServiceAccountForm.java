package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /admin/serviceaccounts} (FUTURE-ENHANCEMENTS.md §3.1, P2-3
 * Option A) — creating a new service account.
 * <p>
 * {@code role} is checked further in {@code ServiceAccountServiceImpl#create} against
 * {@code RoleType#canAssign}: bean validation here only guarantees the field is present, since
 * the tier-ceiling check needs the creating admin's own role, which this form does not carry.
 */
@Data
public class CreateServiceAccountForm {

    /**
     * A caller-chosen display name, e.g. {@code "CI pipeline"}. Also seeds the account's
     * synthetic email (see {@code ServiceAccountServiceImpl#buildSyntheticEmail}).
     */
    @NotBlank(message = "Name is required")
    private String name;

    /**
     * The role to assign, e.g. {@code ROLE_MODERATOR}. Must not outrank the creating admin's own
     * role.
     */
    @NotBlank(message = "Role is required")
    private String role;
}
