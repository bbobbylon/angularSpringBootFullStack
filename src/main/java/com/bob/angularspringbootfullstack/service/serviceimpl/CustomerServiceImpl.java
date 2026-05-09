package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.repo.CustomerRepo;
import com.bob.angularspringbootfullstack.repo.InvoiceRepo;
import com.bob.angularspringbootfullstack.service.CustomerService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.Date;

import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.springframework.data.domain.PageRequest.of;

/**
 * CustomerServiceImpl is the primary implementation of {@link CustomerService}.
 * <p>
 * Delegates all persistence operations to {@link CustomerRepo} and {@link InvoiceRepo}.
 * Invoice numbers are generated automatically using an 10-character random alphanumeric
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
    private final CustomerRepo customerRepo;
    private final InvoiceRepo invoiceRepo;

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
        existingCustomer.setName(customer.getName());
        existingCustomer.setType(customer.getType());
        existingCustomer.setEmail(customer.getEmail());
        existingCustomer.setPhoneNumber(customer.getPhoneNumber());
        existingCustomer.setAddress(customer.getAddress());
        existingCustomer.setStatus(customer.getStatus());
        existingCustomer.setImageUrl(customer.getImageUrl());
        return customerRepo.save(existingCustomer);
    }

    /** {@inheritDoc} */
    @Override
    public Page<Customer> getCustomers(int page, int size) {
        return customerRepo.findAll(of(page, size));
    }

    /** {@inheritDoc} */
    @Override
    public Iterable<Customer> getCustomers() {
        return customerRepo.findAll();
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public Page<Invoice> getInvoices(int page, int size) {
        return invoiceRepo.findAll(of(page, size));
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

    /** {@inheritDoc} */
    @Override
    public Page<Customer> searchCustomers(String name, int page, int size) {
        return customerRepo.findByNameContaining(name, of(page, size));
    }

    /** {@inheritDoc} */
    @Override
    public Invoice getInvoice(Long invoiceId) {
        return invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found"));
    }

}
