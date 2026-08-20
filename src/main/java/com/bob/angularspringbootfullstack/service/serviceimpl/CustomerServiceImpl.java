package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.repo.CustomerRepo;
import com.bob.angularspringbootfullstack.repo.InvoiceRepo;
import com.bob.angularspringbootfullstack.repo.ServicesRepo;
import com.bob.angularspringbootfullstack.rowmapper.StatsRowMapper;
import com.bob.angularspringbootfullstack.service.CustomerService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.CustomerQuery.CUSTOMER_STATUS_BREAKDOWN_BY_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.CustomerQuery.CUSTOMER_STATUS_BREAKDOWN_QUERY;
import static com.bob.angularspringbootfullstack.query.CustomerQuery.STATS_BY_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.CustomerQuery.STATS_QUERY;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.springframework.data.domain.PageRequest.of;

/**
 * CustomerServiceImpl is the primary implementation of {@link CustomerService}.
 * <p>
 * Delegates all persistence operations to {@link CustomerRepo} and {@link InvoiceRepo}.
 * Invoice numbers are generated automatically using a 10-character random alphanumeric
 * string to ensure uniqueness across the system.
 * <p>
 * All methods that look up by ID throw {@link com.bob.angularspringbootfullstack.exception.ApiException}
 * when no matching record is found, which the {@code GlobalExceptionHandler} converts
 * into a structured HTTP error response.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {
    /**
     * JPA repository for {@link Customer} persistence operations.
     * Injected by Lombok's {@code @RequiredArgsConstructor}.
     */
    private final CustomerRepo customerRepo;

    /**
     * JPA repository for {@link Invoice} persistence operations.
     * Injected by Lombok's {@code @RequiredArgsConstructor}.
     */
    private final InvoiceRepo invoiceRepo;

    /**
     * JPA repository for {@link Services} catalog entries.
     * Used to populate the service dropdown on the new-invoice form.
     * Injected by Lombok's {@code @RequiredArgsConstructor}.
     */
    private final ServicesRepo servicesRepo;

    /**
     * Named-parameter JDBC template used for the aggregated stats query.
     * <p>
     * Used instead of JPA because {@link com.bob.angularspringbootfullstack.model.Stats}
     * is not a managed entity — its values are computed by a raw SQL query
     * defined in {@link com.bob.angularspringbootfullstack.query.CustomerQuery#STATS_QUERY}.
     */
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     * Sets {@code createdAt} to the current date before persisting.
     */
    @Override
    public Customer createCustomer(Customer customer) {
        customer.setCreatedAt(new Date());
        return customerRepo.save(customer);
    }

    /**
     * {@inheritDoc}
     * Loads the existing customer from the database and applies only the editable
     * fields: name, type, email, phoneNumber, address, status, and imageUrl.
     * The id, createdAt, and invoices fields are always preserved from the DB record.
     */
    @Override
    public Customer updateCustomer(Long customerId, Customer customer) {
        Customer existingCustomer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ApiException("Customer not found"));
        existingCustomer.setCustomerName(customer.getCustomerName());
        existingCustomer.setType(customer.getType());
        existingCustomer.setEmail(customer.getEmail());
        existingCustomer.setPhoneNumber(customer.getPhoneNumber());
        existingCustomer.setAddress(customer.getAddress());
        existingCustomer.setStatus(customer.getStatus());
        existingCustomer.setImageUrl(customer.getImageUrl());
        return customerRepo.save(existingCustomer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Customer> getCustomers(int page, int size, Sort sort) {
        return customerRepo.findAll(of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Customer> getCustomers() {
        return customerRepo.findAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Customer getCustomer(Long customerId) {
        return customerRepo.findById(customerId)
                .orElseThrow(() -> new ApiException("Customer not found"));
    }

    /**
     * {@inheritDoc}
     * Generates a 10-character alphanumeric invoice number before persisting.
     */
    @Override
    public Invoice createInvoice(Invoice invoice) {
        invoice.setInvoiceNumber(randomAlphanumeric(10).toUpperCase());
        return invoiceRepo.save(invoice);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Invoice> getInvoices(int page, int size, Sort sort) {
        return invoiceRepo.findAll(of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Invoice> getInvoices() {
        return invoiceRepo.findAll();
    }

    /**
     * {@inheritDoc}
     * Generates a 10-character alphanumeric invoice number before associating the invoice
     * with the customer and persisting.
     */
    @Override
    public void addInvoiceToCustomer(Long customerId, Invoice invoice) {
        invoice.setInvoiceNumber(randomAlphanumeric(10).toUpperCase());
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ApiException("Customer not found"));
        invoice.setCustomer(customer);
        invoiceRepo.save(invoice);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Copies only the editable fields onto the managed entity rather than saving the submitted
     * object outright. Saving the request body directly would be shorter and wrong: the client
     * sends a partially-populated {@code Invoice}, so every field it omits — the invoice number,
     * the owning customer, the line items — would be written back as {@code null}, silently
     * erasing them. Reading the row first and assigning field by field means an edit can only ever
     * change what it names.
     *
     * <p>{@code services} is replaced only when the caller actually sends a list. An edit to a
     * status or an amount has no business clearing an invoice's line items, and a client that does
     * not render them has no way to send them back.
     */
    @Override
    public Invoice updateInvoice(Long invoiceId, Invoice edits) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found"));

        invoice.setStatus(edits.getStatus());
        invoice.setTotalAmount(edits.getTotalAmount());
        invoice.setAmount(edits.getAmount());
        invoice.setInvoiceDate(edits.getInvoiceDate());
        if (edits.getServices() != null && !edits.getServices().isEmpty()) {
            invoice.setServices(edits.getServices());
        }
        return invoiceRepo.save(invoice);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both rows are loaded before anything is written, so a bad customer id fails without
     * having half-applied the change.
     */
    @Override
    public Invoice linkInvoiceToCustomer(Long invoiceId, Long customerId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found"));
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ApiException("Customer not found"));

        invoice.setCustomer(customer);
        // Kept in step with the JPA association: customerId is a denormalized column used by the
        // direct queries, and a row whose two ownership fields disagree is a bug waiting to be
        // reported as "the invoice is on the wrong customer" by whichever query happens to run.
        invoice.setCustomerId(customerId);
        return invoiceRepo.save(invoice);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Customer> searchCustomers(String customerName, int page, int size, Sort sort) {
        return customerRepo.findByCustomerNameContaining(customerName, of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Invoice getInvoice(Long invoiceId) {
        return invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Services> getServices() {
        return servicesRepo.findByActiveTrue();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stats getStats() {
        return jdbcTemplate.queryForObject(STATS_QUERY, Map.of(), new StatsRowMapper());
    }

    /**
     * {@inheritDoc}
     * Runs a single {@code GROUP BY status} aggregation and folds the rows into an
     * insertion-ordered {@link LinkedHashMap} (the query already orders by descending
     * count). A {@code ResultSetExtractor} is used rather than a dedicated model +
     * RowMapper because the result is fundamentally key/value data, not an entity.
     * A null status is coalesced to {@code "UNKNOWN"} so it never collapses into a
     * blank legend entry.
     */
    @Override
    public Map<String, Integer> getCustomerStatusBreakdown() {
        return jdbcTemplate.query(CUSTOMER_STATUS_BREAKDOWN_QUERY, Map.of(), rs -> {
            Map<String, Integer> breakdown = new LinkedHashMap<>();
            while (rs.next()) {
                String status = rs.getString("status");
                breakdown.put(status != null ? status : "UNKNOWN", rs.getInt("count"));
            }
            return breakdown;
        });
    }

    // ── Organization-scoped reporting (FR-ORG-2) ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Customer> getCustomersForOrganizations(Collection<Long> organizationIds, int page, int size, Sort sort) {
        requireScope(organizationIds);
        return customerRepo.findByOrganizationIdIn(organizationIds, of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Invoice> getInvoicesForOrganizations(Collection<Long> organizationIds, int page, int size, Sort sort) {
        requireScope(organizationIds);
        return invoiceRepo.findByOrganizationIdIn(organizationIds, of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stats getStatsForOrganizations(Collection<Long> organizationIds) {
        requireScope(organizationIds);
        return jdbcTemplate.queryForObject(STATS_BY_ORGANIZATION_QUERY,
                Map.of("orgIds", organizationIds), new StatsRowMapper());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Integer> getCustomerStatusBreakdownForOrganizations(Collection<Long> organizationIds) {
        requireScope(organizationIds);
        return jdbcTemplate.query(CUSTOMER_STATUS_BREAKDOWN_BY_ORGANIZATION_QUERY,
                Map.of("orgIds", organizationIds), rs -> {
                    Map<String, Integer> breakdown = new LinkedHashMap<>();
                    while (rs.next()) {
                        String status = rs.getString("status");
                        breakdown.put(status != null ? status : "UNKNOWN", rs.getInt("count"));
                    }
                    return breakdown;
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Customer> getCustomersForOrganizations(Collection<Long> organizationIds) {
        requireScope(organizationIds);
        return customerRepo.findByOrganizationIdIn(organizationIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Invoice> getInvoicesForOrganizations(Collection<Long> organizationIds) {
        requireScope(organizationIds);
        return invoiceRepo.findByOrganizationIdIn(organizationIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Customer> searchCustomersForOrganizations(String name, Collection<Long> organizationIds, int page, int size, Sort sort) {
        requireScope(organizationIds);
        return customerRepo.findByCustomerNameContainingAndOrganizationIdIn(name, organizationIds, of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Invoice> searchInvoices(String term, int page, int size, Sort sort) {
        return invoiceRepo.searchByInvoiceNumberOrCustomerName(term, of(page, size, sort));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<Invoice> searchInvoicesForOrganizations(String term, Collection<Long> organizationIds, int page, int size, Sort sort) {
        requireScope(organizationIds);
        return invoiceRepo.searchByInvoiceNumberOrCustomerNameAndOrganizationIdIn(term, organizationIds, of(page, size, sort));
    }

    /**
     * Rejects an absent or empty organization scope before it reaches the database.
     *
     * <p>This is a fail-closed guard, not a convenience check. An empty {@code IN ()} list is
     * invalid SQL in MySQL, so the immediate consequence would be a 500 — but the reason it throws
     * rather than being smoothed over is what matters: the only sensible readings of "no
     * organizations" are "show nothing" or "show everything", and silently choosing the second is
     * exactly the leak this feature closes. Callers must decide explicitly, and
     * {@code AnalyticsController} does so by returning an empty result set rather than calling
     * these methods at all.
     *
     * @param organizationIds the scope supplied by the caller
     * @throws ApiException when the scope is null or empty
     */
    private static void requireScope(Collection<Long> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            throw new ApiException("An organization scope is required for this report.");
        }
    }

}
