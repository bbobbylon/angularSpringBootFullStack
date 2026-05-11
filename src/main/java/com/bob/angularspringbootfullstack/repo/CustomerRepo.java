package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

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
}
