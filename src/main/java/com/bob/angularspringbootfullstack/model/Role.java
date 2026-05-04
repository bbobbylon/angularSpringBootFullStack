package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

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
}
