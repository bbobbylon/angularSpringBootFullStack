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
import org.apache.poi.openxml4j.util.ZipSecureFile;
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
 * <p><b>Resource bounds are enforced before work is done, not after</b> (FUTURE-ENHANCEMENTS.md
 * §3.1, "request size / batch-import limits"). Because this runs synchronously on a request
 * thread, an oversized upload is a single-request denial-of-service the IP rate limiter never
 * sees — one request, not a burst. Three ceilings apply in order, each one cheaper than the
 * next: {@link #MAX_BATCH_FILE_BYTES} is checked against the multipart's declared size before
 * a single byte is parsed; {@link #MAX_BATCH_ROWS} is enforced <em>inside</em> the CSV and XLSX
 * row loops so a file with 50,000 rows is rejected on row 2,001 rather than after all 50,000
 * have been read into a list; and {@link ZipSecureFile#setMaxEntrySize} caps how far a
 * compressed XLSX may inflate, closing the "small file, enormous decompressed sheet" gap that
 * a byte-size check alone leaves open. The app-wide {@code spring.servlet.multipart.max-file-size}
 * (10MB, sized for profile pictures) still applies underneath all three — this class is
 * deliberately tighter than that because a legitimate 2,000-row import is a fraction of it.
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
     * Hard cap on rows per upload. Enforced inside {@link #parseCsv}/{@link #parseXlsx} as rows
     * are read, so an over-limit file is rejected the moment row {@code MAX_BATCH_ROWS + 1} is
     * reached — before any row is validated or persisted, and without materializing the rest of
     * the file. See the class Javadoc for why this is a synchronous, in-request boundary rather
     * than a queued background job. Package-private so {@code BatchImportServiceImplTest} can
     * build boundary fixtures from the real value instead of a hardcoded copy of it.
     */
    static final int MAX_BATCH_ROWS = 2000;

    /**
     * Hard cap on the uploaded file's byte size, checked against {@link MultipartFile#getSize()}
     * before the file is opened. 2MB is roughly ten times what a {@link #MAX_BATCH_ROWS}-row CSV
     * or XLSX with every column populated actually weighs, so a legitimate import never gets
     * near it, while an attacker can no longer hand Apache POI the full 10MB the app-wide
     * multipart limit permits. Package-private for the same test reason as {@link #MAX_BATCH_ROWS}.
     */
    static final long MAX_BATCH_FILE_BYTES = 2L * 1024 * 1024;

    /**
     * Ceiling on how large any single entry inside an uploaded XLSX (a ZIP container) may
     * inflate to. Apache POI's default is effectively unbounded (4GB) and relies on its
     * minimum-inflate-ratio check alone, which still permits a {@link #MAX_BATCH_FILE_BYTES}
     * file to expand 100× into memory. 32MB is over ten times the inflated size of a maximal
     * legitimate import and bounds the worst case at something a small container survives.
     */
    private static final long MAX_XLSX_ENTRY_BYTES = 32L * 1024 * 1024;

    static {
        // ZipSecureFile's limits are JVM-global statics, not per-reader settings. This class is
        // the application's only XLSX *reader* (the report exports are writers, which the
        // inflate guard does not touch), so owning the setting here — rather than in a
        // configuration class far from the code it protects — keeps the reason next to the risk.
        ZipSecureFile.setMaxEntrySize(MAX_XLSX_ENTRY_BYTES);
    }

    /**
     * Column headers for the customer batch-upload template (FUTURE-ENHANCEMENTS.md §3.3,
     * "Downloadable batch-upload templates"), in display casing and expected order.
     * {@code organizationId} is deliberately absent — see {@link #importCustomerRow}'s Javadoc
     * for why a client-supplied organization column would be a scope-escape.
     * <p>
     * Every {@code row.get(key(...))} call in {@link #importCustomerRow} reads one of these
     * headers back via {@link #key}, so this list and the parser can never drift apart: renaming
     * a header here without updating the matching {@code key(...)} call below simply breaks the
     * column, it cannot silently point the template at the wrong field.
     */
    static final List<String> CUSTOMER_TEMPLATE_HEADERS =
            List.of("customerName", "type", "email", "status", "phoneNumber", "address", "imageUrl");

    /**
     * Column headers for the invoice batch-upload template. See {@link #CUSTOMER_TEMPLATE_HEADERS}
     * for the no-drift guarantee this list shares with {@link #importInvoiceRow}.
     */
    static final List<String> INVOICE_TEMPLATE_HEADERS =
            List.of("customerEmail", "invoiceNumber", "status", "totalAmount", "amount", "invoiceDate");

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
        String email = StringUtils.trimToNull(row.get(key("email")));
        if (email != null && customerRepo.findByEmail(email).isPresent()) {
            failed.add(new BatchImportError(rowNum, "A customer with email \"" + email + "\" already exists"));
            return;
        }

        Customer customer = Customer.builder()
                .customerName(StringUtils.trimToNull(row.get(key("customerName"))))
                .type(StringUtils.trimToNull(row.get(key("type"))))
                .email(email)
                .status(StringUtils.trimToNull(row.get(key("status"))))
                .phoneNumber(StringUtils.trimToNull(row.get(key("phoneNumber"))))
                .address(StringUtils.trimToNull(row.get(key("address"))))
                .imageUrl(StringUtils.trimToNull(row.get(key("imageUrl"))))
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
        String customerEmail = StringUtils.trimToNull(row.get(key("customerEmail")));
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

        String invoiceNumber = StringUtils.trimToNull(row.get(key("invoiceNumber")));
        if (invoiceNumber != null && invoiceRepo.findByInvoiceNumber(invoiceNumber).isPresent()) {
            failed.add(new BatchImportError(rowNum, "An invoice with number \"" + invoiceNumber + "\" already exists"));
            return;
        }

        Double totalAmount;
        try {
            String raw = StringUtils.trimToNull(row.get(key("totalAmount")));
            totalAmount = raw == null ? null : Double.valueOf(raw);
        } catch (NumberFormatException e) {
            failed.add(new BatchImportError(rowNum, "totalAmount \"" + row.get(key("totalAmount")) + "\" is not a valid number"));
            return;
        }
        Double amount;
        try {
            String raw = StringUtils.trimToNull(row.get(key("amount")));
            amount = raw == null ? totalAmount : Double.valueOf(raw);
        } catch (NumberFormatException e) {
            failed.add(new BatchImportError(rowNum, "amount \"" + row.get(key("amount")) + "\" is not a valid number"));
            return;
        }
        Date invoiceDate;
        try {
            String raw = StringUtils.trimToNull(row.get(key("invoiceDate")));
            invoiceDate = raw == null ? new Date()
                    : Date.from(LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            failed.add(new BatchImportError(rowNum, "invoiceDate \"" + row.get(key("invoiceDate")) + "\" must be in yyyy-MM-dd format"));
            return;
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber != null ? invoiceNumber : randomAlphanumeric(10).toUpperCase());
        invoice.setStatus(StringUtils.trimToNull(row.get(key("status"))));
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
     * {@inheritDoc}
     */
    @Override
    public List<String> customerTemplateHeaders() {
        return CUSTOMER_TEMPLATE_HEADERS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> invoiceTemplateHeaders() {
        return INVOICE_TEMPLATE_HEADERS;
    }

    /**
     * Lowercases a display header (e.g. {@code "customerName"}) into the row-map key {@link
     * #parseCsv}/{@link #parseXlsx} store it under — both lowercase every header they read, so
     * this is the exact inverse operation. Routing every {@code row.get(...)} call in {@link
     * #importCustomerRow}/{@link #importInvoiceRow} through this one method, applied to a header
     * literal drawn from {@link #CUSTOMER_TEMPLATE_HEADERS}/{@link #INVOICE_TEMPLATE_HEADERS}, is
     * what keeps the downloadable template and the parser mechanically in sync — there is no
     * second, independently typed set of lowercase key strings left anywhere in this class to
     * drift out from under the templates.
     */
    private static String key(String header) {
        return header.toLowerCase(Locale.ROOT);
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
     * Dispatches on file extension to the CSV or XLSX parser. The byte-size ceiling is applied
     * here, before the file is opened; the row ceiling is applied inside each parser as it
     * reads (see {@link #MAX_BATCH_ROWS}), so by the time this returns the list is already
     * known to be within bounds — there is deliberately no post-parse size check left here.
     */
    private List<Map<String, String>> parseRows(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("No file was uploaded.");
        }
        if (file.getSize() > MAX_BATCH_FILE_BYTES) {
            throw new ApiException("This file is larger than " + (MAX_BATCH_FILE_BYTES / (1024 * 1024))
                    + "MB — batch upload is capped at " + MAX_BATCH_ROWS
                    + " rows per file, which is well under that. Split it into smaller files and upload each separately.");
        }
        String filename = file.getOriginalFilename();
        String extension = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try (InputStream in = file.getInputStream()) {
            return switch (extension) {
                case "csv" -> parseCsv(in);
                case "xlsx", "xls" -> parseXlsx(in);
                default -> throw new ApiException("Unsupported file type \"" + extension + "\" — upload a .csv or .xlsx file.");
            };
        } catch (IOException e) {
            throw new ApiException("Could not read the uploaded file — it may be corrupted.");
        }
    }

    /**
     * The one rejection both parsers throw when a file crosses {@link #MAX_BATCH_ROWS}. Worded
     * as "more than" rather than reporting an exact count because, by design, neither parser
     * reads far enough past the limit to know the true total — that is the point of failing
     * fast.
     */
    private static ApiException tooManyRows() {
        return new ApiException("This file has more than " + MAX_BATCH_ROWS + " rows — batch upload is capped at "
                + MAX_BATCH_ROWS + " rows per file. Split it into smaller files and upload each separately.");
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
                // Commons CSV streams records lazily, so throwing here genuinely stops reading —
                // the rest of an oversized file is never pulled off the input stream.
                if (rows.size() >= MAX_BATCH_ROWS) {
                    throw tooManyRows();
                }
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
                    // Counted on non-blank rows only, so trailing formatted-but-empty rows (which
                    // Excel happily leaves behind) can never push a legitimate file over the limit.
                    // Unlike the CSV path this cannot avoid the workbook already being in memory —
                    // WorkbookFactory builds the DOM eagerly — which is exactly why the byte-size
                    // and ZipSecureFile ceilings exist in front of it; what this does bound is the
                    // per-row map allocation and, downstream, the number of DB round-trips.
                    if (rows.size() >= MAX_BATCH_ROWS) {
                        throw tooManyRows();
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }
}
