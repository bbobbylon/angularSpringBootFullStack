package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.form.SettingsForm;
import com.bob.angularspringbootfullstack.form.UpdateForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.PasskeyService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.utils.SortUtils;
import com.bob.angularspringbootfullstack.utils.UserTypeResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.EventType.ACCOUNT_SETTINGS_UPDATE;
import static com.bob.angularspringbootfullstack.enumeration.EventType.MFA_RESET;
import static com.bob.angularspringbootfullstack.enumeration.EventType.PASSKEY_REMOVED;
import static com.bob.angularspringbootfullstack.enumeration.EventType.PROFILE_UPDATE;
import static com.bob.angularspringbootfullstack.enumeration.EventType.ROLE_UPDATE;
import static com.bob.angularspringbootfullstack.enumeration.EventType.SESSION_REVOKED;
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

    /**
     * Client-facing sort fields the {@code /admin/user/list} directory accepts, mapped to the
     * actual {@code users} column each names — the raw-JDBC counterpart of
     * {@code CustomerController#CUSTOMER_SORT_FIELDS}. Resolved through
     * {@link SortUtils#resolveSqlOrderBy}, which only ever hands back one of these values (or the
     * default), so the fragment spliced into {@code UserQuery#SELECT_USERS_PAGED_QUERY} is always
     * one this controller wrote, never anything client-supplied.
     */
    private static final Map<String, String> USER_SORT_FIELDS =
            Map.of("id", "id", "firstName", "first_name", "lastName", "last_name", "email", "email", "createdAt", "created_at");

    /**
     * The organization-scoped sibling of {@link #USER_SORT_FIELDS}, {@code u.}-qualified because
     * {@code OrganizationQuery#SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY} joins
     * {@code userorganizations} under aliases {@code a}/{@code b} and an unqualified column name
     * could otherwise collide with one on the joined table.
     */
    private static final Map<String, String> ORG_SCOPED_USER_SORT_FIELDS =
            Map.of("id", "u.id", "firstName", "u.first_name", "lastName", "u.last_name", "email", "u.email", "createdAt", "u.created_at");

    /** Unsorted default for both directory queries — newest accounts first. */
    private static final String DEFAULT_USER_ORDER_BY = "created_at DESC, id DESC";

    /** {@link #DEFAULT_USER_ORDER_BY}, {@code u.}-qualified for the organization-scoped query. */
    private static final String DEFAULT_ORG_SCOPED_USER_ORDER_BY = "u.created_at DESC, u.id DESC";

    private final UserService userService;
    private final RoleService roleService;
    private final EventService eventService;
    private final OrganizationService organizationService;
    private final SessionService sessionService;
    private final PasskeyService passkeyService;
    private final TotpService totpService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Comma-separated email-domain allowlist for the INTERNAL/EXTERNAL half of the user-type
     * badge (P2-1) — env {@code INTERNAL_DOMAINS}, e.g. {@code "lewisu.edu,tesseraapp.dev"}.
     * Deliberately reconfigurable at deploy time rather than baked into code: which domains count
     * as "internal" is an operational fact about a given deployment, not a compile-time constant.
     * Blank/unset means nothing qualifies as INTERNAL — every non-federated account reads EXTERNAL,
     * the safe default when nobody has configured this yet.
     */
    @Value("${app.security.internal-domains:}")
    private String internalDomains;

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
     * @param sort           optional {@code field,direction} sort (e.g. {@code "email,desc"}); unset or
     *                       unrecognized falls back to the newest-first default — see {@link #USER_SORT_FIELDS}
     * @return 200 OK with users, pagination metadata, and the roles catalogue
     */
    @GetMapping("/list")
    public ResponseEntity<HttpResponse> listUsers(Authentication authentication,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                                                  @RequestParam(defaultValue = "") String searchTerm,
                                                  @RequestParam Optional<String> sort) {
        UserDTO caller = getAuthenticatedUser(authentication);
        // FR-ORG-1/2: an organization administrator's directory contains only users who
        // share an active organization with them; other admin tiers see everyone.
        long totalElements;
        Collection<UserDTO> users;
        if (isOrganizationScoped(caller)) {
            String orderBy = SortUtils.resolveSqlOrderBy(sort, ORG_SCOPED_USER_SORT_FIELDS, DEFAULT_ORG_SCOPED_USER_ORDER_BY);
            totalElements = organizationService.countUsersSharingOrganizations(caller.getId(), searchTerm);
            users = organizationService.searchUsersSharingOrganizations(caller.getId(), searchTerm, page, size, orderBy);
        } else {
            String orderBy = SortUtils.resolveSqlOrderBy(sort, USER_SORT_FIELDS, DEFAULT_USER_ORDER_BY);
            totalElements = userService.countUsers(searchTerm);
            users = userService.searchUsers(searchTerm, page, size, orderBy);
        }
        int totalPages = (int) Math.ceil((double) totalElements / Math.max(size, 1));
        users.forEach(this::stampUserType);
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
        stampUserType(selectedUser);
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
                                "roles", roleService.getAllRoles(),
                                // Metadata only — id, nickname, transports, timestamps. Never the
                                // WebAuthn credential id or the stored attestation object; an
                                // administrator has no legitimate need to see either, and exposing
                                // them would widen this endpoint's blast radius for no UI benefit.
                                "passkeys", passkeyService.listCredentials(id),
                                // Same shape as the Security Center's own device list; RefreshSession
                                // already @JsonIgnores jti/userId/revoked/superseded, so this is the
                                // identical, already-safe-to-serialize view used for self-service.
                                "sessions", sessionService.listSessions(id)))
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
        requireAssignableTier(authentication, roleName);
        UserDTO target = userService.getUserById(id);
        userService.updateUserRole(id, roleName);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), ROLE_UPDATE));
        log.info("Admin '{}' reassigned role of user id {} to {}", getAuthenticatedUser(authentication).getEmail(), id, roleName);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", refreshedTarget(id),
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
                                "selectedUser", refreshedTarget(id),
                                "roles", roleService.getAllRoles()))
                        .message("User account settings updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates another user's profile fields on an administrator's behalf (FR-ADMIN, the admin-update capability
     * {@link UserController} deliberately does not provide).
     *
     * <p><b>Why the id is trusted here, unlike {@code PATCH /user/update}.</b> The self-service
     * endpoint deliberately <em>ignores</em> the body id and binds to the JWT principal, because
     * there a client-supplied id would be an IDOR (any {@code ROLE_USER} could edit anyone). This
     * endpoint is the opposite by design: an administrator is <em>supposed</em> to target another
     * user, so the {@code {id}} path variable is authoritative and is written onto the form,
     * overwriting whatever the body carried. The gate that makes that safe is the
     * {@code UPDATE:USER} authority (enforced at both the URL layer in {@code SecurityConfig} and
     * the method layer via {@link PreAuthorize}, per FR-RBAC-2) plus the organization-scope check.
     *
     * <p>Self-targeting is refused so this admin path never becomes a second way for an
     * administrator to edit their own account — that belongs to their profile screen — keeping the
     * "admin endpoints act on <em>other</em> users" invariant intact. The change is audited against
     * the target user (FR-ADMIN) and logged for the operator.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key (authoritative; overwrites any body id)
     * @param form           the validated profile fields to apply
     * @return 200 OK with the refreshed target user under {@code selectedUser} and the roles catalogue
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @PatchMapping("/{id}/update")
    public ResponseEntity<HttpResponse> updateUserByAdmin(Authentication authentication,
                                                          @PathVariable Long id,
                                                          @RequestBody @Valid UpdateForm form) {
        requireNotSelf(authentication, id, "Use your profile settings to edit your own account.");
        requireOrganizationScope(authentication, id);
        // Trust the PATH id (admin targets another user), NOT the body — the inverse of the IDOR
        // fix on the self-service endpoint, and safe because UPDATE:USER + org scope gate this route.
        form.setId(id);
        UserDTO updated = userService.updateUserDTO(form);
        eventPublisher.publishEvent(new NewUserEvent(updated.getEmail(), PROFILE_UPDATE));
        log.info("Admin '{}' updated profile of user id {} (email={})",
                getAuthenticatedUser(authentication).getEmail(), id, updated.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", updated,
                                "roles", roleService.getAllRoles()))
                        .message("User profile updated successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Signs a user out of every device by revoking all of their refresh sessions.
     *
     * <p>This is the containment action for "that account may be compromised". Locking an account
     * (via {@code PATCH /{id}/settings}) stops the <em>next</em> sign-in but does nothing to the
     * sessions already open — access tokens are verified by signature alone, so an attacker holding
     * one keeps working until it expires, and their refresh token keeps minting new ones for five
     * days. Revoking the refresh families is what actually ends the intrusion, which is why this
     * sits beside the lock control rather than behind a separate screen.
     *
     * <p>Requires {@code UPDATE:USER} — the same authority as changing account state, because this
     * is the same kind of act. Organization scope applies, so a tenant administrator can only do it
     * to their own members.
     *
     * <p>Self-targeting is refused: an administrator ending their own sessions belongs on their
     * Security Center, which can exclude the current device. Doing it here would sign the caller
     * out mid-request with no way to except themselves.
     *
     * <p>Audited as {@code SESSION_REVOKED} against the <b>target</b>, so it lands in the affected
     * user's own activity history rather than only in the operator's log — the person who was signed
     * out should be able to see that it happened and when.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @return 200 OK with the refreshed target user
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @DeleteMapping("/{id}/sessions")
    public ResponseEntity<HttpResponse> revokeUserSessions(Authentication authentication,
                                                           @PathVariable Long id) {
        requireNotSelf(authentication, id, "Use your Security Center to manage your own sessions.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        sessionService.revokeAllSessions(id);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), SESSION_REVOKED));
        log.warn("Admin '{}' revoked ALL sessions for user id {} (email={})",
                getAuthenticatedUser(authentication).getEmail(), id, target.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", refreshedTarget(id),
                                "roles", roleService.getAllRoles(),
                                "sessions", sessionService.listSessions(id)))
                        .message("All sessions for this user have been revoked.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Revokes one specific session (device) of a managed user, leaving their other sessions
     * untouched — the granular sibling of {@link #revokeUserSessions}, which ends all of them at
     * once. Reuses {@link SessionService#revokeSession}, the same family-scoped revoke the
     * Security Center calls on a user's own sessions; ownership of the family is enforced in the
     * SQL predicate there, so a family id belonging to a different user updates nothing.
     *
     * <p>Same authority, self-target refusal, organization scope, and audit convention as
     * {@link #revokeUserSessions} — this is a narrower version of the same containment action, not
     * a different one.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @param family         the session (family) to revoke
     * @return 200 OK with the refreshed target user and their remaining sessions
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @DeleteMapping("/{id}/sessions/{family}")
    public ResponseEntity<HttpResponse> revokeUserSession(Authentication authentication,
                                                           @PathVariable Long id,
                                                           @PathVariable String family) {
        requireNotSelf(authentication, id, "Use your Security Center to manage your own sessions.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        sessionService.revokeSession(id, family);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), SESSION_REVOKED));
        log.warn("Admin '{}' revoked session '{}' for user id {} (email={})",
                getAuthenticatedUser(authentication).getEmail(), family, id, target.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", refreshedTarget(id),
                                "roles", roleService.getAllRoles(),
                                "sessions", sessionService.listSessions(id)))
                        .message("Session revoked.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Revokes one of a managed user's passkeys — the admin "help reset" action for a lost or
     * compromised device. There is no "regenerate": a passkey's private key never leaves its
     * authenticator, so revocation (forcing the user to enroll a fresh passkey, or fall back to
     * password/TOTP, on their next sign-in) is the only lever anyone — including an administrator —
     * has. Audited as PASSKEY_REMOVED against the <b>target</b>, same convention as
     * {@link #revokeUserSessions}.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @param credentialId   the credential's primary key (never the WebAuthn credential id itself)
     * @return 200 OK with the refreshed target user's passkey list
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @DeleteMapping("/{id}/passkeys/{credentialId}")
    public ResponseEntity<HttpResponse> revokeUserPasskey(Authentication authentication,
                                                          @PathVariable Long id,
                                                          @PathVariable Long credentialId) {
        requireNotSelf(authentication, id, "Use your Security Center to manage your own passkeys.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        passkeyService.deleteCredential(id, credentialId);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), PASSKEY_REMOVED));
        log.warn("Admin '{}' revoked passkey id {} for user id {} (email={})",
                getAuthenticatedUser(authentication).getEmail(), credentialId, id, target.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", refreshedTarget(id),
                                "passkeys", passkeyService.listCredentials(id)))
                        .message("Passkey revoked.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Revokes ALL of a managed user's passkeys in one action — the bulk form of
     * {@link #revokeUserPasskey}, for an account where every enrolled device is suspect. Same
     * self-target refusal, organization scope, and audit convention.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @return 200 OK with the refreshed target user's (now empty) passkey list
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @DeleteMapping("/{id}/passkeys")
    public ResponseEntity<HttpResponse> revokeAllUserPasskeys(Authentication authentication, @PathVariable Long id) {
        requireNotSelf(authentication, id, "Use your Security Center to manage your own passkeys.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        passkeyService.deleteAllCredentials(id);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), PASSKEY_REMOVED));
        log.warn("Admin '{}' revoked ALL passkeys for user id {} (email={})",
                getAuthenticatedUser(authentication).getEmail(), id, target.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", refreshedTarget(id),
                                "passkeys", passkeyService.listCredentials(id)))
                        .message("All passkeys for this user have been revoked.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Force-disables a managed user's authenticator MFA — the admin recovery path for an account
     * that has lost both its authenticator and every recovery code, and so has no live code to
     * present through the self-service disable flow (which {@link TotpService#disableTotp}
     * deliberately requires, so a hijacked session alone cannot strip the second factor). Trust
     * here comes from the caller's {@code UPDATE:USER} authority instead, exactly like every other
     * action on this controller. Same self-target refusal, organization scope, and audit
     * convention as {@link #revokeAllUserPasskeys}. Audited as MFA_RESET, not TOTP_DISABLED, so the
     * trail distinguishes administrator action from self-service.
     *
     * @param authentication the calling administrator's authentication
     * @param id             the target user's primary key
     * @return 200 OK with the refreshed target user (usingTotp now false)
     */
    @PreAuthorize("hasAuthority('UPDATE:USER')")
    @DeleteMapping("/{id}/totp")
    public ResponseEntity<HttpResponse> resetUserTotp(Authentication authentication, @PathVariable Long id) {
        requireNotSelf(authentication, id, "Use your Security Center to manage your own authenticator.");
        requireOrganizationScope(authentication, id);
        UserDTO target = userService.getUserById(id);
        totpService.adminResetTotp(id);
        eventPublisher.publishEvent(new NewUserEvent(target.getEmail(), MFA_RESET));
        log.warn("Admin '{}' reset TOTP for user id {} (email={})",
                getAuthenticatedUser(authentication).getEmail(), id, target.getEmail());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", getAuthenticatedUser(authentication),
                                "selectedUser", refreshedTarget(id)))
                        .message("Authenticator MFA reset for this user.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Re-fetches a managed user and stamps its user-type badge, for the mutating endpoints that
     * return a refreshed {@code selectedUser} after acting on the target. Without this, the badge
     * would go blank on the frontend after any role/settings/session/passkey mutation until the
     * next full page load, since {@code UserDTO#userType} is never copied by {@code BeanUtils} —
     * only {@link #getUser} and {@link #listUsers} stamped it otherwise.
     *
     * @param id the target user's primary key
     * @return the target user, with {@code userType} populated
     */
    private UserDTO refreshedTarget(Long id) {
        UserDTO user = userService.getUserById(id);
        stampUserType(user);
        return user;
    }

    /**
     * Sets {@link UserDTO#getUserType()} from the account's stamped {@link UserDTO#getOrigin()}
     * and, for non-federated accounts, its email domain against {@link #internalDomains}
     * (P2-1). Mutates in place rather than returning a new instance since every caller already
     * holds a reference to the DTO it wants stamped.
     *
     * @param user the DTO to stamp; a no-op if {@code null}
     */
    private void stampUserType(UserDTO user) {
        if (user == null) return;
        user.setUserType(UserTypeResolver.resolve(user.getEmail(), user.getOrigin(), internalDomains));
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
     * Whether the caller's authority is bounded by organization membership. Delegates to
     * {@link RoleType#isOrganizationScoped(String)} — every role below the two unscoped tiers
     * ({@code ROLE_ADMIN}, {@code ROLE_APPLICATION_ADMIN}, FR-ORG-3) is scoped, not just
     * {@code ROLE_ORGANIZATION_ADMIN} by name.
     *
     * <p><b>Fixed 2026-08-13:</b> this used to check the caller's role name against the literal
     * string {@code "ROLE_ORGANIZATION_ADMIN"}. {@code ROLE_HELP_DESK_ADMIN} also carries
     * {@code UPDATE:USER} and reaches every endpoint on this controller, but was never in that
     * one-name check — so a help-desk admin saw and acted on every user system-wide, unscoped,
     * while an org admin doing the identical job was correctly restricted. See
     * {@link AnalyticsController#resolveScope}, which had the identical bug for the identical
     * reason and is fixed the same way.
     *
     * @param caller the calling administrator from the token principal
     * @return true when organization-scope checks apply to this caller
     */
    private static boolean isOrganizationScoped(UserDTO caller) {
        return RoleType.isOrganizationScoped(caller.getRoleName());
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

    /**
     * Refuses to assign a role that outranks the caller's own (privilege-elevation-by-proxy).
     *
     * <p>{@code UPDATE:ROLE} answers "may you reassign roles at all", and organization scope answers
     * "to whom" — neither bounds <em>which</em> role. Without this third check a
     * {@code ROLE_ORGANIZATION_ADMIN} could promote an in-scope user to {@code ROLE_ADMIN}: an
     * unscoped tier above their own, which they could then act through to reach accounts the scope
     * check would have denied them directly. The grant is legitimate at every individual step, which
     * is exactly what makes it worth blocking explicitly.
     *
     * <p>Equal tiers are allowed, so an administrator can still create a peer. Only assignment
     * <em>upward</em> is refused.
     *
     * <p>Fails closed on an unrecognised role name — see {@link RoleType#canAssign}. The denial names
     * the requested role but no account data, so like the scope check it cannot be used to probe
     * which users exist (NFR-SEC-7); the role catalogue is public to anyone who can reach this
     * endpoint anyway, since {@code roles} is returned in the response body.
     *
     * @param authentication the calling administrator's authentication
     * @param roleName       the role the caller is attempting to assign
     */
    private static void requireAssignableTier(Authentication authentication, String roleName) {
        UserDTO caller = getAuthenticatedUser(authentication);
        if (!RoleType.canAssign(caller.getRoleName(), roleName)) {
            log.warn("Admin '{}' (role {}) denied assignment of role '{}' — at or above their own tier",
                    caller.getEmail(), caller.getRoleName(), roleName);
            throw new AccessDeniedException(
                    "You cannot assign a role with more privileges than your own.");
        }
    }
}
