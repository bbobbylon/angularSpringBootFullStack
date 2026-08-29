package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.ServicesCatalogService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_APPLICATION_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_HELP_DESK_ADMIN;
import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ORGANIZATION_ADMIN;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for organization scoping on {@link ServicesCatalogController}
 * (per-organization service catalogs, 2026-08-28) — same discipline as
 * {@link CustomerControllerOrgScopeTest}: this class's {@code @PreAuthorize
 * ("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")} alone lets both an unscoped platform operator
 * AND an org-scoped {@code ROLE_ORGANIZATION_ADMIN}/{@code ROLE_HELP_DESK_ADMIN} reach every
 * method here, so the class's own {@code requireVisible}/{@code requireManageable} checks are the
 * only thing standing between a scoped caller and another organization's private catalog. Every
 * positive assertion is paired with a {@code never()} on the sibling method/outcome, so a scoped
 * caller silently falling through to the unscoped branch (the exact bug
 * {@code CustomerControllerOrgScopeTest} found on a sibling controller) fails here.
 *
 * <p>Runs on plain Mockito, no Spring context — authority gating is the security filter chain's
 * job; this suite isolates the organization-scoping decision alone.
 */
@ExtendWith(MockitoExtension.class)
class ServicesCatalogControllerOrgScopeTest {

    private static final long CALLER_ID = 7L;
    private static final List<Long> ORG_IDS = List.of(1L, 4L);

    @Mock
    private ServicesCatalogService servicesCatalogService;
    @Mock
    private UserService userService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private ServicesCatalogController controller;

    @BeforeEach
    void stubPrincipalLookup() {
        // lenient(): several tests throw AccessDeniedException before the envelope is built (the
        // scope check runs first), which would otherwise fail strict-stubbing verification.
        lenient().when(userService.getUserByEmail(any())).thenReturn(new UserDTO());
    }

    private static UserDTO callerWithRole(String roleName) {
        UserDTO caller = new UserDTO();
        caller.setId(CALLER_ID);
        caller.setEmail("caller@example.com");
        caller.setRoleName(roleName);
        return caller;
    }

    private static Services serviceOwnedBy(Long organizationId) {
        Services service = new Services();
        service.setId(99L);
        service.setName("Consulting");
        service.setOrganizationId(organizationId);
        return service;
    }

    // ── listServices ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unscoped caller lists the whole catalog, not the scoped view")
    void unscopedListerSeesWholeCatalog() {
        when(servicesCatalogService.getAllServices()).thenReturn(List.of());

        controller.listServices(callerWithRole(ROLE_ADMIN.name()));

        verify(servicesCatalogService).getAllServices();
        verify(servicesCatalogService, never()).getAllServicesForOrganizations(any());
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    @Test
    @DisplayName("an org admin lists only the global entries plus their own organizations' entries")
    void scopedListerSeesOnlyOwnScope() {
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);
        when(servicesCatalogService.getAllServicesForOrganizations(ORG_IDS)).thenReturn(List.of());

        controller.listServices(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()));

        verify(servicesCatalogService).getAllServicesForOrganizations(ORG_IDS);
        verify(servicesCatalogService, never()).getAllServices();
    }

    // ── getService (requireVisible) ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a scoped caller CAN read a globally shared entry")
    void scopedCallerCanReadGlobalEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(null));

        assertDoesNotThrow(() -> controller.getService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L));
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    @Test
    @DisplayName("a scoped caller CAN read an entry owned by one of their own organizations")
    void scopedCallerCanReadOwnOrgEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(1L));
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);

        assertDoesNotThrow(() -> controller.getService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L));
    }

    @Test
    @DisplayName("a scoped caller CANNOT read an entry owned by another organization")
    void scopedCallerCannotReadOtherOrgEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(999L));
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);

        assertThrows(AccessDeniedException.class,
                () -> controller.getService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L));
    }

    @Test
    @DisplayName("an unscoped caller can read any entry regardless of ownership")
    void unscopedCallerCanReadAnyEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(999L));

        assertDoesNotThrow(() -> controller.getService(callerWithRole(ROLE_ADMIN.name()), 99L));
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    // ── createService (requireManageable) ────────────────────────────────────────────────────

    @Test
    @DisplayName("a scoped caller CANNOT create a globally shared entry")
    void scopedCallerCannotCreateGlobalEntry() {
        Services submitted = serviceOwnedBy(null);

        assertThrows(AccessDeniedException.class,
                () -> controller.createService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), submitted));
        verify(servicesCatalogService, never()).createService(any());
    }

    @Test
    @DisplayName("a scoped caller CAN create an entry owned by one of their own organizations")
    void scopedCallerCanCreateOwnOrgEntry() {
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);
        Services submitted = serviceOwnedBy(1L);
        when(servicesCatalogService.createService(submitted)).thenReturn(submitted);

        controller.createService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), submitted);

        verify(servicesCatalogService).createService(submitted);
    }

    @Test
    @DisplayName("a scoped caller CANNOT create an entry for another organization")
    void scopedCallerCannotCreateOtherOrgEntry() {
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);
        Services submitted = serviceOwnedBy(999L);

        assertThrows(AccessDeniedException.class,
                () -> controller.createService(callerWithRole(ROLE_HELP_DESK_ADMIN.name()), submitted));
        verify(servicesCatalogService, never()).createService(any());
    }

    @Test
    @DisplayName("an unscoped caller CAN create a globally shared entry")
    void unscopedCallerCanCreateGlobalEntry() {
        Services submitted = serviceOwnedBy(null);
        when(servicesCatalogService.createService(submitted)).thenReturn(submitted);

        controller.createService(callerWithRole(ROLE_APPLICATION_ADMIN.name()), submitted);

        verify(servicesCatalogService).createService(submitted);
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }

    // ── updateService / setServiceActive (requireManageable on the EXISTING row) ────────────

    @Test
    @DisplayName("a scoped caller CANNOT edit an entry owned by another organization")
    void scopedCallerCannotEditOtherOrgEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(999L));
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);
        Services edits = serviceOwnedBy(999L);

        assertThrows(AccessDeniedException.class,
                () -> controller.updateService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L, edits));
        verify(servicesCatalogService, never()).updateService(anyLong(), any());
    }

    @Test
    @DisplayName("a scoped caller CAN edit an entry owned by their own organization")
    void scopedCallerCanEditOwnOrgEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(1L));
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);
        Services edits = serviceOwnedBy(1L);
        when(servicesCatalogService.updateService(99L, edits)).thenReturn(edits);

        controller.updateService(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L, edits);

        verify(servicesCatalogService).updateService(99L, edits);
    }

    @Test
    @DisplayName("a scoped caller CANNOT retire a globally shared entry")
    void scopedCallerCannotRetireGlobalEntry() {
        when(servicesCatalogService.getService(99L)).thenReturn(serviceOwnedBy(null));

        assertThrows(AccessDeniedException.class,
                () -> controller.setServiceActive(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L, false));
        verify(servicesCatalogService, never()).setServiceActive(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a scoped caller CAN retire an entry owned by their own organization")
    void scopedCallerCanRetireOwnOrgEntry() {
        Services owned = serviceOwnedBy(1L);
        when(servicesCatalogService.getService(99L)).thenReturn(owned);
        when(organizationService.findActiveOrganizationIds(CALLER_ID)).thenReturn(ORG_IDS);
        when(servicesCatalogService.setServiceActive(99L, false)).thenReturn(owned);

        assertDoesNotThrow(() -> controller.setServiceActive(callerWithRole(ROLE_ORGANIZATION_ADMIN.name()), 99L, false));
        verify(servicesCatalogService).setServiceActive(99L, false);
    }

    @Test
    @DisplayName("an unscoped caller CAN retire any entry regardless of ownership")
    void unscopedCallerCanRetireAnyEntry() {
        Services owned = serviceOwnedBy(999L);
        when(servicesCatalogService.getService(99L)).thenReturn(owned);
        when(servicesCatalogService.setServiceActive(99L, false)).thenReturn(owned);

        assertDoesNotThrow(() -> controller.setServiceActive(callerWithRole(ROLE_ADMIN.name()), 99L, false));
        verify(organizationService, never()).findActiveOrganizationIds(anyLong());
    }
}
