package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.BatchImportError;
import com.bob.angularspringbootfullstack.dto.BatchImportResult;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.repo.CustomerRepo;
import com.bob.angularspringbootfullstack.repo.InvoiceRepo;
import com.bob.angularspringbootfullstack.service.BatchImportService;
import com.bob.angularspringbootfullstack.service.CustomerService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;

/**
 * BatchImportServiceImpl is the sole implementation of {@link BatchImportService}
 * (FUTURE-ENHANCEMENTS.md §3.3, "P2-2 — Batch upload").
 *
 * <p><b>Deliberately NOT {@code @Transactional} at the class level — this is the whole
 * mechanism behind per-row partial success.</b> Every row is persisted through a call that
 * crosses into a separately Spring-managed proxy — {@link CustomerService#createCustomer} for
 * customers, {@link InvoiceRepo#save} for invoices (mirroring how {@code CustomerServiceImpl}
 * itself calls {@code invoiceRepo.save()} directly rather than through a dedicated method) —
 * and because this class opens no surrounding transaction, each of those calls begins and
 * commits as its own independent transaction. A constraint violation or database error on row
 * 50 therefore cannot roll back rows 1–49: they are already committed by the time row 50 is
 * attempted. This is a stronger guarantee than a typical "commit every N rows" chunking scheme
 * (a bad row can only ever cost that one row, never a whole chunk), at the cost of one
 * transaction per row rather than per chunk — an acceptable trade for the row counts a single
 * HTTP request realistically carries here (see {@link #MAX_BATCH_ROWS}).
 *
 * <p><b>What this deliberately does not do.</b> The FUTURE-ENHANCEMENTS.md sketch for this item
 * also named "async job for large files" — not built. Processing happens synchronously inside
 * the HTTP request, bounded by {@link #MAX_BATCH_ROWS}; a file larger than that is rejected
 * outright with a message asking the caller to split it, rather than accepted and silently
 * truncated. Queueing large imports for background processing is real future work, not
 * something this class pretends to do.
 *
 * <p>Bean-validation constraints are re-used rather than re-implemented: each built {@link
 * Customer}/{@link Invoice} is checked against the exact same {@code jakarta.validation}
 * annotations {@code @Valid} enforces on the single-record create endpoints, via the {@link
 * Validator} bean Spring Boot auto-configures. This matters because {@code
 * application.yml}'s {@code jakarta.persistence.validation.mode: none} turns OFF Hibernate's
 * automatic validate-on-flush — so calling a repository's {@code save()} directly, as this class
 * does, would otherwise persist an invalid row instead of rejecting it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchImportServiceImpl implements BatchImportService {

    /**
     * Hard cap on rows per upload. A file over this size is rejected before any row is
     * processed — see the class Javadoc for why this is a synchronous, in-request boundary
     * rather than a queued background job.
     */
    private static final int MAX_BATCH_ROWS = 2000;

    private final CustomerRepo customerRepo;
    private final InvoiceRepo invoiceRepo;
    private final CustomerService customerService;
    private final Validator validator;

    /**
     * {@inheritDoc}
     */
    @Override
    public BatchImportResult importCustomers(MultipartFile file, Long organizationId) {
        List<Map<String, String>> rows = parseRows(file);
        List<BatchImportError> failed = new ArrayList<>();
        AtomicInteger imported = new AtomicInteger();
        int rowNum = 0;
        for (Map<String, String> row : rows) {
            rowNum++;
            importCustomerRow(rowNum, row, organizationId, failed, imported);
        }
        return new BatchImportResult(imported.get(), failed);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BatchImportResult importInvoices(MultipartFile file, Collection<Long> scope) {
        List<Map<String, String>> rows = parseRows(file);
        List<BatchImportError> failed = new ArrayList<>();
        AtomicInteger imported = new AtomicInteger();
        int rowNum = 0;
        for (Map<String, String> row : rows) {
            rowNum++;
            importInvoiceRow(rowNum, row, scope, failed, imported);
        }
        return new BatchImportResult(imported.get(), failed);
    }

    /**
     * Validates and persists one customer row. Every exit point either increments {@code
     * imported} or appends exactly one entry to {@code failed} — never both, never neither.
     */
    private void importCustomerRow(int rowNum, Map<String, String> row, Long organizationId,
                                    List<BatchImportError> failed, AtomicInteger imported) {
        String email = StringUtils.trimToNull(row.get("email"));
        if (email != null && customerRepo.findByEmail(email).isPresent()) {
            failed.add(new BatchImportError(rowNum, "A customer with email \"" + email + "\" already exists"));
            return;
        }

        Customer customer = Customer.builder()
                .customerName(StringUtils.trimToNull(row.get("customername")))
                .type(StringUtils.trimToNull(row.get("type")))
                .email(email)
                .status(StringUtils.trimToNull(row.get("status")))
                .phoneNumber(StringUtils.trimToNull(row.get("phonenumber")))
                .address(StringUtils.trimToNull(row.get("address")))
                .imageUrl(StringUtils.trimToNull(row.get("imageurl")))
                .organizationId(organizationId)
                .build();

        Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
        if (!violations.isEmpty()) {
            failed.add(new BatchImportError(rowNum, describeViolations(violations)));
            return;
        }

        try {
            customerService.createCustomer(customer);
            imported.incrementAndGet();
        } catch (RuntimeException e) {
            log.warn("Batch customer import failed on row {}: {}", rowNum, e.getMessage());
            failed.add(new BatchImportError(rowNum, "Could not save this row — check the values and try again"));
        }
    }

    /**
     * Validates and persists one invoice row. Every exit point either increments {@code
     * imported} or appends exactly one entry to {@code failed} — never both, never neither.
     */
    private void importInvoiceRow(int rowNum, Map<String, String> row, Collection<Long> scope,
                                   List<BatchImportError> failed, AtomicInteger imported) {
        String customerEmail = StringUtils.trimToNull(row.get("customeremail"));
        if (customerEmail == null) {
            failed.add(new BatchImportError(rowNum, "customerEmail is required"));
            return;
        }
        Customer customer = customerRepo.findByEmail(customerEmail).orElse(null);
        if (customer == null) {
            failed.add(new BatchImportError(rowNum, "No customer found with email \"" + customerEmail + "\""));
            return;
        }
        // Same organization-scope boundary CustomerController#requireInScope enforces on every
        // other invoice-touching endpoint (FR-ORG-2) — a scoped caller cannot bill an invoice to
        // a customer outside their own organizations just by naming the customer's email.
        if (scope != null && (customer.getOrganizationId() == null || !scope.contains(customer.getOrganizationId()))) {
            failed.add(new BatchImportError(rowNum, "Customer \"" + customerEmail + "\" is outside your organization scope"));
            return;
        }

        String invoiceNumber = StringUtils.trimToNull(row.get("invoicenumber"));
        if (invoiceNumber != null && invoiceRepo.findByInvoiceNumber(invoiceNumber).isPresent()) {
            failed.add(new BatchImportError(rowNum, "An invoice with number \"" + invoiceNumber + "\" already exists"));
            return;
        }

        Double totalAmount;
        try {
            String raw = StringUtils.trimToNull(row.get("totalamount"));
            totalAmount = raw == null ? null : Double.valueOf(raw);
        } catch (NumberFormatException e) {
            failed.add(new BatchImportError(rowNum, "totalAmount \"" + row.get("totalamount") + "\" is not a valid number"));
            return;
        }
        Double amount;
        try {
            String raw = StringUtils.trimToNull(row.get("amount"));
            amount = raw == null ? totalAmount : Double.valueOf(raw);
        } catch (NumberFormatException e) {
            failed.add(new BatchImportError(rowNum, "amount \"" + row.get("amount") + "\" is not a valid number"));
            return;
        }
        Date invoiceDate;
        try {
            String raw = StringUtils.trimToNull(row.get("invoicedate"));
            invoiceDate = raw == null ? new Date()
                    : Date.from(LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            failed.add(new BatchImportError(rowNum, "invoiceDate \"" + row.get("invoicedate") + "\" must be in yyyy-MM-dd format"));
            return;
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber != null ? invoiceNumber : randomAlphanumeric(10).toUpperCase());
        invoice.setStatus(StringUtils.trimToNull(row.get("status")));
        invoice.setAmount(amount);
        invoice.setTotalAmount(totalAmount);
        invoice.setInvoiceDate(invoiceDate);
        invoice.setCustomer(customer);
        invoice.setCustomerId(customer.getId());

        Set<ConstraintViolation<Invoice>> violations = validator.validate(invoice);
        if (!violations.isEmpty()) {
            failed.add(new BatchImportError(rowNum, describeViolations(violations)));
            return;
        }

        try {
            invoiceRepo.save(invoice);
            imported.incrementAndGet();
        } catch (RuntimeException e) {
            log.warn("Batch invoice import failed on row {}: {}", rowNum, e.getMessage());
            failed.add(new BatchImportError(rowNum, "Could not save this row — check the values and try again"));
        }
    }

    /**
     * Joins bean-validation messages into one client-safe reason string. These are the same
     * messages {@code @Valid} would already show a caller of the single-record create
     * endpoints, so — unlike the generic message used for a save-time failure below — they are
     * safe to return as-is.
     */
    private static <T> String describeViolations(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
    }

    /**
     * Dispatches on file extension to the CSV or XLSX parser and enforces {@link
     * #MAX_BATCH_ROWS} before any row is validated or persisted.
     */
    private List<Map<String, String>> parseRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("No file was uploaded.");
        }
        String filename = file.getOriginalFilename();
        String extension = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        List<Map<String, String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = switch (extension) {
                case "csv" -> parseCsv(in);
                case "xlsx", "xls" -> parseXlsx(in);
                default -> throw new ApiException("Unsupported file type \"" + extension + "\" — upload a .csv or .xlsx file.");
            };
        } catch (IOException e) {
            throw new ApiException("Could not read the uploaded file — it may be corrupted.");
        }
        if (rows.size() > MAX_BATCH_ROWS) {
            throw new ApiException("This file has " + rows.size() + " rows — batch upload is capped at "
                    + MAX_BATCH_ROWS + " rows per file. Split it into smaller files and upload each separately.");
        }
        return rows;
    }

    /**
     * Parses a CSV file into one lowercase-keyed {@code Map<String, String>} per data row (the
     * header row is consumed as column names, never returned as data). Keys are lowercased here
     * — rather than relying on {@code CSVFormat}'s {@code setIgnoreHeaderCase}, which affects
     * lookup but not the casing {@link CSVRecord#toMap()} returns — so every row-field access
     * in this class can use one fixed lowercase key regardless of how the source file capitalized
     * its header.
     */
    private List<Map<String, String>> parseCsv(InputStream in) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                record.toMap().forEach((key, value) ->
                        row.put(key == null ? "" : key.trim().toLowerCase(Locale.ROOT), value));
                rows.add(row);
            }
            return rows;
        }
    }

    /**
     * Parses the first sheet of an XLSX/XLS workbook the same way {@link #parseCsv} parses a
     * CSV — the header row becomes lowercase column keys, every following row becomes one
     * {@code Map<String, String>}. A fully blank row (common as a trailing artifact of a
     * spreadsheet export) is skipped rather than surfaced as a row full of "field is required"
     * failures. {@link DataFormatter} renders every cell type (numeric, date, formula result) as
     * the same text a person reading the sheet in Excel would see, so the row-parsing logic below
     * this method never needs to branch on {@code Cell#getCellType()}.
     */
    private List<Map<String, String>> parseXlsx(InputStream in) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            var rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                return List.of();
            }
            Row headerRow = rowIterator.next();
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(cell.getStringCellValue().trim().toLowerCase(Locale.ROOT));
            }
            DataFormatter formatter = new DataFormatter();
            List<Map<String, String>> rows = new ArrayList<>();
            while (rowIterator.hasNext()) {
                Row sourceRow = rowIterator.next();
                Map<String, String> row = new LinkedHashMap<>();
                boolean blank = true;
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = sourceRow.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    if (!value.isEmpty()) {
                        blank = false;
                    }
                    row.put(headers.get(i), value);
                }
                if (!blank) {
                    rows.add(row);
                }
            }
            return rows;
        }
    }
}
