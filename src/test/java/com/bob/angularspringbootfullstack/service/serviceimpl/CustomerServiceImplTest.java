package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.repo.CustomerRepo;
import com.bob.angularspringbootfullstack.repo.InvoiceRepo;
import com.bob.angularspringbootfullstack.repo.ServicesRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link CustomerServiceImpl} — no Spring context and no database.
 * <p>
 * Repositories and the JDBC template are mocked with Mockito, so these run in
 * milliseconds in any environment (including CI without MySQL). They lock in the
 * service-layer business rules that the controllers depend on: timestamping on
 * create, server-generated invoice numbers, and not-found → {@link ApiException}
 * (which the {@code GlobalExceptionHandler} maps to a 400).
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepo customerRepo;
    @Mock
    private InvoiceRepo invoiceRepo;
    @Mock
    private ServicesRepo servicesRepo;
    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private CustomerServiceImpl customerService;

    @Test
    @DisplayName("createCustomer stamps createdAt and persists the customer")
    void createCustomer_setsCreatedAt_andSaves() {
        Customer input = Customer.builder().customerName("Acme").email("a@acme.test").build();
        when(customerRepo.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Customer saved = customerService.createCustomer(input);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCustomerName()).isEqualTo("Acme");
    }

    @Test
    @DisplayName("createInvoice generates a 10-character uppercase invoice number")
    void createInvoice_generatesInvoiceNumber() {
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Invoice saved = customerService.createInvoice(new Invoice());

        assertThat(saved.getInvoiceNumber()).hasSize(10);
        assertThat(saved.getInvoiceNumber()).isEqualTo(saved.getInvoiceNumber().toUpperCase());
        assertThat(saved.getInvoiceNumber()).matches("[A-Z0-9]{10}");
    }

    @Test
    @DisplayName("updateCustomer throws ApiException when the customer does not exist")
    void updateCustomer_throwsWhenNotFound() {
        when(customerRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> customerService.updateCustomer(99L, new Customer()));
    }

    @Test
    @DisplayName("addInvoiceToCustomer throws ApiException when the customer does not exist")
    void addInvoiceToCustomer_throwsWhenCustomerNotFound() {
        when(customerRepo.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> customerService.addInvoiceToCustomer(42L, new Invoice()));
    }

    @Test
    @DisplayName("updateCustomer applies editable fields onto the existing record")
    void updateCustomer_appliesEditableFields() {
        Customer existing = Customer.builder().id(1L).customerName("Old").email("old@test").status("PENDING").build();
        Customer incoming = Customer.builder().customerName("New").email("new@test").type("INDIVIDUAL").status("ACTIVE").build();
        when(customerRepo.findById(1L)).thenReturn(Optional.of(existing));
        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        when(customerRepo.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        customerService.updateCustomer(1L, incoming);

        org.mockito.Mockito.verify(customerRepo).save(captor.capture());
        Customer persisted = captor.getValue();
        assertThat(persisted.getId()).isEqualTo(1L);                 // id preserved
        assertThat(persisted.getCustomerName()).isEqualTo("New");    // editable field applied
        assertThat(persisted.getStatus()).isEqualTo("ACTIVE");
    }
}
