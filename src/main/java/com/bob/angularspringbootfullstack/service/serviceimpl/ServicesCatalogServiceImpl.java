package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.repo.ServicesRepo;
import com.bob.angularspringbootfullstack.service.ServicesCatalogService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JPA implementation of {@link ServicesCatalogService}.
 *
 * <p>Thin by design — the catalog is four columns and has no invariants beyond "a service has a
 * name". What little logic exists lives here rather than in the controller, per the project's rule
 * that request handling and business decisions stay separate: defaulting a new entry to active,
 * and refusing an entry with no name, are both decisions about what a valid service is, not about
 * how HTTP works.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ServicesCatalogServiceImpl implements ServicesCatalogService {

    private final ServicesRepo servicesRepo;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Services> getAllServices() {
        return servicesRepo.findAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Services> getAllServicesForOrganizations(Collection<Long> organizationIds) {
        List<Services> visible = new ArrayList<>(servicesRepo.findByOrganizationIdIsNull());
        if (!organizationIds.isEmpty()) {
            visible.addAll(servicesRepo.findByOrganizationIdIn(organizationIds));
        }
        return visible;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Services getService(Long serviceId) {
        return servicesRepo.findById(serviceId)
                .orElseThrow(() -> new ApiException("Service not found"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Services createService(Services service) {
        requireName(service);
        // The id is cleared rather than trusted: a client that echoes back an existing id would
        // otherwise turn a create into a silent overwrite of a different catalog entry.
        service.setId(null);
        if (service.getActive() == null) {
            service.setActive(true);
        }
        Services saved = servicesRepo.save(service);
        log.debug("[SERVICES] Created catalog entry '{}' (id={})", saved.getName(), saved.getId());
        return saved;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Services updateService(Long serviceId, Services edits) {
        requireName(edits);
        Services service = getService(serviceId);

        // Field-by-field rather than saving the submitted object, for the same reason invoice
        // edits work this way: the request body is partial, so persisting it wholesale would write
        // null over every field the client did not send.
        service.setName(edits.getName());
        service.setDescription(edits.getDescription());
        service.setPrice(edits.getPrice());
        // Retirement is intentionally not editable here — it has its own operation, so that
        // "correct this description" can never accidentally pull a live service off the menu.
        // organizationId is likewise excluded: it is set once at creation (mirroring
        // Organization.tenantUuid's settable-once shape) and never reassigned through an edit, so
        // "fix this service's price" can never double as a silent transfer of a private catalog
        // entry to a different organization, or a private entry quietly becoming global.
        return servicesRepo.save(service);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Services setServiceActive(Long serviceId, boolean active) {
        Services service = getService(serviceId);
        service.setActive(active);
        log.debug("[SERVICES] Catalog entry id={} is now {}", serviceId, active ? "active" : "retired");
        return servicesRepo.save(service);
    }

    /**
     * Rejects a service with no usable name.
     *
     * <p>Enforced here as well as by bean validation on the request body, because this is the
     * invariant that makes the catalog usable at all: a nameless entry renders as a blank row in
     * the invoice pick-list, which a user can select without being able to tell what they chose.
     *
     * @param service the submitted service
     * @throws ApiException when the name is absent or blank
     */
    private static void requireName(Services service) {
        if (service.getName() == null || service.getName().isBlank()) {
            throw new ApiException("A service name is required");
        }
    }
}
