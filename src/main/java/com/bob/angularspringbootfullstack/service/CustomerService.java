package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.model.Stats;
import org.springframework.data.domain.Page;

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
     * Returns a paginated page of all customers.
     *
     * @param page zero-based page index
     * @param size number of records per page
     * @return a page of customers
     */
    Page<Customer> getCustomers(int page, int size);

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
     * @return a page of invoices
     */
    Page<Invoice> getInvoices(int page, int size);

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
     * Returns a paginated page of customers whose name contains the given search term.
     *
     * @param name the substring to search for within customer names
     * @param page zero-based page index
     * @param size number of records per page
     * @return a page of matching customers
     */
    Page<Customer> searchCustomers(String name, int page, int size);

    /**
     * Retrieves a single invoice by its unique ID.
     *
     * @param invoiceId the ID of the invoice to retrieve
     * @return the matching invoice
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no invoice with the given ID exists
     */
    Invoice getInvoice(Long invoiceId);

    /**
     * Returns all entries from the {@link Services} catalog.
     * <p>
     * Used to populate the service dropdown on the new-invoice form so users
     * can select from predefined offerings rather than entering free text.
     *
     * @return all service catalog entries, unpaginated
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
}
