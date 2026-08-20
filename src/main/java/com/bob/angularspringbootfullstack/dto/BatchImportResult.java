package com.bob.angularspringbootfullstack.dto;

import java.util.List;

/**
 * The partial-success report returned by {@link com.bob.angularspringbootfullstack.service.BatchImportService}
 * for one {@code POST /customer/batch} or {@code POST /customer/invoice/batch} upload.
 *
 * <p>A batch upload never fails all-or-nothing: each row is validated and persisted
 * independently (see {@code BatchImportServiceImpl} for why that gives true per-row
 * commit isolation rather than an approximation of it), so one malformed row cannot take
 * the rest of the file down with it. {@code imported} counts rows that were actually
 * persisted; {@code failed} lists every row that was not, with a reason a non-technical
 * user can act on.
 *
 * @param imported the number of rows successfully created
 * @param failed   every rejected row, in file order
 */
public record BatchImportResult(int imported, List<BatchImportError> failed) {

    /**
     * Canonicalises the failure list so the record is genuinely immutable, matching
     * {@link LoginRiskAssessment}'s convention for a list-carrying record in this package.
     */
    public BatchImportResult {
        failed = List.copyOf(failed);
    }
}
