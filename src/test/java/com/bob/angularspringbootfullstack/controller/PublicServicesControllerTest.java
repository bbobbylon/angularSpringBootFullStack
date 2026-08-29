package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.service.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the fix in {@link PublicServicesController} made alongside per-organization service
 * catalogs (2026-08-28): an anonymous visitor must see only the globally shared catalog, never a
 * private organization's entries. Before this fix the controller called the unfiltered
 * {@code CustomerService#getServices()}, which would have leaked private catalog rows to anyone
 * once they existed — this pins the correct call, {@code getServicesForOrganizations(List.of())}.
 */
@ExtendWith(MockitoExtension.class)
class PublicServicesControllerTest {

    @Mock
    private CustomerService customerService;

    @InjectMocks
    private PublicServicesController controller;

    @Test
    @DisplayName("public catalog browsing calls the empty-scope (global-only) query, not the unfiltered one")
    void listPublicServices_usesEmptyScopeNotUnfiltered() {
        when(customerService.getServicesForOrganizations(List.of())).thenReturn(List.of());

        controller.listPublicServices();

        verify(customerService).getServicesForOrganizations(List.of());
        verify(customerService, never()).getServices();
    }
}
