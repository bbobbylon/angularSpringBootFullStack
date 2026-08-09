package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.service.EventService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.PasskeyService;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Organization-scope enforcement on the admin user surface (SRS FR-ORG-2 / FR-ORG-3).
 *
 * <h3>Why this suite is separate from {@link AdminUserControllerTest}</h3>
 * That suite pins down the <em>editing</em> contract (path id wins, self-targeting refused) and
 * deliberately uses an unscoped {@code ROLE_ADMIN} so the tenancy branch stays out of its way.
 * This one is about nothing but tenancy, and it covers the axis that is easiest to get wrong.
 *
 * <h3>The three properties, and why each earns a test</h3>
 * <ul>
 *   <li><b>Reads are scoped, not just writes.</b> This is the classic omission: a reviewer guards
 *       the mutating endpoints, and {@code GET /admin/user/{id}} quietly hands the same data over
 *       anyway. A tenancy boundary that only stops writes is not a tenancy boundary — reading
 *       another organization's user record and audit log <em>is</em> the breach.</li>
 *   <li><b>The unscoped tiers still work.</b> {@code ROLE_ADMIN} and {@code ROLE_APPLICATION_ADMIN}
 *       act globally by design (FR-ORG-3). Without this case, the suite would still pass if the
 *       guard were changed to refuse everyone, and the platform admin would be locked out of their
 *       own system by a "security fix".</li>
 *   <li><b>Denials do not leak existence.</b> A 403 is returned for an out-of-scope id whether or
 *       not that id exists, and the body must not distinguish the two — otherwise the endpoint
 *       becomes an oracle for enumerating user ids across tenants (NFR-SEC-7).</li>
 * </ul>
 *
 * <p>Built with {@code standaloneSetup} plus the real {@link GlobalExceptionHandler}, so the
 * {@code AccessDeniedException} the controller raises is mapped to a genuine HTTP 403 rather than
 * asserted as a Java exception — the status code is the contract the SPA depends on.
 */
class AdminUserControllerOrgScopeTest {

    private static final long ORG_ADMIN_ID = 5L;
    private static final long IN_SCOPE_TARGET = 20L;
    // Deliberately long and non-sequential: denialIsNonEnumerating scans the raw JSON body — including
    // the response's nanosecond-precision timestamp field — for this id as a substring. A short id like
    // 77 has real odds of appearing by coincidence inside an unrelated ~9-digit timestamp fraction and
    // failing the test on a leak that never happened; a 9-digit id makes that collision astronomically
    // unlikely while still exercising the exact same org-scope-denial code path.
    private static final long OUT_OF_SCOPE_TARGET = 918273645L;

    private UserService userService;
    private EventService eventService;
    private OrganizationService organizationService;
    private SessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        RoleService roleService = mock(RoleService.class);
        eventService = mock(EventService.class);
        organizationService = mock(OrganizationService.class);
        sessionService = mock(SessionService.class);
        PasskeyService passkeyService = mock(PasskeyService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        AdminUserController controller = new AdminUserController(userService, roleService, eventService,
                organizationService, sessionService, passkeyService, eventPublisher);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(roleService.getAllRoles()).thenReturn(List.of());
    }

    /** An organization administrator — the only tier the scope check applies to. */
    private static Authentication orgAdmin() {
        return authFor("ROLE_ORGANIZATION_ADMIN");
    }

    /** A platform administrator — unscoped by design (FR-ORG-3). */
    private static Authentication globalAdmin() {
        return authFor("ROLE_ADMIN");
    }

    private static Authentication authFor(String roleName) {
        UserDTO caller = new UserDTO();
        caller.setId(ORG_ADMIN_ID);
        caller.setEmail("caller@example.com");
        caller.setRoleName(roleName);
        return new UsernamePasswordAuthenticationToken(
                caller, null, AuthorityUtils.createAuthorityList("UPDATE:USER", "UPDATE:ROLE"));
    }

    /** Makes the target look like a normal, existing account so only scope decides the outcome. */
    private void stubExistingTarget(long id) {
        UserDTO target = new UserDTO();
        target.setId(id);
        target.setEmail("target@example.com");
        when(userService.getUserById(id)).thenReturn(target);
        when(eventService.countEventsByUserId(id)).thenReturn(0L);
        when(eventService.getEventsByUserId(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("an org admin may READ a user inside their organization")
    void inScopeReadIsAllowed() throws Exception {
        // The positive case. Without it every "must be 403" assertion below would still hold if the
        // guard simply denied everyone, and the feature would be silently broken for its real users.
        when(organizationService.isWithinOrganizationScope(ORG_ADMIN_ID, IN_SCOPE_TARGET)).thenReturn(true);
        stubExistingTarget(IN_SCOPE_TARGET);

        mockMvc.perform(get("/admin/user/{id}", IN_SCOPE_TARGET).principal(orgAdmin()))
                .andExpect(status().isOk());

        verify(userService).getUserById(IN_SCOPE_TARGET);
    }

    @Test
    @DisplayName("an org admin is refused with 403 when READING a user outside their organization")
    void outOfScopeReadIsForbidden() throws Exception {
        when(organizationService.isWithinOrganizationScope(ORG_ADMIN_ID, OUT_OF_SCOPE_TARGET)).thenReturn(false);

        mockMvc.perform(get("/admin/user/{id}", OUT_OF_SCOPE_TARGET).principal(orgAdmin()))
                .andExpect(status().isForbidden());

        // The refusal happens BEFORE the record is fetched. If the guard ran after the lookup, the
        // data would already have been loaded — and any later logging, caching, or error path that
        // happened to include it would leak the very record the check exists to protect.
        verify(userService, never()).getUserById(OUT_OF_SCOPE_TARGET);
    }

    @Test
    @DisplayName("the audit-log endpoint is scoped too, not only the user record")
    void outOfScopeEventsReadIsForbidden() throws Exception {
        // Paginating events is a second door to the same tenant data — and an easy one to forget,
        // because it is reached only after the detail page has already loaded.
        when(organizationService.isWithinOrganizationScope(ORG_ADMIN_ID, OUT_OF_SCOPE_TARGET)).thenReturn(false);

        mockMvc.perform(get("/admin/user/{id}/events", OUT_OF_SCOPE_TARGET).principal(orgAdmin()))
                .andExpect(status().isForbidden());

        verify(eventService, never()).getEventsByUserId(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a platform admin is not scope-checked at all (FR-ORG-3)")
    void globalAdminBypassesScopeEntirely() throws Exception {
        stubExistingTarget(OUT_OF_SCOPE_TARGET);

        mockMvc.perform(get("/admin/user/{id}", OUT_OF_SCOPE_TARGET).principal(globalAdmin()))
                .andExpect(status().isOk());

        // Not merely "allowed" — the membership question is never even asked. Asserting the call
        // never happens is what distinguishes "unscoped by design" from "happened to be a member".
        verify(organizationService, never()).isWithinOrganizationScope(anyLong(), anyLong());
    }

    @Test
    @DisplayName("the 403 body reveals nothing about whether the target exists")
    void denialIsNonEnumerating() throws Exception {
        when(organizationService.isWithinOrganizationScope(anyLong(), anyLong())).thenReturn(false);

        MvcResult result = mockMvc.perform(get("/admin/user/{id}", OUT_OF_SCOPE_TARGET).principal(orgAdmin()))
                .andExpect(status().isForbidden())
                .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase();
        // No email, no name, no role, and no "user not found" — an out-of-scope id that exists and
        // one that does not must be indistinguishable, or the endpoint becomes an id oracle.
        assertFalse(body.contains("target@example.com"), "leaked the target's email: " + body);
        assertFalse(body.contains("role_"), "leaked a role name: " + body);
        assertFalse(body.contains("not found"), "distinguished missing from forbidden: " + body);
        assertFalse(body.contains(String.valueOf(OUT_OF_SCOPE_TARGET)), "echoed the probed id: " + body);
    }
}
