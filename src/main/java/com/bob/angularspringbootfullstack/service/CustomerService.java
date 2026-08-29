package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.model.Stats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.Map;

/**
 * CustomerService defines the service layer contract for all customer and invoice operations.
 * <p>
 * Implementations are responsible for interacting with the persistence layer to create,
 * read, update, and search customers and invoices. All paginated methods use zero-based
 * page indexing consistent with Spring Data's {@code PageRequest} convention.
 */
public interface CustomerService {
    /**
     * Creates a new customer record, setting the creation timestamp automatically.
     *
     * @param customer the customer data to persist
     * @return the saved customer with its generated ID and createdAt populated
     */
    Customer createCustomer(Customer customer);

    /**
     * Updates an existing customer record by loading it from the database and
     * applying only the editable fields from the provided data.
     * <p>
     * Protected fields ({@code id}, {@code createdAt}, {@code invoices}) are
     * never overwritten, regardless of what the request body contains.
     *
     * @param customerId the ID of the customer to update
     * @param customer   the inbound data containing the fields to apply
     * @return the updated customer as persisted
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no customer with the given ID exists
     */
    Customer updateCustomer(Long customerId, Customer customer);

    /**
     * Attaches existing, currently-unassigned customers to an organization — the "tie invoices to
     * the org at creation" step of Organization CRUD (org setup). Invoices are not touched
     * directly: an invoice belongs to a customer via {@code Invoice.customerId}, not to an
     * organization, so they become visible under the organization implicitly once their customer
     * does.
     *
     * <p>Deliberately scoped to customers with no organization yet — an id belonging to a customer
     * already assigned elsewhere is silently skipped rather than reassigned, since moving a
     * customer that already belongs to another organization is a more sensitive action than
     * attaching new business to a brand-new org, and is left to a dedicated flow rather than being
     * a side effect of creating an unrelated organization.
     *
     * @param customerIds    the customers to attach; unknown ids and ids already assigned to any
     *                       organization are silently skipped
     * @param organizationId the organization to attach them to
     * @return how many customers were actually reassigned
     */
    int assignCustomersToOrganization(Collection<Long> customerIds, Long organizationId);

    /**
     * Returns a paginated page of all customers.
     *
     * @param page zero-based page index
     * @param size number of records per page
     * @param sort the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of customers
     */
    Page<Customer> getCustomers(int page, int size, Sort sort);

    /**
     * Returns all customers without pagination.
     * Intended for use cases such as populating dropdown lists.
     *
     * @return all customer records
     */
    Iterable<Customer> getCustomers();

    /**
     * Retrieves a single customer by their unique ID.
     *
     * @param customerId the ID of the customer to retrieve
     * @return the matching customer
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no customer with the given ID exists
     */
    Customer getCustomer(Long customerId);

    /**
     * Creates a new invoice, generating a unique invoice number automatically.
     *
     * @param invoice the invoice data to persist
     * @return the saved invoice with its generated ID and invoice number populated
     */
    Invoice createInvoice(Invoice invoice);

    /**
     * Returns a paginated page of all invoices.
     *
     * @param page zero-based page index
     * @param size number of records per page
     * @param sort the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of invoices
     */
    Page<Invoice> getInvoices(int page, int size, Sort sort);

    /**
     * Returns all invoices without pagination, for use in report generation.
     *
     * @return an {@link Iterable} of every {@link Invoice} in the system
     */
    Iterable<Invoice> getInvoices();

    /**
     * Creates a new invoice and associates it with an existing customer.
     * Generates a unique invoice number automatically.
     *
     * @param customerId the ID of the customer to attach the invoice to
     * @param invoice    the invoice data to persist
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no customer with the given ID exists
     */
    void addInvoiceToCustomer(Long customerId, Invoice invoice);

    /**
     * Applies edits to an existing invoice.
     *
     * <p>Invoices were create-only until now, which meant a typo in an amount or a status that
     * needed correcting could only be fixed by raising a second invoice — leaving the wrong one
     * permanently in the customer's history and in every revenue total derived from it.
     *
     * <p>Three fields are deliberately <b>not</b> taken from the caller, whatever the request body
     * contains: the invoice number (a stable external reference — changing it would break every
     * document already sent to the customer), the creation identity of the row, and the owning
     * customer. Reassigning an invoice to a different customer is a different operation with
     * different consequences for both parties' billing histories, and it has its own endpoint
     * ({@link #linkInvoiceToCustomer}) rather than hiding inside a general edit.
     *
     * @param invoiceId the id of the invoice to edit
     * @param edits     the submitted values; only the editable fields are read
     * @return the updated invoice
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no invoice with the given ID exists
     */
    Invoice updateInvoice(Long invoiceId, Invoice edits);

    /**
     * Attaches an existing standalone (draft) invoice to a customer.
     *
     * <p>The other half of {@code POST /invoice/create}: that endpoint raises an invoice with no
     * customer, and this one gives it an owner once it is known. Distinct from
     * {@link #addInvoiceToCustomer}, which creates a brand-new invoice already attached — the two
     * differ in whether the invoice exists yet, which is why they are separate rather than one
     * method with a nullable id.
     *
     * @param invoiceId  the id of the existing invoice
     * @param customerId the id of the customer to attach it to
     * @return the invoice, now owned by the customer
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if either id does not exist
     */
    Invoice linkInvoiceToCustomer(Long invoiceId, Long customerId);

    /**
     * Returns a paginated page of customers whose name contains the given search term.
     *
     * @param name the substring to search for within customer names
     * @param page zero-based page index
     * @param size number of records per page
     * @param sort the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of matching customers
     */
    Page<Customer> searchCustomers(String name, int page, int size, Sort sort);

    /**
     * Retrieves a single invoice by its unique ID.
     *
     * @param invoiceId the ID of the invoice to retrieve
     * @return the matching invoice
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no invoice with the given ID exists
     */
    Invoice getInvoice(Long invoiceId);

    /**
     * Returns the entries from the {@link Services} catalog that are still on offer.
     * <p>
     * Used to populate the service dropdown on the new-invoice form so users
     * can select from predefined offerings rather than entering free text.
     *
     * <p>Retired services are excluded. Offering a discontinued service on a new invoice is worse
     * than merely untidy — it is how a business accidentally sells something it no longer
     * provides. Administrators see the full catalog, retired entries included, through
     * {@code ServicesCatalogService}.
     *
     * @return the active service catalog entries, unpaginated
     */
    Iterable<Services> getServices();

    /**
     * Returns aggregated dashboard statistics: total customers, total invoices,
     * and the sum of all invoice {@code totalAmount} values.
     *
     * @return a {@link Stats} record with the current system-wide counts and totals
     */
    Stats getStats();

    /**
     * Returns the system-wide breakdown of customers by account status.
     * <p>
     * The result preserves insertion order (largest status first) so the home
     * dashboard donut and its legend render deterministically. Keys are the raw
     * status strings stored on each customer; values are the counts.
     *
     * @return an ordered map of status → customer count across the whole table
     */
    Map<String, Integer> getCustomerStatusBreakdown();

    // ── Organization-scoped reporting (FR-ORG-2) ────────────────────────────────────────────
    // These mirror the four methods above but restrict every row to customers owned by the
    // caller's organizations. They are deliberately SEPARATE methods rather than an extra
    // nullable parameter on the existing ones: a null "no scope" argument would make the
    // unrestricted case the default that any caller gets by forgetting to pass anything, and a
    // security boundary should not be something you opt into. Call sites now state which view
    // they want, and AnalyticsController is the one place that decides which applies.
    //
    // Every method requires a NON-EMPTY collection. An empty organization set means the caller
    // belongs to no active organization and must see nothing, which is the caller's decision to
    // enforce — quietly returning system-wide data on empty is precisely the failure this
    // feature exists to prevent, and an empty SQL `IN ()` list is invalid anyway.

    /**
     * Paginated customers owned by the given organizations.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param page            zero-based page index
     * @param size            records per page
     * @param sort            the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of customers restricted to those organizations
     */
    Page<Customer> getCustomersForOrganizations(Collection<Long> organizationIds, int page, int size, Sort sort);

    /**
     * Paginated invoices billed to customers owned by the given organizations.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param page            zero-based page index
     * @param size            records per page
     * @param sort            the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of invoices restricted to those organizations
     */
    Page<Invoice> getInvoicesForOrganizations(Collection<Long> organizationIds, int page, int size, Sort sort);

    /**
     * Aggregated dashboard statistics restricted to the given organizations.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @return counts and billed total covering only those organizations
     */
    Stats getStatsForOrganizations(Collection<Long> organizationIds);

    /**
     * Customer status breakdown restricted to the given organizations.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @return an ordered map of status → customer count within those organizations
     */
    Map<String, Integer> getCustomerStatusBreakdownForOrganizations(Collection<Long> organizationIds);

    /**
     * Every customer owned by the given organizations, unpaginated — the scoped form of
     * {@link #getCustomers()}, for the new-invoice customer picker and the XLSX export.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @return every customer belonging to those organizations
     */
    Iterable<Customer> getCustomersForOrganizations(Collection<Long> organizationIds);

    /**
     * Every invoice billed to customers owned by the given organizations, unpaginated — the
     * scoped form of {@link #getInvoices()}, for the XLSX export.
     *
     * @param organizationIds the caller's active organization ids; must not be empty
     * @return every invoice belonging to those organizations
     */
    Iterable<Invoice> getInvoicesForOrganizations(Collection<Long> organizationIds);

    /**
     * Paginated, name-filtered customers restricted to the given organizations — the scoped form
     * of {@link #searchCustomers(String, int, int)}.
     *
     * @param name            the substring to search for within customer names
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param page            zero-based page index
     * @param size            records per page
     * @param sort            the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of matching customers restricted to those organizations
     */
    Page<Customer> searchCustomersForOrganizations(String name, Collection<Long> organizationIds, int page, int size, Sort sort);

    /**
     * Returns a paginated page of invoices whose invoice number or owning customer's name
     * contains the given search term.
     *
     * @param term the substring to search for, matched against both the invoice number and
     *             the owning customer's name
     * @param page zero-based page index
     * @param size number of records per page
     * @param sort the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of matching invoices
     */
    Page<Invoice> searchInvoices(String term, int page, int size, Sort sort);

    /**
     * Org-scoped form of {@link #searchInvoices(String, int, int)} (FR-ORG-2), restricted to
     * invoices billed to customers owned by the given organizations.
     *
     * @param term            the substring to search for
     * @param organizationIds the caller's active organization ids; must not be empty
     * @param page            zero-based page index
     * @param size            records per page
     * @param sort            the column(s) to order by; {@link Sort#unsorted()} preserves insertion order
     * @return a page of matching invoices restricted to those organizations
     */
    Page<Invoice> searchInvoicesForOrganizations(String term, Collection<Long> organizationIds, int page, int size, Sort sort);
}
