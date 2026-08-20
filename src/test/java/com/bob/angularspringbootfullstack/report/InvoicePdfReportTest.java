package com.bob.angularspringbootfullstack.report;

import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.InvoiceLineItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InvoicePdfReport} — no Spring context, no database.
 * <p>
 * Locks in the one behavior that matters at this layer: {@link InvoicePdfReport#exportReport()}
 * always returns bytes starting with the standard {@code %PDF-} magic header, for both a normal
 * invoice and a draft with no linked {@link Customer}. The draft case matters specifically because
 * {@code CustomerController#exportInvoicePdf} (the download endpoint) does not refuse a draft the
 * way {@code CustomerController#emailInvoice} does — this class is what has to render one without
 * throwing.
 */
class InvoicePdfReportTest {

    @Test
    @DisplayName("exportReport renders a normal invoice as a valid PDF")
    void exportReport_normalInvoice_producesPdfBytes() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("A3F9KQ2B");
        invoice.setStatus("Paid");
        invoice.setInvoiceDate(new Date());
        invoice.setTotalAmount(150.0);
        invoice.setServices(List.of(
                new InvoiceLineItem("Consulting", 100.0),
                new InvoiceLineItem("Support", 50.0)));
        invoice.setCustomer(Customer.builder()
                .customerName("Acme Corp")
                .email("billing@acme.test")
                .address("1 Acme Way")
                .phoneNumber("555-0100")
                .build());

        byte[] pdf = new InvoicePdfReport(invoice).exportReport();

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("exportReport renders a draft invoice (no customer) without throwing")
    void exportReport_draftInvoice_stillProducesPdfBytes() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("DRAFT01");
        invoice.setStatus("Pending");
        invoice.setTotalAmount(0.0);
        invoice.setServices(List.of());
        invoice.setCustomer(null);

        byte[] pdf = new InvoicePdfReport(invoice).exportReport();

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }
}
