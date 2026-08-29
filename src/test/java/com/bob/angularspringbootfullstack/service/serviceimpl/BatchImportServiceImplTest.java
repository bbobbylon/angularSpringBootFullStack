package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.BatchImportResult;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.repo.CustomerRepo;
import com.bob.angularspringbootfullstack.repo.InvoiceRepo;
import com.bob.angularspringbootfullstack.service.CustomerService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchImportServiceImpl}, focused on the downloadable-template work
 * (FUTURE-ENHANCEMENTS.md §3.3, "Downloadable batch-upload templates") rather than re-testing
 * every validation branch {@code importCustomerRow}/{@code importInvoiceRow} already had before
 * this change (this class had no prior test coverage at all).
 *
 * <p>The two "template round trip" tests are the ones that actually matter here: they build a
 * CSV using {@code CUSTOMER_TEMPLATE_HEADERS}/{@code INVOICE_TEMPLATE_HEADERS} — the exact same
 * lists {@code GET /customer/batch/template} and {@code GET /customer/invoice/batch/template}
 * serve — and feed it through the real {@code importCustomers}/{@code importInvoices} pipeline.
 * A passing round trip proves the header list, the {@code key()} lookup helper, and the parser
 * agree on every column name; a typo in any of the three would show up here as a failed row
 * instead of a silent data-loss bug in production.
 */
@ExtendWith(MockitoExtension.class)
class BatchImportServiceImplTest {

    @Mock
    private CustomerRepo customerRepo;
    @Mock
    private InvoiceRepo invoiceRepo;
    @Mock
    private CustomerService customerService;

    private BatchImportServiceImpl batchImportService;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        batchImportService = new BatchImportServiceImpl(customerRepo, invoiceRepo, customerService, validator);
    }

    private static MockMultipartFile csvFile(List<String> headers, String... dataRows) {
        StringBuilder csv = new StringBuilder(String.join(",", headers)).append("\n");
        for (String row : dataRows) {
            csv.append(row).append("\n");
        }
        return new MockMultipartFile("file", "import.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("customerTemplateHeaders() exposes the exact columns importCustomerRow reads")
    void customerTemplateHeadersMatchExpectedColumns() {
        assertEquals(
                List.of("customerName", "type", "email", "status", "phoneNumber", "address", "imageUrl"),
                batchImportService.customerTemplateHeaders());
    }

    @Test
    @DisplayName("invoiceTemplateHeaders() exposes the exact columns importInvoiceRow reads")
    void invoiceTemplateHeadersMatchExpectedColumns() {
        assertEquals(
                List.of("customerEmail", "invoiceNumber", "status", "totalAmount", "amount", "invoiceDate"),
                batchImportService.invoiceTemplateHeaders());
    }

    @Test
    @DisplayName("a CSV built from customerTemplateHeaders() imports cleanly end to end")
    void importCustomersTemplateHeaderRoundTrip() {
        when(customerRepo.findByEmail("acme@example.com")).thenReturn(Optional.empty());
        when(customerService.createCustomer(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = csvFile(batchImportService.customerTemplateHeaders(),
                "Acme Corp,BUSINESS,acme@example.com,ACTIVE,555-1234,123 Main St,http://img.example.com/acme.png");

        BatchImportResult result = batchImportService.importCustomers(file, 42L);

        assertEquals(1, result.imported());
        assertTrue(result.failed().isEmpty(), () -> "unexpected failures: " + result.failed());

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        org.mockito.Mockito.verify(customerService).createCustomer(captor.capture());
        Customer created = captor.getValue();
        assertEquals("Acme Corp", created.getCustomerName());
        assertEquals("BUSINESS", created.getType());
        assertEquals("acme@example.com", created.getEmail());
        assertEquals("ACTIVE", created.getStatus());
        assertEquals("555-1234", created.getPhoneNumber());
        assertEquals("123 Main St", created.getAddress());
        assertEquals("http://img.example.com/acme.png", created.getImageUrl());
        assertEquals(42L, created.getOrganizationId());
    }

    @Test
    @DisplayName("a CSV built from invoiceTemplateHeaders() imports cleanly end to end")
    void importInvoicesTemplateHeaderRoundTrip() {
        Customer customer = Customer.builder().id(7L).customerName("Acme Corp").email("acme@example.com").organizationId(42L).build();
        when(customerRepo.findByEmail("acme@example.com")).thenReturn(Optional.of(customer));
        when(invoiceRepo.findByInvoiceNumber("INV-100")).thenReturn(Optional.empty());
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = csvFile(batchImportService.invoiceTemplateHeaders(),
                "acme@example.com,INV-100,PAID,500.0,450.0,2026-01-15");

        BatchImportResult result = batchImportService.importInvoices(file, null);

        assertEquals(1, result.imported());
        assertTrue(result.failed().isEmpty(), () -> "unexpected failures: " + result.failed());

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        org.mockito.Mockito.verify(invoiceRepo).save(captor.capture());
        Invoice saved = captor.getValue();
        assertEquals("INV-100", saved.getInvoiceNumber());
        assertEquals("PAID", saved.getStatus());
        assertEquals(500.0, saved.getTotalAmount());
        assertEquals(450.0, saved.getAmount());
        assertEquals(Date.from(LocalDate.of(2026, 1, 15).atStartOfDay(ZoneId.systemDefault()).toInstant()), saved.getInvoiceDate());
        assertEquals(7L, saved.getCustomerId());
    }
}
