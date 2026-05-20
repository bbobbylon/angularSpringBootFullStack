package com.bob.angularspringbootfullstack.report;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Invoice;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.InputStreamResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates an XLSX report of all invoices using Apache POI.
 *
 * <p>Mirrors the structure of {@link CustomerReport}: the constructor initialises
 * the workbook and writes column headers; {@link #exportReport()} triggers the
 * data rows and returns the finished file as an {@link InputStreamResource} so
 * Spring MVC can stream it directly to the HTTP response.
 *
 * <p>Used by {@code CustomerController.exportInvoiceReport()} at
 * {@code GET /customer/invoice/download/report}.
 */
@Slf4j
public class InvoiceReport {
    private static final String[] HEADERS = {"ID", "Invoice Number", "Services", "Status", "Date", "Total Amount", "Customer"};
    private final List<Invoice> invoices;
    private final XSSFWorkbook workbook;
    private final XSSFSheet sheet;

    /**
     * Initialises the workbook and writes the header row.
     *
     * @param invoices the full list of invoices to include in the report;
     *                 passed directly to {@link #generateReport()} without filtering
     */
    public InvoiceReport(List<Invoice> invoices) {
        this.invoices = invoices;
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Invoices");
        setHeaders();
    }

    /**
     * Writes the bold header row at row index 0 using the column names defined in {@link #HEADERS}.
     *
     * <p>Called once from the constructor so the sheet is ready for data rows
     * as soon as {@link #exportReport()} is invoked.
     */
    private void setHeaders() {
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        XSSFFont headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 14);
        headerStyle.setFont(headerFont);
        IntStream.range(0, HEADERS.length).forEach(index -> {
            Cell cell = headerRow.createCell(index);
            cell.setCellValue(HEADERS[index]);
            cell.setCellStyle(headerStyle);
        });
    }

    /**
     * Builds the data rows and returns the finished workbook as a streamable resource.
     *
     * @return an {@link InputStreamResource} wrapping the serialised XLSX bytes,
     *         ready to be set as the body of a {@link org.springframework.http.ResponseEntity}
     */
    public InputStreamResource exportReport() {
        return generateReport();
    }

    /**
     * Writes one row per invoice and serialises the workbook to a byte array.
     *
     * <p>The services list is joined as a comma-separated string. The {@code invoiceDate}
     * is formatted as {@code yyyy-MM-dd}; null dates and null customers are written as
     * empty strings to avoid NPEs from {@link DateFormatUtils#format(java.util.Date, String)}.
     *
     * @return an {@link InputStreamResource} backed by the in-memory XLSX bytes
     * @throws ApiException if POI fails to write the workbook
     */
    private InputStreamResource generateReport() {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            CellStyle style = workbook.createCellStyle();
            XSSFFont font = workbook.createFont();
            font.setFontHeightInPoints((short) 14);
            style.setFont(font);
            int rowIndex = 1;
            for (Invoice invoice : invoices) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(invoice.getId());
                row.createCell(1).setCellValue(invoice.getInvoiceNumber());
                row.createCell(2).setCellValue(
                        invoice.getServices() != null
                                ? invoice.getServices().stream()
                                        .map(s -> s.getName())
                                        .collect(Collectors.joining(", "))
                                : ""
                );
                row.createCell(3).setCellValue(invoice.getStatus());
                row.createCell(4).setCellValue(invoice.getInvoiceDate() != null
                        ? DateFormatUtils.format(invoice.getInvoiceDate(), "yyyy-MM-dd")
                        : "");
                row.createCell(5).setCellValue(invoice.getTotalAmount() != null ? invoice.getTotalAmount() : 0.0);
                row.createCell(6).setCellValue(invoice.getCustomer() != null
                        ? invoice.getCustomer().getCustomerName()
                        : "");
            }
            workbook.write(outputStream);
            return new InputStreamResource(new ByteArrayInputStream(outputStream.toByteArray()));
        } catch (Exception e) {
            log.error("Error generating invoice report: {}", e.getMessage());
            throw new ApiException("Failed to generate invoice report");
        }
    }
}
