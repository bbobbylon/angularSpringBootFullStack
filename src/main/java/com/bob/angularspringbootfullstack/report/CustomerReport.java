package com.bob.angularspringbootfullstack.report;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
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
import java.util.stream.IntStream;

/**
 * Generates an XLSX report of all customers using Apache POI.
 *
 * <p>Construction is two-phase: the constructor initializes the {@link XSSFWorkbook},
 * creates a "Customers" sheet, and writes the bold header row via {@link #setHeaders()}.
 * Calling {@link #exportReport()} then writes all data rows and serializes the workbook
 * into a heap-backed {@link InputStreamResource} — no temp files are created or need
 * to be cleaned up.
 *
 * <p>Used by {@code CustomerController.exportReport()} at
 * {@code GET /customer/download/report}.
 *
 * <p><strong>Note:</strong> instances are single-use. Calling {@link #exportReport()}
 * a second time would append duplicate rows to the same in-memory sheet.
 */
@Slf4j
public class CustomerReport {
    private static final String[] HEADERS = {"ID", "Name", "Type", "Email", "Phone Number", "Status", "Address", "Created At"};
    private final List<Customer> customers;
    private final XSSFWorkbook workbook;
    private final XSSFSheet sheet;

    /**
     * Initializes the workbook and writes the header row.
     *
     * @param customers the full list of customers to include in the report;
     *                  passed directly to {@link #generateReport()} without filtering
     */
    public CustomerReport(List<Customer> customers) {
        this.customers = customers;
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Customers");
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
     * @return an {@link InputStreamResource} wrapping the serialized XLSX bytes,
     *         ready to be set as the body of a {@link org.springframework.http.ResponseEntity}
     */
    public InputStreamResource exportReport() {
        return generateReport();
    }

    /**
     * Writes one row per customer and serializes the workbook to a byte array.
     *
     * <p>The {@code createdAt} date is formatted as {@code yyyy-MM-dd hh:mm:ss};
     * a null date is written as an empty string to avoid an NPE from
     * {@link DateFormatUtils#format(java.util.Date, String)}.
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
            for (Customer customer : customers) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(customer.getId());
                row.createCell(1).setCellValue(customer.getCustomerName());
                row.createCell(2).setCellValue(customer.getType());
                row.createCell(3).setCellValue(customer.getEmail());
                row.createCell(4).setCellValue(customer.getPhoneNumber());
                row.createCell(5).setCellValue(customer.getStatus());
                row.createCell(6).setCellValue(customer.getAddress());
                row.createCell(7).setCellValue(customer.getCreatedAt() != null
                        ? DateFormatUtils.format(customer.getCreatedAt(), "yyyy-MM-dd hh:mm:ss")
                        : "");
            }
            workbook.write(outputStream);
            return new InputStreamResource(new ByteArrayInputStream(outputStream.toByteArray()));
        } catch (Exception e) {
            log.error("Error generating customer report: {}", e.getMessage());
            throw new ApiException("Failed to generate report");
        }
    }
}
