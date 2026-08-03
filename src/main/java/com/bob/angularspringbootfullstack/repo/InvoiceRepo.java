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

    // NOTE: a `sumTotalBilled()` aggregate was once planned here and is deliberately NOT added.
    // That total already exists, computed in SQL, in both the unscoped and org-scoped stats
    // queries (`CustomerQuery.STATS_QUERY` / `STATS_BY_ORGANIZATION_QUERY`), which is what
    // populates `Stats.totalBilled` and the dashboard's Total Billed tile. Adding a second
    // aggregate over the same column would create a competing source of truth for one number —
    // the exact shape of problem this codebase has been unwinding elsewhere (two CORS configs,
    // two exception advices). If the JPA side ever needs the figure, call the existing service.

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
