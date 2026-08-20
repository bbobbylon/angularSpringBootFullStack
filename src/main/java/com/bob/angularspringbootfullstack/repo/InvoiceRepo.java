package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /**
     * Unpaginated form of {@link #findByOrganizationIdIn(Collection, Pageable)}, for the XLSX
     * export call site that already uses the unscoped no-arg {@code findAll()} today. Same
     * customer-join and draft-invoice (no customer) exclusion applies.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @return every invoice billed to customers owned by those organizations, unpaginated
     */
    @Query("SELECT i FROM Invoice i WHERE i.customer.organizationId IN :organizationIds")
    List<Invoice> findByOrganizationIdIn(@Param("organizationIds") Collection<Long> organizationIds);

    /**
     * Returns a page of invoices whose invoice number or owning customer's name contains the
     * given term (case-insensitive) — the search half of {@code GET /customer/invoice/search}.
     *
     * <p>One term matched against two columns, mirroring how {@code CustomerRepo}'s search matches
     * a single field: a reader searching invoices rarely knows which of "the invoice number" or
     * "the customer's name" they actually remember, so requiring them to pick the right field first
     * would defeat the point of a search box. A draft invoice (no customer yet) still matches on
     * its invoice number since {@code customer} being {@code null} only fails the second half of
     * the {@code OR}, never the query itself.
     *
     * @param term     the substring to search for; matched against both columns
     * @param pageable pagination and sorting parameters
     * @return a page of invoices whose number or customer name contains the term
     */
    @Query("SELECT i FROM Invoice i WHERE LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :term, '%')) "
            + "OR LOWER(i.customer.customerName) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<Invoice> searchByInvoiceNumberOrCustomerName(@Param("term") String term, Pageable pageable);

    /**
     * Org-scoped form of {@link #searchByInvoiceNumberOrCustomerName(String, Pageable)} (FR-ORG-2),
     * for an org admin's use of {@code GET /customer/invoice/search} — otherwise a scoped caller
     * could search their way to an invoice outside their organizations even though the plain list
     * is scoped.
     *
     * @param term            the substring to search for; matched against both columns
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param pageable        pagination and sorting parameters
     * @return a page of matching invoices restricted to those organizations
     */
    @Query("SELECT i FROM Invoice i WHERE i.customer.organizationId IN :organizationIds "
            + "AND (LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :term, '%')) "
            + "OR LOWER(i.customer.customerName) LIKE LOWER(CONCAT('%', :term, '%')))")
    Page<Invoice> searchByInvoiceNumberOrCustomerNameAndOrganizationIdIn(
            @Param("term") String term, @Param("organizationIds") Collection<Long> organizationIds, Pageable pageable);

    /**
     * Looks up an invoice by its exact invoice number — the dedupe key
     * {@code BatchImportServiceImpl} checks for a row that supplies its own invoice number,
     * so re-importing a spreadsheet that already carried one doesn't raise a second invoice
     * under the same reference.
     *
     * @param invoiceNumber the exact invoice number to match
     * @return the matching invoice, if one exists
     */
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
}
