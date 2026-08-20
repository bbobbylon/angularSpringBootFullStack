package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * Role entity representing a user role/authority in the system.
 *
 * <p>This model maps to the {@code roles} table and provides the permission string that is
 * converted into Spring Security {@code GrantedAuthority} instances.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class Role {
    private Long id;
    @NotEmpty(message = "Role name is required")
    private String name;
    private String permission;

    /**
     * When the CALLING user's assignment to this role expires, or {@code null} for an
     * unlimited assignment (the default). Only ever populated by
     * {@link com.bob.angularspringbootfullstack.repo.repoimpl.RoleRepoImpl#getRoleByUserId}
     * from the {@code userroles.expires_at} column it joins in — this is a per-assignment
     * fact, not a property of the role catalog row itself, so it is left {@code null} on
     * every {@link com.bob.angularspringbootfullstack.repo.RoleRepo#list()} entry.
     */
    private LocalDateTime expiresAt;

    /**
     * Whether {@link com.bob.angularspringbootfullstack.enumeration.RoleType} has a
     * compile-time constant matching this role's name — i.e. whether an administrator can
     * actually assign it to anyone right now. Stamped by
     * {@link com.bob.angularspringbootfullstack.service.serviceimpl.RoleServiceImpl#getAllRoles()}
     * for the catalog the frontend renders; a role created purely through the Role CRUD
     * catalog has no entry in that enum until code catches up, so it is created but inert
     * until a redeploy adds it — {@link com.bob.angularspringbootfullstack.enumeration.RoleType#canAssign}
     * already fails closed on an unrecognized name, so this flag is a UI hint only, not a
     * security boundary the frontend could bypass by lying about it.
     */
    private boolean assignable;
}
