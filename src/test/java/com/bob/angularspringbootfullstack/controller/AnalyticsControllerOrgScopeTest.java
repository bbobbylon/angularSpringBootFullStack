package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_APPLICATION_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_HELP_DESK_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ORGANIZATION_ADMIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for organization scoping on the analytics rollups (SRS FR-ORG-2/3).
 *
 * <p>The gap this closes was subtle because the endpoint was <em>already</em> protected: a genuine
 * server-side authority check (see {@code AnalyticsControllerSecurityTest}) decided <b>whether</b> a
 * caller could open the dashboards. It said nothing about <b>whose</b> numbers they contained, so
 * every {@code ROLE_ORGANIZATION_ADMIN} received system-wide totals — the customer counts and
 * billed revenue of organizations they have no relationship with. Authority and tenancy are
 * different questions, and passing the first is not evidence about the second.
 *
 * <p>The assertions therefore come in pairs: a scoped caller must reach the {@code *ForOrganizations}
 * methods and must NOT reach the unscoped ones, because calling the unscoped variant is exactly the
 * bug — it would return correct-looking data that silently spans tenants. Verifying "the scoped
 * method was called" alone would still pass if the unscoped one were also invoked and its result
 * used.
 *
 * <p>Runs on plain Mockito with no Spring context: {@code @PreAuthorize} is deliberately not
 * exercised here (that is the other suite's job), leaving this one free to assert the scoping
 * decision in isolation.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsControllerOrgScopeTest {

    private static final long ORG_ADMIN_ID = 7L;
    private static final List<Long> ORG_IDS = List.of(1L, 4L);

    @Mock
    private CustomerService customerService;
    @Mock
    private UserService userService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private AnalyticsController controller;

    @BeforeEach
    void stubPrincipalLookup() {
        // Every endpoint embeds the caller in its envelope; identity is irrelevant to scoping.
        when(userService.getUserByEmail(anyString())).thenReturn(new UserDTO());
    }

    private static UserDTO callerWithRole(String roleName) {
        UserDTO caller = new UserDTO();
        caller.setId(ORG_ADMIN_ID);
        caller.setEmail("caller@example.com");
        caller.setRoleName(roleName);
        return caller;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<HttpResponse> response) {
        return (Map<String, Object>) response.getBody().getData();
    }

    // ── Scoped caller: ROLE_ORGANIZATION_ADMIN ──────────────────────────────────────────────

    @Test
    @DisplayName("an org admin's summary is restricted to their organizations, never system-wide")
    void orgAdminSummaryIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(ORG_IDS)).thenReturn(Map.of("ACTIVE", 3));

        controller.getSummary(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), null);

        verify(customerService).getStatsForOrganizations(ORG_IDS);
        verify(customerService).getCustomerStatusBreakdownForOrganizations(ORG_IDS);
        // The heart of the fix: reaching the unscoped variants at all would leak other tenants.
        verify(customerService, never()).getStats();
        verify(customerService, never()).getCustomerStatusBreakdown();
    }

    @Test
    @DisplayName("an org admin's customer page is restricted to their organizations")
    void orgAdminCustomersAreScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getCustomersForOrganizations(ORG_IDS, 0, 20, Sort.unsorted())).thenReturn(new PageImpl<>(List.of(new Customer())));
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(ORG_IDS)).thenReturn(Map.of());

        controller.getCustomers(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.empty(), Optional.empty(), Optional.empty(), null);

        verify(customerService).getCustomersForOrganizations(ORG_IDS, 0, 20, Sort.unsorted());
        verify(customerService, never()).getCustomers(anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("an org admin's invoice page is restricted to their organizations")
    void orgAdminInvoicesAreScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getInvoicesForOrganizations(ORG_IDS, 0, 20, Sort.unsorted())).thenReturn(new PageImpl<>(List.of(new Invoice())));

        controller.getInvoices(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.empty(), Optional.empty(), Optional.empty(), null);

        verify(customerService).getInvoicesForOrganizations(ORG_IDS, 0, 20, Sort.unsorted());
        verify(customerService, never()).getInvoices(anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("pagination parameters survive scoping rather than being silently reset")
    void scopedPagingHonoursRequestedPage() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getInvoicesForOrganizations(ORG_IDS, 3, 50, Sort.unsorted())).thenReturn(Page.empty());

        controller.getInvoices(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.of(3), Optional.of(50), Optional.empty(), null);

        verify(customerService).getInvoicesForOrganizations(ORG_IDS, 3, 50, Sort.unsorted());
    }

    // ── Regression, 2026-08-13: scoped callers OTHER than ROLE_ORGANIZATION_ADMIN ───────────

    @Test
    @DisplayName("a help-desk admin's summary is scoped too, not just an org admin's")
    void helpDeskAdminSummaryIsScoped() {
        // Before the fix, resolveScope() checked the caller's role name against the literal string
        // "ROLE_ORGANIZATION_ADMIN" — so a help-desk admin (also UPDATE:USER, also reaches this
        // controller) fell through to the unscoped branch and saw every organization's rollups.
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(ORG_IDS)).thenReturn(Map.of("ACTIVE", 3));

        controller.getSummary(callerWithRole(ROLE_HELP_DESK_ADMIN.name()), null);

        verify(customerService).getStatsForOrganizations(ORG_IDS);
        verify(customerService, never()).getStats();
        verify(customerService, never()).getCustomerStatusBreakdown();
    }

    // ── Unscoped callers: ROLE_ADMIN / ROLE_APPLICATION_ADMIN (FR-ORG-3) ────────────────────

    @Test
    @DisplayName("a plain admin remains unscoped and still sees system-wide numbers")
    void adminIsUnscoped() {
        when(customerService.getStats()).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdown()).thenReturn(Map.of("ACTIVE", 9));

        controller.getSummary(callerWithRole(ROLE_ADMIN.name()), null);

        verify(customerService).getStats();
        // An unscoped tier must not even be asked which organizations it belongs to — its answer
        // would be irrelevant, and consulting it invites a future refactor to start applying it.
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    @Test
    @DisplayName("an application admin remains unscoped (FR-ORG-3)")
    void applicationAdminIsUnscoped() {
        when(customerService.getInvoices(0, 20, Sort.unsorted())).thenReturn(Page.empty());

        controller.getInvoices(callerWithRole(ROLE_APPLICATION_ADMIN.name()), Optional.empty(), Optional.empty(), Optional.empty(), null);

        verify(customerService).getInvoices(0, 20, Sort.unsorted());
        verify(customerService, never()).getInvoicesForOrganizations(any(), anyInt(), anyInt(), any());
    }

    // ── The degenerate case ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an org admin belonging to no organization sees zeros, NOT the system-wide view")
    void orgAdminWithNoMembershipsSeesNothing() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(List.of());

        ResponseEntity<HttpResponse> response = controller.getSummary(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), null);

        // "No memberships" has two possible readings — see everything, or see nothing. Treating an
        // empty scope as "unscoped" would hand the global view to the least-established account,
        // so the empty set must resolve to empty DATA.
        verify(customerService, never()).getStats();
        verify(customerService, never()).getCustomerStatusBreakdown();
        Stats stats = (Stats) dataOf(response).get("stats");
        assertEquals(0, stats.getTotalCustomers());
        assertEquals(0, stats.getTotalInvoices());
        assertEquals(0.0, stats.getTotalBilled());
        assertTrue(((Map<?, ?>) dataOf(response).get("statusBreakdown")).isEmpty());
    }

    @Test
    @DisplayName("an org admin with no organizations gets an empty page, not an exception")
    void orgAdminWithNoMembershipsGetsEmptyPage() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(List.of());

        ResponseEntity<HttpResponse> response =
                controller.getCustomers(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.of(2), Optional.of(10), Optional.empty(), null);

        // The service fails closed on an empty scope (an empty SQL `IN ()` is invalid anyway), so
        // the controller must short-circuit rather than let that surface as a 500.
        verify(customerService, never()).getCustomersForOrganizations(any(), anyInt(), anyInt(), any());
        assertTrue(((Page<?>) dataOf(response).get("page")).isEmpty());
    }

    // ── The org-filter dropdown (dashboard revamp, 2026-08-22) ──────────────────────────────

    @Test
    @DisplayName("an unscoped caller filtering to one organization is narrowed to exactly that id")
    void unscopedCallerFilterNarrowsToOneOrganization() {
        when(customerService.getStatsForOrganizations(Set.of(4L))).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(Set.of(4L))).thenReturn(Map.of());

        controller.getSummary(callerWithRole(ROLE_ADMIN.name()), 4L);

        verify(customerService).getStatsForOrganizations(Set.of(4L));
        verify(customerService, never()).getStats();
    }

    @Test
    @DisplayName("a scoped caller filtering to an organization they belong to is narrowed further")
    void scopedCallerFilterNarrowsWithinTheirOwnScope() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getStatsForOrganizations(Set.of(4L))).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(Set.of(4L))).thenReturn(Map.of());

        controller.getSummary(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 4L);

        verify(customerService).getStatsForOrganizations(Set.of(4L));
        verify(customerService, never()).getStatsForOrganizations(ORG_IDS);
    }

    @Test
    @DisplayName("a scoped caller filtering to an organization OUTSIDE their scope sees zeros, never that org's data")
    void scopedCallerFilterOutsideOwnScopeSeesNothing() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);

        ResponseEntity<HttpResponse> response = controller.getSummary(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 999L);

        verify(customerService, never()).getStatsForOrganizations(any());
        verify(customerService, never()).getStats();
        Stats stats = (Stats) dataOf(response).get("stats");
        assertEquals(0, stats.getTotalCustomers());
    }
}
