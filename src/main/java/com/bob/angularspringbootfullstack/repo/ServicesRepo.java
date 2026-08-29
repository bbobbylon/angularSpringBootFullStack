package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Services;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Collection;
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

    // ── Organization-scoped catalog (per-organization services) ────────────────────────────
    // Deliberately four narrow, single-predicate finders rather than one derived method mixing
    // AND/OR (e.g. "ActiveTrueAndOrganizationIdIsNullOrOrganizationIdIn", which Spring Data would
    // parse as "(active=true AND org IS NULL) OR org IN (...)" — silently including inactive
    // org-owned rows). The service layer unions the "global" and "owned" results itself, which
    // also sidesteps JPQL's rejection of an empty `IN ()` list when a caller belongs to zero
    // active organizations: that case simply skips the *In query entirely.

    /**
     * The active, globally shared catalog entries (visible to every caller).
     *
     * @return active entries with a null {@code organizationId}
     */
    List<Services> findByActiveTrueAndOrganizationIdIsNull();

    /**
     * Every globally shared catalog entry, retired included — for a scoped caller's admin view.
     *
     * @return all entries with a null {@code organizationId}
     */
    List<Services> findByOrganizationIdIsNull();

    /**
     * The active catalog entries privately owned by any of the given organizations.
     *
     * @param organizationIds must not be empty — callers skip this query when the caller's scope is empty
     * @return active entries whose {@code organizationId} is one of {@code organizationIds}
     */
    List<Services> findByActiveTrueAndOrganizationIdIn(Collection<Long> organizationIds);

    /**
     * Every catalog entry privately owned by any of the given organizations, retired included.
     *
     * @param organizationIds must not be empty — callers skip this query when the caller's scope is empty
     * @return all entries whose {@code organizationId} is one of {@code organizationIds}
     */
    List<Services> findByOrganizationIdIn(Collection<Long> organizationIds);
}
