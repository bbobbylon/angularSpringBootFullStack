package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.repo.ServicesRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link ServicesCatalogServiceImpl} — no Spring context, {@link ServicesRepo}
 * mocked with Mockito. Covers the per-organization catalog additions (2026-08-28):
 * {@code getAllServicesForOrganizations}'s global/owned union, and {@code updateService}'s
 * deliberate exclusion of {@code organizationId} from an edit.
 */
@ExtendWith(MockitoExtension.class)
class ServicesCatalogServiceImplTest {

    @Mock
    private ServicesRepo servicesRepo;

    @InjectMocks
    private ServicesCatalogServiceImpl catalogService;

    @Test
    @DisplayName("getAllServicesForOrganizations unions global entries with the given organizations' entries, retired included")
    void getAllServicesForOrganizations_unionsGlobalAndOwned() {
        Services global = new Services();
        global.setId(1L);
        Services retiredOwned = new Services();
        retiredOwned.setId(2L);
        retiredOwned.setOrganizationId(4L);
        retiredOwned.setActive(false);
        when(servicesRepo.findByOrganizationIdIsNull()).thenReturn(List.of(global));
        when(servicesRepo.findByOrganizationIdIn(List.of(4L))).thenReturn(List.of(retiredOwned));

        List<Services> visible = catalogService.getAllServicesForOrganizations(List.of(4L));

        assertThat(visible).containsExactly(global, retiredOwned);
    }

    @Test
    @DisplayName("getAllServicesForOrganizations with an empty scope still returns the global entries")
    void getAllServicesForOrganizations_emptyScope_returnsGlobalOnly() {
        Services global = new Services();
        global.setId(1L);
        when(servicesRepo.findByOrganizationIdIsNull()).thenReturn(List.of(global));

        List<Services> visible = catalogService.getAllServicesForOrganizations(List.of());

        assertThat(visible).containsExactly(global);
        verify(servicesRepo, never()).findByOrganizationIdIn(any());
    }

    @Test
    @DisplayName("updateService does not let an edit reassign organizationId")
    void updateService_leavesOrganizationIdUnchanged() {
        Services existing = new Services();
        existing.setId(5L);
        existing.setName("Old name");
        existing.setOrganizationId(1L);
        when(servicesRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(servicesRepo.save(any(Services.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Services edits = new Services();
        edits.setName("New name");
        edits.setOrganizationId(999L); // attempted reassignment — must be ignored
        ArgumentCaptor<Services> captor = ArgumentCaptor.forClass(Services.class);

        catalogService.updateService(5L, edits);

        verify(servicesRepo).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("New name");
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(1L);
    }
}
