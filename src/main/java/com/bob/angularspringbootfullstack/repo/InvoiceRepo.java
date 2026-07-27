package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

/**
 * InvoiceRepo is the Spring Data JPA repository for {@link Invoice} entities.
 * <p>
 * Extends both {@code PagingAndSortingRepository} for paginated retrieval and
 * {@code ListCrudRepository} for standard CRUD operations. Custom query methods
 * can be added here as invoice-related query needs grow.
 */
public interface InvoiceRepo extends PagingAndSortingRepository<Invoice, Long>, ListCrudRepository<Invoice, Long> {
    // TODO(human): add a @Query method that returns the sum of all invoice totalAmount values.
    // The method should be named sumTotalBilled() and return a Double.
    // Use COALESCE in your JPQL so it returns 0 instead of null when there are no invoices.

    /**
     * Returns a page of invoices belonging to any of the given organizations (FR-ORG-2).
     *
     * <p>Invoices carry no tenant column of their own; they inherit it from the customer they
     * bill, which is why this traverses {@code invoice.customer.organizationId} rather than
     * filtering a local field. Keeping ownership in one place means reassigning a customer moves
     * its invoices with it automatically, and there is no second column to drift out of sync.
     *
     * <p>Written as an explicit {@code @Query} rather than a derived method name: the derived form
     * ({@code findByCustomer_OrganizationIdIn}) works but reads as an implementation detail of
     * Spring Data's parser, and the join is worth stating plainly given it is the security
     * boundary.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param pageable        pagination and sorting parameters
     * @return a page of invoices billed to customers owned by those organizations
     */
    @Query("SELECT i FROM Invoice i WHERE i.customer.organizationId IN :organizationIds")
    Page<Invoice> findByOrganizationIdIn(@Param("organizationIds") Collection<Long> organizationIds,
                                         Pageable pageable);
}
