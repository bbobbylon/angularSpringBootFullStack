package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Services;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * ServicesRepo is the Spring Data JPA repository for {@link Services} catalog entries.
 * <p>
 * Extends {@code ListCrudRepository} for standard CRUD operations. The catalog is
 * read-only from the invoice creation flow — services are chosen there, and managed
 * through the admin-gated {@code /admin/services} endpoints.
 */
public interface ServicesRepo extends ListCrudRepository<Services, Long> {

    /**
     * Returns only the services still on offer, for the invoice form and the public catalog.
     *
     * <p>The distinction between this and {@link #findAll()} is the whole reason retirement is a
     * flag rather than a delete: a retired service must disappear from the pick-list on a new
     * invoice while remaining visible — and reactivatable — in the administrative view. A single
     * unfiltered method would force every caller to remember which of the two it wanted, and the
     * one that forgot would quietly re-offer a discontinued product.
     *
     * @return the active catalog entries, in no particular order
     */
    List<Services> findByActiveTrue();
}
