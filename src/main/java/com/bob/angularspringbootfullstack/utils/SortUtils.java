package com.bob.angularspringbootfullstack.utils;

import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the {@code sort=field,direction} request parameter used by the paginated list/search
 * endpoints (customers, invoices) into a validated {@link Sort}, following the same shape Spring
 * Data's own {@code Pageable} resolver accepts so the frontend needs no bespoke convention.
 * <p>
 * The field is checked against a per-entity allow-list rather than passed straight to
 * {@link Sort#by(Sort.Direction, String...)}. Two reasons: an unrecognized JPA property path
 * throws deep inside Hibernate's query translator with a stack trace that leaks entity internals,
 * and an allow-list is the only way to guarantee a client can never request an ORDER BY over a
 * column (or a joined association) that was never meant to be client-steerable. An unrecognized or
 * absent field falls back to {@link Sort#unsorted()} rather than a 400 — sorting is a nice-to-have
 * on these lists, so a bad/stale {@code sort} value degrades to "unsorted", not a broken page.
 */
public final class SortUtils {

    private SortUtils() {
    }

    /**
     * @param sortParam     the raw {@code sort} request param, e.g. {@code "customerName,desc"}
     * @param allowedFields the JPA property paths this entity may be sorted by (e.g.
     *                      {@code "customerName"}, or a joined path like {@code "customer.customerName"})
     * @return a validated {@link Sort}, or {@link Sort#unsorted()} if the param is absent, blank,
     * or names a field outside {@code allowedFields}
     */
    public static Sort resolveSort(Optional<String> sortParam, Set<String> allowedFields) {
        if (sortParam == null || sortParam.isEmpty() || sortParam.get().isBlank()) {
            return Sort.unsorted();
        }
        String[] parts = sortParam.get().split(",", 2);
        String field = parts[0].trim();
        if (!allowedFields.contains(field)) {
            return Sort.unsorted();
        }
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, field);
    }

    /**
     * The raw-JDBC sibling of {@link #resolveSort}, for aggregates (the User directory) whose
     * queries are hand-written SQL rather than Spring Data derived/JPQL queries. A
     * {@link Sort} object has nowhere to plug into a {@code NamedParameterJdbcTemplate} query —
     * named parameters bind values, not identifiers, so an {@code ORDER BY} column can't be a
     * bind param at all. This returns an already-validated {@code "column ASC|DESC"} fragment
     * that the caller splices into the SQL template with {@link String#format}.
     * <p>
     * That splice is safe specifically <em>because</em> of the allow-list: {@code field} is
     * checked against {@code allowedFields} before {@code column} is read out of it, so the
     * fragment that reaches {@link String#format} is always one of the column names the caller
     * wrote into the map — never anything from the request.
     *
     * @param sortParam     the raw {@code sort} request param, e.g. {@code "email,desc"}
     * @param allowedFields map of client-facing field name (e.g. {@code "email"}) to the actual
     *                      SQL column it may order by (e.g. {@code "email"} or {@code "first_name"})
     * @param defaultOrderBy the fragment to use when the param is absent, blank, or unrecognized
     *                       (e.g. {@code "created_at DESC, id DESC"})
     * @return a safe {@code "column ASC|DESC"} fragment, or {@code defaultOrderBy}
     */
    public static String resolveSqlOrderBy(Optional<String> sortParam, Map<String, String> allowedFields, String defaultOrderBy) {
        if (sortParam == null || sortParam.isEmpty() || sortParam.get().isBlank()) {
            return defaultOrderBy;
        }
        String[] parts = sortParam.get().split(",", 2);
        String column = allowedFields.get(parts[0].trim());
        if (column == null) {
            return defaultOrderBy;
        }
        String direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? "DESC" : "ASC";
        return column + " " + direction;
    }
}
