package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for the Role CRUD business rules {@code RoleRepoImpl} deliberately does not
 * own, per this codebase's "business logic belongs in the service layer" convention: name-format
 * validation on create, and refusing to delete a built-in
 * {@link com.bob.angularspringbootfullstack.enumeration.RoleType} role.
 *
 * <h3>The property this suite exists for</h3>
 * The database's {@code ON DELETE RESTRICT} on {@code userroles.role_id} only stops deleting a
 * role someone currently holds — it says nothing about a built-in role nobody happens to hold
 * <em>right now</em> (e.g. {@code ROLE_ORGANIZATION_ADMIN} on a deployment with no organization
 * admins yet). Deleting one anyway would silently strand {@link
 * com.bob.angularspringbootfullstack.enumeration.RoleType}'s compile-time tier ladder for that
 * name with nothing in the database backing it, which is why {@link RoleServiceImpl#deleteRole}
 * checks {@link com.bob.angularspringbootfullstack.enumeration.RoleType#from} before ever
 * reaching the repository.
 *
 * <p>{@link #getAllRolesStampsAssignability()} also locks in the flag the frontend uses to show
 * "created, not yet assignable" for a catalog-only role — a UI hint only, since {@link
 * com.bob.angularspringbootfullstack.enumeration.RoleType#canAssign} already fails closed on an
 * unrecognized name regardless of what this flag says.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleServiceImplTest {

    @Mock
    private RoleRepo<Role> roleRepo;

    @InjectMocks
    private RoleServiceImpl roleService;

    @Test
    @DisplayName("a well-formed name is normalized to uppercase and delegated to the repo")
    void createRoleNormalizesNameAndDelegates() {
        Role toCreate = Role.builder().name("role_billing_reviewer").permission("READ:CUSTOMER").build();
        when(roleRepo.create(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role created = roleService.createRole(toCreate);

        assertThat(created.getName()).isEqualTo("ROLE_BILLING_REVIEWER");
        verify(roleRepo).create(toCreate);
    }

    @Test
    @DisplayName("a name that doesn't look like ROLE_SOMETHING is refused before the repo is touched")
    void createRoleRejectsMalformedName() {
        Role toCreate = Role.builder().name("billing-reviewer").permission("READ:CUSTOMER").build();

        assertThatThrownBy(() -> roleService.createRole(toCreate)).isInstanceOf(ApiException.class);

        verify(roleRepo, never()).create(any(Role.class));
    }

    @Test
    @DisplayName("a blank permission string is refused before the repo is touched")
    void createRoleRejectsBlankPermission() {
        Role toCreate = Role.builder().name("ROLE_BILLING_REVIEWER").permission(" ").build();

        assertThatThrownBy(() -> roleService.createRole(toCreate)).isInstanceOf(ApiException.class);

        verify(roleRepo, never()).create(any(Role.class));
    }

    @Test
    @DisplayName("updateRolePermission rejects a blank permission before the repo is touched")
    void updateRolePermissionRejectsBlank() {
        assertThatThrownBy(() -> roleService.updateRolePermission(5L, ""))
                .isInstanceOf(ApiException.class);

        verify(roleRepo, never()).update(any(Long.class), any(Role.class));
    }

    @Test
    @DisplayName("updateRolePermission delegates to the repo with only the permission set")
    void updateRolePermissionDelegates() {
        Role updated = Role.builder().id(5L).name("ROLE_MODERATOR").permission("READ:CUSTOMER").build();
        when(roleRepo.update(eq(5L), any(Role.class))).thenReturn(updated);

        Role result = roleService.updateRolePermission(5L, "READ:CUSTOMER");

        assertThat(result).isEqualTo(updated);
        verify(roleRepo).update(eq(5L), any(Role.class));
    }

    @Test
    @DisplayName("a built-in RoleType role cannot be deleted, even if currently unassigned")
    void deleteRoleRefusesBuiltInRole() {
        when(roleRepo.get(6L)).thenReturn(Role.builder().id(6L).name("ROLE_MODERATOR").permission("x").build());

        assertThatThrownBy(() -> roleService.deleteRole(6L)).isInstanceOf(ApiException.class);

        verify(roleRepo, never()).delete(any(Long.class));
    }

    @Test
    @DisplayName("a catalog-only role with no RoleType constant can be deleted")
    void deleteRoleAllowsCatalogOnlyRole() {
        when(roleRepo.get(7L)).thenReturn(Role.builder().id(7L).name("ROLE_BILLING_REVIEWER").permission("x").build());

        roleService.deleteRole(7L);

        verify(roleRepo).delete(7L);
    }

    @Test
    @DisplayName("getAllRoles stamps assignable=true only for names RoleType recognizes")
    void getAllRolesStampsAssignability() {
        Role known = Role.builder().id(1L).name("ROLE_USER").permission("x").build();
        Role unknown = Role.builder().id(2L).name("ROLE_BILLING_REVIEWER").permission("x").build();
        when(roleRepo.list()).thenReturn(List.of(known, unknown));

        Collection<Role> roles = roleService.getAllRoles();

        assertThat(roles).filteredOn(role -> role.getName().equals("ROLE_USER"))
                .singleElement().extracting(Role::isAssignable).isEqualTo(true);
        assertThat(roles).filteredOn(role -> role.getName().equals("ROLE_BILLING_REVIEWER"))
                .singleElement().extracting(Role::isAssignable).isEqualTo(false);
    }
}
