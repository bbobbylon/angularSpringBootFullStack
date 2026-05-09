package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Invoice;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

/**
 * InvoiceRepo is the Spring Data JPA repository for {@link Invoice} entities.
 * <p>
 * Extends both {@code PagingAndSortingRepository} for paginated retrieval and
 * {@code ListCrudRepository} for standard CRUD operations. Custom query methods
 * can be added here as invoice-related query needs grow.
 */
public interface InvoiceRepo extends PagingAndSortingRepository<Invoice, Long>, ListCrudRepository<Invoice, Long> {
}
