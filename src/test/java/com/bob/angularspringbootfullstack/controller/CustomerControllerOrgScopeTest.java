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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ORGANIZATION_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for organization scoping on the SHARED {@code /customer/**} surface
 * (FR-ORG-2, 2026-08-08) — the extension of {@link AnalyticsControllerOrgScopeTest}'s pattern
 * from the admin-only rollups to the endpoints every authenticated user (including a
 * {@code ROLE_ORGANIZATION_ADMIN} browsing day to day, not just through the dashboards) actually
 * hits. Before this, an org admin's directory-scoping (FR-ORG-1) covered user administration
 * only — {@code /customer/**} showed every organization's customers and invoices regardless.
 *
 * <p>Same pairing discipline as the analytics suite: a scoped caller reaching an unscoped
 * service method is exactly the bug (correct-looking data that silently spans tenants), so every
 * positive assertion is paired with a {@code never()} on the sibling method.
 *
 * <p>Runs on plain Mockito, no Spring context — authority gating is the security filter chain's
 * job, not this controller's; this suite isolates the scoping decision alone.
 */
@ExtendWith(MockitoExtension.class)
class CustomerControllerOrgScopeTest {

    private static final long ORG_ADMIN_ID = 7L;
    private static final List<Long> ORG_IDS = List.of(1L, 4L);

    @Mock
    private CustomerService customerService;
    @Mock
    private UserService userService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private CustomerController controller;

    @BeforeEach
    void stubPrincipalLookup() {
        // lenient(): several tests throw AccessDeniedException before reaching this call (the
        // scope check runs first), which would otherwise fail strict-stubbing verification for
        // a stub never invoked in that specific test.
        lenient().when(userService.getUserByEmail(anyString())).thenReturn(new UserDTO());
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

    // ── Scoped caller: ROLE_ORGANIZATION_ADMIN, list/search endpoints ───────────────────────

    @Test
    @DisplayName("an org admin's stats are restricted to their organizations")
    void orgAdminStatsAreScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(ORG_IDS)).thenReturn(Map.of("ACTIVE", 2));

        controller.getStats(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()));

        verify(customerService).getStatsForOrganizations(ORG_IDS);
        verify(customerService, never()).getStats();
    }

    @Test
    @DisplayName("an org admin's customer list is restricted to their organizations")
    void orgAdminCustomerListIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getCustomersForOrganizations(ORG_IDS, 0, 20)).thenReturn(new PageImpl<>(List.of(new Customer())));
        when(customerService.getStatsForOrganizations(ORG_IDS)).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdownForOrganizations(ORG_IDS)).thenReturn(Map.of());

        controller.getCustomers(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.empty(), Optional.empty());

        verify(customerService).getCustomersForOrganizations(ORG_IDS, 0, 20);
        verify(customerService, never()).getCustomers(anyInt(), anyInt());
    }

    @Test
    @DisplayName("an org admin's search is restricted to their organizations")
    void orgAdminSearchIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.searchCustomersForOrganizations("ada", ORG_IDS, 0, 20)).thenReturn(Page.empty());

        controller.searchCustomer(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.of("ada"), Optional.empty(), Optional.empty());

        verify(customerService).searchCustomersForOrganizations("ada", ORG_IDS, 0, 20);
        verify(customerService, never()).searchCustomers(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("an org admin's invoice list is restricted to their organizations")
    void orgAdminInvoiceListIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getInvoicesForOrganizations(ORG_IDS, 0, 20)).thenReturn(new PageImpl<>(List.of(new Invoice())));

        controller.getInvoices(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.empty(), Optional.empty());

        verify(customerService).getInvoicesForOrganizations(ORG_IDS, 0, 20);
        verify(customerService, never()).getInvoices(anyInt(), anyInt());
    }

    @Test
    @DisplayName("an org admin's new-invoice customer picker is restricted to their organizations")
    void orgAdminNewInvoicePickerIsScoped() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        when(customerService.getCustomersForOrganizations(ORG_IDS)).thenReturn(List.of(new Customer()));

        controller.newInvoice(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()));

        verify(customerService).getCustomersForOrganizations(ORG_IDS);
        verify(customerService, never()).getCustomers();
    }

    // ── Scoped caller, single-record gets ────────────────────────────────────────────────────

    @Test
    @DisplayName("an org admin CAN fetch a single customer that belongs to their organization")
    void orgAdminCanFetchInScopeCustomer() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        Customer customer = new Customer();
        customer.setOrganizationId(1L);
        when(customerService.getCustomer(99L)).thenReturn(customer);

        assertDoesNotThrow(() -> controller.getCustomer(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L));
    }

    @Test
    @DisplayName("an org admin CANNOT fetch a single customer outside their organizations")
    void orgAdminCannotFetchOutOfScopeCustomer() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        Customer customer = new Customer();
        customer.setOrganizationId(999L); // not in ORG_IDS
        when(customerService.getCustomer(99L)).thenReturn(customer);

        assertThrows(AccessDeniedException.class,
                () -> controller.getCustomer(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L));
    }

    @Test
    @DisplayName("an org admin CANNOT fetch a customer with no organization at all")
    void orgAdminCannotFetchUnownedCustomer() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        Customer customer = new Customer(); // organizationId left null
        when(customerService.getCustomer(99L)).thenReturn(customer);

        assertThrows(AccessDeniedException.class,
                () -> controller.getCustomer(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L));
    }

    @Test
    @DisplayName("an org admin CANNOT fetch a draft invoice (no customer, so no derivable organization)")
    void orgAdminCannotFetchDraftInvoice() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(ORG_IDS);
        Invoice draft = new Invoice(); // customer left null
        when(customerService.getInvoice(55L)).thenReturn(draft);

        assertThrows(AccessDeniedException.class,
                () -> controller.getInvoice(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 55L));
    }

    @Test
    @DisplayName("an unscoped caller can fetch a draft invoice with no issue")
    void unscopedCallerCanFetchDraftInvoice() {
        Invoice draft = new Invoice();
        when(customerService.getInvoice(55L)).thenReturn(draft);

        assertDoesNotThrow(() -> controller.getInvoice(callerWithRole(ROLE_ADMIN.name()), 55L));
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    // ── Unscoped callers: everyone except ROLE_ORGANIZATION_ADMIN ───────────────────────────

    @Test
    @DisplayName("a plain ROLE_USER remains unscoped — this closes an org-admin gap, not a per-user wall")
    void plainUserIsUnscoped() {
        when(customerService.getCustomers(0, 20)).thenReturn(Page.empty());
        when(customerService.getStats()).thenReturn(new Stats());
        when(customerService.getCustomerStatusBreakdown()).thenReturn(Map.of());

        controller.getCustomers(callerWithRole(ROLE_USER.name()), Optional.empty(), Optional.empty());

        verify(customerService).getCustomers(0, 20);
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    @Test
    @DisplayName("a plain admin remains unscoped and still sees system-wide invoices")
    void adminIsUnscoped() {
        when(customerService.getInvoices(0, 20)).thenReturn(Page.empty());

        controller.getInvoices(callerWithRole(ROLE_ADMIN.name()), Optional.empty(), Optional.empty());

        verify(customerService).getInvoices(0, 20);
        verify(customerService, never()).getInvoicesForOrganizations(any(), anyInt(), anyInt());
    }

    // ── The degenerate case ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an org admin belonging to no organization gets an empty customer page, not an exception")
    void orgAdminWithNoMembershipsGetsEmptyCustomerPage() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(List.of());

        ResponseEntity<HttpResponse> response =
                controller.getCustomers(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), Optional.of(2), Optional.of(10));

        verify(customerService, never()).getCustomersForOrganizations(any(), anyInt(), anyInt());
        assertTrue(((Page<?>) dataOf(response).get("page")).isEmpty());
    }

    @Test
    @DisplayName("an org admin belonging to no organization gets zero stats, not system-wide totals")
    void orgAdminWithNoMembershipsSeesZeroStats() {
        when(organizationService.findActiveOrganizationIds(ORG_ADMIN_ID)).thenReturn(List.of());

        controller.getStats(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()));

        verify(customerService, never()).getStats();
        verify(customerService, never()).getCustomerStatusBreakdown();
    }
}
