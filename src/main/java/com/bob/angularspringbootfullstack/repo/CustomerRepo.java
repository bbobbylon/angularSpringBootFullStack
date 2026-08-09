package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Collection;
import java.util.List;

/**
 * CustomerRepo is the Spring Data JPA repository for {@link Customer} entities.
 * <p>
 * Extends both {@code PagingAndSortingRepository} for paginated retrieval and
 * {@code ListCrudRepository} for standard CRUD operations. Spring Data generates
 * all query implementations at runtime — no boilerplate required.
 */
public interface CustomerRepo extends PagingAndSortingRepository<Customer, Long>, ListCrudRepository<Customer, Long> {
    /**
     * Returns a paginated list of customers whose name contains the given string,
     * case-insensitively depending on the database collation.
     *
     * @param customerName the substring to search for within customer names
     * @param pageable     pagination and sorting parameters
     * @return a page of matching customers
     */
    Page<Customer> findByCustomerNameContaining(String customerName, Pageable pageable);

    /**
     * Returns a page of customers owned by any of the given organizations (FR-ORG-2).
     *
     * <p>The tenant filter is part of the query rather than something applied to the returned page,
     * which matters for pagination: filtering after the fact would make {@code totalElements} count
     * rows the caller may not see, and pages would arrive partially empty or short.
     *
     * <p>Customers with a {@code NULL organization_id} match no id and are therefore invisible to a
     * scoped caller — the safe direction, since an unowned row is not evidence of shared ownership.
     * Unscoped administrators do not use this method at all.
     *
     * @param organizationIds the caller's active organization ids; must not be empty (an empty
     *                        {@code IN} list is invalid SQL, and callers short-circuit that case)
     * @param pageable        pagination and sorting parameters
     * @return a page of customers belonging to those organizations
     */
    Page<Customer> findByOrganizationIdIn(Collection<Long> organizationIds, Pageable pageable);

    /**
     * Unpaginated form of {@link #findByOrganizationIdIn(Collection, Pageable)}, for the
     * dropdown/export call sites that already use the unscoped no-arg {@code findAll()} today
     * (the new-invoice customer picker, the XLSX report). Same NULL-{@code organization_id}
     * exclusion applies.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @return every customer belonging to those organizations, unpaginated
     */
    List<Customer> findByOrganizationIdIn(Collection<Long> organizationIds);

    /**
     * Org-scoped form of {@link #findByCustomerNameContaining(String, Pageable)} (FR-ORG-2), for
     * an org admin's use of {@code GET /customer/search} — otherwise a scoped caller could search
     * their way to a customer name outside their organizations even though the plain list is
     * scoped.
     *
     * @param customerName    the substring to search for within customer names
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param pageable        pagination and sorting parameters
     * @return a page of matching customers restricted to those organizations
     */
    Page<Customer> findByCustomerNameContainingAndOrganizationIdIn(
            String customerName, Collection<Long> organizationIds, Pageable pageable);
}
