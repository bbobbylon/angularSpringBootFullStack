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

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {
    private final CustomerRepo customerRepo;
    private final InvoiceRepo invoiceRepo;

    @Override
    public Customer createCustomer(Customer customer) {
        customer.setCreatedAt(new Date());
        return customerRepo.save(customer);
    }

    @Override
    public Customer updateCustomer(Customer customer) {
        return customerRepo.save(customer);
    }

    @Override
    public Page<Customer> getCustomers(int page, int size) {
        return customerRepo.findAll(of(page, size));
    }

    @Override
    public Iterable<Customer> getCustomers() {
        return customerRepo.findAll();
    }

    @Override
    public Customer getCustomer(Long customerId) {
        return customerRepo.findById(customerId)
                .orElseThrow(() -> new ApiException("Customer not found"));
    }

    @Override
    public Invoice createInvoice(Invoice invoice) {
        invoice.setInvoiceNumber(randomAlphanumeric(10).toUpperCase());
        return invoiceRepo.save(invoice);
    }

    @Override
    public Page<Invoice> getInvoices(int page, int size) {
        return invoiceRepo.findAll(of(page, size));
    }

    @Override
    public void addInvoiceToCustomer(Long customerId, Invoice invoice) {
        invoice.setInvoiceNumber(randomAlphanumeric(10).toUpperCase());
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ApiException("Customer not found"));
        invoice.setCustomer(customer);
        invoiceRepo.save(invoice);
    }

    @Override
    public Page<Customer> searchCustomers(String name, int page, int size) {
        return customerRepo.findByNameContaining(name, of(page, size));
    }

    @Override
    public Invoice getInvoice(Long invoiceId) {
        return invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found"));
    }

}
