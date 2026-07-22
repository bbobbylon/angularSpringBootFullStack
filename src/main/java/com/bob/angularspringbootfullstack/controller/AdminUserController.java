package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.form.SettingsForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

import static com.bob.angularspringbootfullstack.enumeration.EventType.ACCOUNT_SETTINGS_UPDATE;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ROLE_UPDATE;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ORGANIZATION_ADMIN;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Administrative user-management endpoints (SRS §4.9, FR-ADMIN-1..5).
 *
 * <p>This controller is the <b>only</b> place where one user may change another user's
 * role or account state. The former self-service role endpoint on {@link UserController}
 * was removed to close the FR-RBAC-4 privilege-escalation gap; the frontend's profile
 * Authorization tab is now read-only and the admin dashboard calls these endpoints
 * instead.
 *
 * <p>Authorization is enforced at two levels, per FR-RBAC-2:
 * <ul>
 *   <li><b>URL level</b> — SecurityConfig requires {@code UPDATE:USER} or
 *       {@code UPDATE:ROLE} for anything under {@code /admin/**}, with stricter
 *       per-route matchers for the two PATCH operations.</li>
 *   <li><b>Method level</b> — {@link PreAuthorize} on each mutating endpoint repeats the
 *       requirement, so a future routing change cannot silently reopen the gap.</li>
 * </ul>
 *
 * <p>Both mutating endpoints refuse to operate on the calling administrator's own
 * account: self-role-change is exactly the FR-RBAC-4 hole, and self-disabling/locking
 * is an accidental-lockout footgun. Changes to other users are recorded as audit events
 * against the <b>target</b> user (FR-ADMIN-3/4) so the action shows up in that user's
 * activity history.
 *
 * <p>Organization scoping (FR-ORG-1..3) is enforced on every endpoint: when the caller
 * holds {@code ROLE_ORGANIZATION_ADMIN}, the directory shrinks to users sharing an
 * active organization with them, and any action on an out-of-scope user is rejected
 * with HTTP 403 (via {@link AccessDeniedException} → GlobalExceptionHandler).
 * {@code ROLE_ADMIN} and {@code ROLE_APPLICATION_ADMIN} are unscoped (FR-ORG-3).
 */
@RestController
@RequestMapping(path = "/admin/user")
@RequiredArgsConstructor
@Slf4j
public class AdminUserController {

    /** Default directory/event page size, matching NFR-PERF-3's stated default of 10. */
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final UserService userService;
    private final RoleService roleService;
    private final EventService eventService;
    private final OrganizationService organizationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Returns one page of the user directory, optionally filtered by a free-text term
     * matched against first name, last name, and email (FR-ADMIN-1).
     *
     * <p>The response bundles the full roles catalogue alongside the page so the admin
     * dashboard can populate its role-reassignment selectors without a second request —
     * the same convention {@link UserController} uses for the profile screen. The
     * {@code user} key carries the <i>calling administrator</i> (taken from the token
     * principal, no extra DB hit) because every page template feeds it to the shared
     * navbar; the directory rows live under {@code users}.
     *
     * @param authentication the calling administrator's authentication
     * @param page           0-indexed page number (defaults to 0)
     * @param size           rows per page (defaults to {@value DEFAULT_PAGE_SIZE}; capped in the repository)
     * @param searchTerm     optional free-text filter; blank lists everyone
     * @return 200 OK with users, pagination metadata, and the roles catalogue
     */
    @GetMapping("/list")
    public ResponseEntity<HttpResponse> listUsers(Authentication authentication,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                                                  @RequestParam(defaultValue = "") String searchTerm) {
        UserDTO caller = getAuthenticatedUser(authentication);
        // FR-ORG-1/2: an organization administrator's directory contains only users who
        // share an active organization with them; other admin tiers see everyone.
        long totalElements;
        Collection<UserDTO> users;
        if (isOrganizationScoped(caller)) {
            totalElements = organizationService.countUsersSharingOrganizations(caller.getId(), searchTerm);
            users = organizationService.searchUsersSharingOrganizations(caller.getId(), searchTerm, page, size);
        } else {
            totalElements = userService.countUsers(searchTerm);
            users = userService.searchUsers(searchTerm, page, size);
        }
        int totalPages = (int) Math.ceil((double) totalElements / Math.max(size, 1));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", caller,
                                "users", users,
                                "usersTotalElements", totalElements,
                                "usersTotalPages", totalPages,
                                "page", page,
                                "pageSize", size,
                                "roles", roleService.getAllRoles()))
                        .message("Users retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns the single-user detail view: profile fields, current role, account state,
     * and the first page of that user's authentication event history (FR-ADMIN-2).
     *
     * <p>The managed user is returned under {@code selectedUser}; {@code user} is the
     * calling administrator for the navbar, mirroring {@link #listUsers}.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @return 200 OK with the selected user, their paginated events, and the roles catalogue
     */
    @GetMapping("/{id}")
    public ResponseEntity<HttpResponse> getUser(Authentication authentication, @PathVariable Long id) {
        requireOrganizationScope(authentication, id);
        UserDTO selectedUser = userService.getUserById(id);
        long eventsTotalElements = eventService.countEventsByUserId(id);
        int eventsTotalPages = (int) Math.ceil((double) eventsTotalElements / DEFAULT_PAGE_SIZE);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", selectedUser,
                                "events", eventService.getEventsByUserId(id, 0, DEFAULT_PAGE_SIZE),
                                "eventsTotalElements", eventsTotalElements,
                                "eventsTotalPages", eventsTotalPages,
                                "roles", roleService.getAllRoles()))
                        .message("User retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns one page of a managed user's audit event history (FR-ADMIN-2 pagination).
     *
     * <p>Called by the admin user-detail frontend when paginating beyond the first page
     * that is bundled into {@link #getUser}. Organization scope is re-checked on every
     * call so a scope reduction between the initial load and a page turn is enforced.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @param page           0-indexed page number (defaults to 0)
     * @param size           rows per page (defaults to {@value DEFAULT_PAGE_SIZE})
     * @return 200 OK with the events page and pagination metadata
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<HttpResponse> getUserEvents(Authentication authentication,
                                                      @PathVariable Long id,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        requireOrganizationScope(authentication, id);
        long totalElements = eventService.countEventsByUserId(id);
        int totalPages = (int) Math.ceil((double) totalElements / Math.max(size, 1));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("events", eventService.getEventsByUserId(id, page, size),
                                "eventsTotalElements", totalElements,
                                "eventsTotalPages", totalPages))
                        .message("User events retrieved.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Reassigns another user's role (FR-ADMIN-3). Requires the {@code UPDATE:ROLE}
     * authority and refuses self-targeting — administrators change their own role the
     * same way everyone else does: by asking another administrator (FR-RBAC-4 /
     * NFR-SEC-8). The change is audited against the target user.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @param roleName       the role to assign (e.g. {@code ROLE_MODERATOR})
     * @return 200 OK with the refreshed target user and the roles catalogue
     */
    @PreAuthorize("hasAuthority('UPDATE:ROLE')")
    @PatchMapping("/{id}/role/{roleName}")
    public ResponseEntity<HttpResponse> updateUserRole(Authentication authentication,
                                                       @PathVariable Long id,
                                                       @PathVariable String roleName) {
        requireNotSelf(authentication, id, "You cannot change your own role. Ask another administrator.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        userService.updateUserRole(id, roleName);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), ROLE_UPDATE));
        log.info("Admin '{}' reassigned role of user id {} to {}", getAuthenticatedUser(authentication).getEmail(), id, roleName);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", userService.getUserById(id),
                                "roles", roleService.getAllRoles()))
                        .message("User role updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Changes another user's account state — enable/disable and lock/unlock flags
     * (FR-ADMIN-4). Requires the {@code UPDATE:USER} authority and refuses
     * self-targeting so an administrator cannot accidentally lock themselves out.
     * The change is audited against the target user.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @param settingsForm   validated payload carrying {@code enabled} and {@code notLocked}
     * @return 200 OK with the refreshed target user
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @PatchMapping("/{id}/settings")
    public ResponseEntity<HttpResponse> updateAccountSettings(Authentication authentication,
                                                              @PathVariable Long id,
                                                              @RequestBody @Valid SettingsForm settingsForm) {
        requireNotSelf(authentication, id, "You cannot change your own account state from the admin dashboard. Use your profile settings.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        userService.updateAccountSettings(id, settingsForm.getEnabled(), settingsForm.getNotLocked());
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), ACCOUNT_SETTINGS_UPDATE));
        log.info("Admin '{}' set account state of user id {} to enabled={}, notLocked={}",
                getAuthenticatedUser(authentication).getEmail(), id, settingsForm.getEnabled(), settingsForm.getNotLocked());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", userService.getUserById(id),
                                "roles", roleService.getAllRoles()))
                        .message("User account settings updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Rejects administrative operations whose target is the calling administrator's own
     * account. Self-service mutations belong to {@link UserController}; keeping them off
     * the admin surface is what makes FR-RBAC-4 ("a user shall not elevate their own
     * role") structurally impossible rather than merely policy-checked.
     *
     * @param authentication the calling administrator's authentication
     * @param targetId       the user id the operation wants to act on
     * @param message        the error message to surface if the target is the caller
     */
    private static void requireNotSelf(Authentication authentication, Long targetId, String message) {
        if (getAuthenticatedUser(authentication).getId().equals(targetId)) {
            throw new ApiException(message);
        }
    }

    /**
     * Whether the caller's authority is bounded by organization membership. Only
     * {@code ROLE_ORGANIZATION_ADMIN} is scoped; {@code ROLE_ADMIN} and
     * {@code ROLE_APPLICATION_ADMIN} act globally (FR-ORG-3).
     *
     * @param caller the calling administrator from the token principal
     * @return true when organization-scope checks apply to this caller
     */
    private static boolean isOrganizationScoped(UserDTO caller) {
        return ROLE_ORGANIZATION_ADMIN.name().equals(caller.getRoleName());
    }

    /**
     * Enforces FR-ORG-2 on single-target operations: an organization administrator may
     * act only on users sharing an active organization with them. Out-of-scope targets
     * raise {@link AccessDeniedException}, which GlobalExceptionHandler maps to the
     * HTTP 403 the SRS requires. The denial message names no account data, so it cannot
     * be used to probe which user ids exist (NFR-SEC-7).
     *
     * @param authentication the calling administrator's authentication
     * @param targetId       the user id the operation wants to act on
     */
    private void requireOrganizationScope(Authentication authentication, Long targetId) {
        UserDTO caller = getAuthenticatedUser(authentication);
        if (isOrganizationScoped(caller) && !organizationService.isWithinOrganizationScope(caller.getId(), targetId)) {
            log.warn("Org admin '{}' denied access to user id {} (outside organization scope)", caller.getEmail(), targetId);
            throw new AccessDeniedException("This user is outside your organization scope.");
        }
    }
}
