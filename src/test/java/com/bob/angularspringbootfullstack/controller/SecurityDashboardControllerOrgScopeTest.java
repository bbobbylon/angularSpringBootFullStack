package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.SecurityDashboardService;
import com.bob.angularspringbootfullstack.service.SecuritySettingsService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_APPLICATION_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_HELP_DESK_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ORGANIZATION_ADMIN;
import static com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl.DEFAULT_WINDOW_DAYS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for organization scoping on {@code /admin/security/overview} (SRS FR-TPF-2,
 * FR-ORG-2), the same pattern {@link AnalyticsControllerOrgScopeTest} and
 * {@link CustomerControllerOrgScopeTest} already apply to their surfaces.
 *
 * <p><b>2026-08-21:</b> {@code resolveScope} used to check the caller's role name against the
 * literal string {@code "ROLE_ORGANIZATION_ADMIN"} — the identical bug already found and fixed on
 * the analytics surface on 2026-08-13 (see {@code AnalyticsControllerOrgScopeTest}'s header), just
 * never propagated here. A {@code ROLE_HELP_DESK_ADMIN} (also {@code UPDATE:USER}, also reaches
 * this {@code /admin/**} controller) fell through to the unscoped branch and watched every other
 * organization's failed logins, locked accounts, and MFA gaps — a more sensitive leak than the
 * billing figures FR-ORG-2 originally closed. No test caught it because no org-scope suite existed
 * for this controller; only its authority gating was covered ({@code
 * SecurityDashboardControllerSecurityTest}).
 *
 * <p>Runs on plain Mockito with no Spring context: {@code @PreAuthorize} is deliberately not
 * exercised here, leaving this suite free to assert the scoping decision alone.
 */
@ExtendWith(MockitoExtension.class)
class SecurityDashboardControllerOrgScopeTest {

    private static final long ORG_ADMIN_ID = 7L;
    private static final List<Long> ORG_IDS = List.of(1L, 4L);

    @Mock
    private SecurityDashboardService securityDashboardService;
    @Mock
    private UserService userService;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private SecuritySettingsService securitySettingsService;

    @InjectMocks
    private SecurityDashboardController controller;

    @BeforeEach
    void stubPrincipalLookup() {
        when(userService.getUserByEmail(anyString())).thenReturn(new UserDTO());
        when(securityDashboardService.getOverview(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(SecurityOverview.empty(DEFAULT_WINDOW_DAYS));
    }

    private static UserDTO callerWithRole(String roleName) {
        UserDTO caller = new UserDTO();
        caller.setId(ORG_ADMIN_ID);
        caller.setEmail("caller@example.com");
        caller.setRoleName(roleName);
        return caller;
    }

    // ── Scoped callers ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an org admin's overview is restricted to their organizations")
    void orgAdminOverviewIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);

        controller.getOverview(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.empty(), 0, 50, 0, 50);

        verify(securityDashboardService).getOverview(eq(ORG_IDS), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a help-desk admin's overview is scoped too, not just an org admin's")
    void helpDeskAdminOverviewIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);

        controller.getOverview(callerWithRole(ROLE_HELP_DESK_ADMIN.name()), Optional.empty(), 0, 50, 0, 50);

        verify(securityDashboardService).getOverview(eq(ORG_IDS), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an org admin with no memberships gets an empty overview, not the system-wide one")
    void orgAdminWithNoMembershipsGetsEmptyOverview() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(List.of());

        controller.getOverview(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.empty(), 0, 50, 0, 50);

        verify(securityDashboardService).getOverview(eq(List.of()), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    // ── Unscoped callers: ROLE_ADMIN / ROLE_APPLICATION_ADMIN (FR-ORG-3) ────────────────────

    @Test
    @DisplayName("a plain admin remains unscoped and still sees the whole platform")
    void adminIsUnscoped() {
        controller.getOverview(callerWithRole(ROLE_ADMIN.name()), Optional.empty(), 0, 50, 0, 50);

        verify(securityDashboardService).getOverview(isNull(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    @Test
    @DisplayName("an application admin remains unscoped (FR-ORG-3)")
    void applicationAdminIsUnscoped() {
        controller.getOverview(callerWithRole(ROLE_APPLICATION_ADMIN.name()), Optional.empty(), 0, 50, 0, 50);

        verify(securityDashboardService).getOverview(isNull(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }
}
