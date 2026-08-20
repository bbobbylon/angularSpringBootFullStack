package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.form.RoleForm;
import com.bob.angularspringbootfullstack.form.RolePermissionForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Role catalog administration (Role CRUD — POST-SUBMISSION-UPGRADES.md).
 * <p>
 * Distinct from {@link AdminUserController#updateUserRole}, which reassigns an <b>existing</b>
 * catalog role to a user — these three endpoints instead create, edit, or delete the catalog
 * rows themselves. That is a materially bigger blast radius than reassigning a user's role (it
 * changes what a role means or whether it exists at all, for every user who holds or might ever
 * be given it), so it is deliberately gated <b>tighter</b> than the rest of {@code /admin/**}:
 * where reassignment only needs the {@code UPDATE:ROLE} authority, catalog mutation additionally
 * requires the caller's role to be exactly {@code ROLE_APPLICATION_ADMIN} — the single highest
 * tier on {@link RoleType}'s ladder — checked explicitly in {@link #requireApplicationAdmin}
 * since permission strings alone (what {@code UPDATE:ROLE} tests) cannot express "and only this
 * one role".
 * <p>
 * Authorization is enforced at two levels, per FR-RBAC-2, matching {@link AdminUserController}'s
 * convention:
 * <ul>
 *   <li><b>URL level</b> — {@code SecurityConfig} requires {@code UPDATE:ROLE} for
 *       {@code /admin/role/**}.</li>
 *   <li><b>Method level</b> — {@link PreAuthorize} repeats the {@code UPDATE:ROLE} requirement,
 *       and {@link #requireApplicationAdmin} additionally narrows it to the top tier — a check
 *       {@code @PreAuthorize} alone cannot express against this application's authority-string
 *       model.</li>
 * </ul>
 * <p>
 * A role created here has no {@link RoleType} constant until a redeploy adds one, so it is
 * created but not yet assignable — {@link RoleType#canAssign} already fails closed on an
 * unrecognized name, so this is enforced automatically by code that predates this controller,
 * not by anything added here. {@link Role#isAssignable()}, stamped by
 * {@link RoleService#getAllRoles()}, is only a UI hint that flag exists for.
 */
@RestController
@RequestMapping(path = "/admin/role")
@RequiredArgsConstructor
@Slf4j
public class RoleController {

    private final RoleService roleService;

    /**
     * Creates a new role catalog row (Role CRUD — create).
     *
     * @param authentication the calling administrator's authentication
     * @param form           the validated {name, permission} payload
     * @return 200 OK with the created role and the refreshed catalog
     */
    @PreAuthorize("hasAuthority('UPDATE:ROLE')")
    @PostMapping
    public ResponseEntity<HttpResponse> createRole(Authentication authentication, @RequestBody @Valid RoleForm form) {
        requireApplicationAdmin(authentication);
        Role created = roleService.createRole(Role.builder().name(form.getName()).permission(form.getPermission()).build());
        log.info("Application admin '{}' created role '{}'", getAuthenticatedUser(authentication).getEmail(), created.getName());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("role", created, "roles", roleService.getAllRoles()))
                        .message("Role created successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates an existing role's permission string (Role CRUD — edit). The name is immutable;
     * see {@link RolePermissionForm}'s Javadoc for why.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the id of the role to update
     * @param form           the validated {permission} payload
     * @return 200 OK with the updated role and the refreshed catalog
     */
    @PreAuthorize("hasAuthority('UPDATE:ROLE')")
    @PatchMapping("/{id}")
    public ResponseEntity<HttpResponse> updateRolePermission(Authentication authentication,
                                                              @PathVariable Long id,
                                                              @RequestBody @Valid RolePermissionForm form) {
        requireApplicationAdmin(authentication);
        Role updated = roleService.updateRolePermission(id, form.getPermission());
        log.info("Application admin '{}' updated permissions for role '{}' (id={})",
                getAuthenticatedUser(authentication).getEmail(), updated.getName(), id);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("role", updated, "roles", roleService.getAllRoles()))
                        .message("Role updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Deletes a role from the catalog (Role CRUD — delete). Refused for any of the seven
     * built-in {@link RoleType} roles, and for a role any user currently holds — see
     * {@link RoleService#deleteRole} for both guards.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the id of the role to delete
     * @return 200 OK with the refreshed catalog
     */
    @PreAuthorize("hasAuthority('UPDATE:ROLE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<HttpResponse> deleteRole(Authentication authentication, @PathVariable Long id) {
        requireApplicationAdmin(authentication);
        roleService.deleteRole(id);
        log.info("Application admin '{}' deleted role id {}", getAuthenticatedUser(authentication).getEmail(), id);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("roles", roleService.getAllRoles()))
                        .message("Role deleted successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Refuses role-catalog mutations from anyone below the top tier. Unlike
     * {@code AdminUserController#requireAssignableTier} (which bounds WHICH role an admin may
     * hand to someone else, relative to their own tier), this is an absolute floor: no tier below
     * {@code ROLE_APPLICATION_ADMIN} may touch the catalog at all, per the design decision in
     * {@code FUTURE-ENHANCEMENTS.md} §3.2. The denial names no account data (NFR-SEC-7).
     *
     * @param authentication the calling administrator's authentication
     * @throws AccessDeniedException if the caller does not hold {@code ROLE_APPLICATION_ADMIN}
     */
    private static void requireApplicationAdmin(Authentication authentication) {
        UserDTO caller = getAuthenticatedUser(authentication);
        if (!RoleType.ROLE_APPLICATION_ADMIN.name().equals(caller.getRoleName())) {
            log.warn("Non-application-admin '{}' (role {}) denied a role-catalog operation", caller.getEmail(), caller.getRoleName());
            throw new AccessDeniedException("Only an application administrator can manage the role catalog.");
        }
    }
}
