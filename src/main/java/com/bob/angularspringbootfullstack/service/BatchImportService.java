package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.BatchImportResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;

/**
 * BatchImportService defines the service-layer contract for CSV/XLSX bulk import
 * (FUTURE-ENHANCEMENTS.md §3.3, "P2-2 — Batch upload"), backing
 * {@code POST /customer/batch} and {@code POST /customer/invoice/batch}.
 * <p>
 * Both operations are <b>partial-success</b>: a malformed or duplicate row is reported in the
 * returned {@link BatchImportResult} rather than aborting rows that already validated and
 * persisted correctly. See {@code BatchImportServiceImpl}'s class Javadoc for how per-row
 * transaction isolation makes that guarantee real rather than approximate.
 */
public interface BatchImportService {

    /**
     * Imports customers from an uploaded CSV or XLSX file.
     * <p>
     * Expected columns (header row required, case-insensitive): {@code customerName}, {@code
     * type}, {@code email}, {@code status} (all required), plus optional {@code phoneNumber},
     * {@code address}, {@code imageUrl}. A row whose email already belongs to an existing
     * customer is rejected as a duplicate rather than silently skipped or silently overwritten.
     *
     * @param file           the uploaded {@code .csv} or {@code .xlsx} file
     * @param organizationId the organization new customers are stamped with, mirroring {@code
     *                       POST /customer/create}'s single-record behavior — {@code null} leaves
     *                       them unowned, exactly as a single create does for a caller in no
     *                       organization
     * @return a row-by-row report of what was imported and what was rejected, and why
     */
    BatchImportResult importCustomers(MultipartFile file, Long organizationId);

    /**
     * Imports invoices from an uploaded CSV or XLSX file, each row linked to an existing customer
     * by email.
     * <p>
     * Expected columns (header row required, case-insensitive): {@code customerEmail}, {@code
     * status}, {@code totalAmount} (all required), plus optional {@code invoiceNumber} (a blank
     * value auto-generates one, exactly as {@code POST /invoice/create} does), {@code amount},
     * {@code invoiceDate} (format {@code yyyy-MM-dd}, defaults to today when blank). A row whose
     * {@code customerEmail} does not match any existing customer is rejected — this endpoint
     * links to existing customers, it does not create them.
     *
     * @param file  the uploaded {@code .csv} or {@code .xlsx} file
     * @param scope the caller's organization-scope restriction from {@code CustomerController
     *              #resolveScope} — {@code null} for an unscoped caller, otherwise every
     *              resolved customer must belong to one of these organizations or the row is
     *              rejected, the same boundary {@code CustomerController#requireInScope} enforces
     *              on every other invoice-touching endpoint
     * @return a row-by-row report of what was imported and what was rejected, and why
     */
    BatchImportResult importInvoices(MultipartFile file, Collection<Long> scope);
}
