package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.service.BatchImportService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@code GET /customer/batch/template} and
 * {@code GET /customer/invoice/batch/template} (FUTURE-ENHANCEMENTS.md §3.3, "Downloadable
 * batch-upload templates"). Confirms the two things a caller of these endpoints actually depends
 * on: the response is a downloadable XLSX attachment, not an inline blob, and its one header row
 * exactly matches whatever {@link BatchImportService#customerTemplateHeaders()}/
 * {@code #invoiceTemplateHeaders()} returns — i.e. the endpoint really does defer to the service
 * layer's header list rather than hardcoding its own copy that could drift from it.
 *
 * <p>Runs on plain Mockito, no Spring MVC context — consistent with the rest of this controller's
 * test suite ({@link CustomerControllerOrgScopeTest}).
 */
@ExtendWith(MockitoExtension.class)
class CustomerControllerBatchTemplateTest {

    @Mock
    private BatchImportService batchImportService;

    @InjectMocks
    private CustomerController controller;

    @Test
    @DisplayName("customer batch template: attachment XLSX whose header row is customerTemplateHeaders()")
    void downloadCustomerBatchTemplateReturnsExpectedHeaders() throws IOException {
        List<String> headers = List.of("customerName", "type", "email", "status", "phoneNumber", "address", "imageUrl");
        when(batchImportService.customerTemplateHeaders()).thenReturn(headers);

        ResponseEntity<Resource> response = controller.downloadCustomerBatchTemplate();

        assertEquals(200, response.getStatusCode().value());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertEquals("attachment; filename=\"customer_batch_template.xlsx\"", disposition);
        assertEquals(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), response.getHeaders().getContentType());
        assertHeaderRow(response, headers);
    }

    @Test
    @DisplayName("invoice batch template: attachment XLSX whose header row is invoiceTemplateHeaders()")
    void downloadInvoiceBatchTemplateReturnsExpectedHeaders() throws IOException {
        List<String> headers = List.of("customerEmail", "invoiceNumber", "status", "totalAmount", "amount", "invoiceDate");
        when(batchImportService.invoiceTemplateHeaders()).thenReturn(headers);

        ResponseEntity<Resource> response = controller.downloadInvoiceBatchTemplate();

        assertEquals(200, response.getStatusCode().value());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertEquals("attachment; filename=\"invoice_batch_template.xlsx\"", disposition);
        assertHeaderRow(response, headers);
    }

    private static void assertHeaderRow(ResponseEntity<Resource> response, List<String> expectedHeaders) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
            Row headerRow = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < expectedHeaders.size(); i++) {
                assertEquals(expectedHeaders.get(i), headerRow.getCell(i).getStringCellValue());
            }
        }
    }
}
