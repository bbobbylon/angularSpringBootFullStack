package com.bob.angularspringbootfullstack.dto;

/**
 * One failed row from a {@link BatchImportResult}.
 *
 * <p>{@code row} is the 1-based index of the row within the uploaded file's data rows —
 * i.e. {@code row == 1} is the first row after the header, matching what a user counts
 * looking at their spreadsheet with the header excluded. {@code reason} is a message safe
 * to show directly in the UI: either a bean-validation message already written for the
 * {@link com.bob.angularspringbootfullstack.model.Customer}/{@link com.bob.angularspringbootfullstack.model.Invoice}
 * entity fields, or a batch-import-specific one (missing column, duplicate email, unknown
 * customer) — never a raw exception message or stack trace.
 *
 * @param row    the 1-based data-row index this failure occurred on
 * @param reason a human-readable explanation of why the row was rejected
 */
public record BatchImportError(int row, String reason) {
}
