package com.bob.angularspringbootfullstack.report;

import com.bob.angularspringbootfullstack.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
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
 * Generates a header-only XLSX "starter file" for the batch-upload endpoints
 * (FUTURE-ENHANCEMENTS.md §3.3, "Downloadable batch-upload templates").
 *
 * <p>Mirrors {@link CustomerReport} and {@link InvoiceReport}'s construction shape — bold
 * header row written eagerly, workbook serialized on demand — but writes no data rows at all:
 * the entire point of this class is to hand a first-time uploader an empty file shaped exactly
 * like what {@code BatchImportServiceImpl} expects to read back, rather than a table of example
 * data that would need deleting before the file was useful.
 *
 * <p>The header list is supplied by the caller rather than hardcoded here, unlike {@code
 * CustomerReport}/{@code InvoiceReport}'s {@code HEADERS} constants — see {@code
 * BatchImportService#customerTemplateHeaders()}/{@code #invoiceTemplateHeaders()} for why: those
 * lists are the same ones {@code BatchImportServiceImpl}'s row parser reads back, so the file
 * this class produces can never silently drift from the parser that consumes it. This class
 * knows nothing about customers or invoices specifically — it only knows how to turn a sheet
 * name and a header list into a workbook.
 *
 * <p>Used by {@code CustomerController.downloadCustomerBatchTemplate()} and {@code
 * CustomerController.downloadInvoiceBatchTemplate()} at {@code GET /customer/batch/template}
 * and {@code GET /customer/invoice/batch/template}.
 */
@Slf4j
public class BatchTemplateReport {
    private final XSSFWorkbook workbook;

    /**
     * Builds the workbook and writes the bold header row immediately — there is no second phase,
     * unlike {@link CustomerReport}/{@link InvoiceReport}, because a template has no data rows to
     * defer to a later {@code exportReport()} call.
     *
     * @param sheetName the sheet name (e.g. {@code "Customers"}, {@code "Invoices"})
     * @param headers   the column headers, written in order starting at column 0
     */
    public BatchTemplateReport(String sheetName, List<String> headers) {
        workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetName);
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        XSSFFont headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 14);
        headerStyle.setFont(headerFont);
        IntStream.range(0, headers.size()).forEach(index -> {
            Cell cell = headerRow.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(headerStyle);
            sheet.autoSizeColumn(index);
        });
    }

    /**
     * Serializes the header-only workbook to a streamable resource.
     *
     * @return an {@link InputStreamResource} backed by the in-memory XLSX bytes
     * @throws ApiException if POI fails to write the workbook
     */
    public InputStreamResource exportReport() {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            workbook.write(outputStream);
            return new InputStreamResource(new ByteArrayInputStream(outputStream.toByteArray()));
        } catch (Exception e) {
            log.error("Error generating batch-upload template: {}", e.getMessage());
            throw new ApiException("Failed to generate batch-upload template");
        }
    }
}
