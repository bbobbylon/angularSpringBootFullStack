package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import org.springframework.data.domain.Page;

public interface CustomerService {
    Customer createCustomer(Customer customer);

    Customer updateCustomer(Customer customer);

    Page<Customer> getCustomers(int page, int size);

    Iterable<Customer> getCustomers();

    Customer getCustomer(Long customerId);

    Invoice createInvoice(Invoice invoice);

    Page<Invoice> getInvoices(int page, int size);

    void addInvoiceToCustomer(Long customerId, Invoice invoice);

    Page<Customer> searchCustomers(String name, int page, int size);

    Invoice getInvoice(Long invoiceId);
}
