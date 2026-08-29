package com.bob.angularspringbootfullstack.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Aggregated dashboard statistics returned by {@code GET /customer/stats}
 * and embedded in the {@code GET /customer/list} response.
 * <p>
 * All values are system-wide totals across all customers and invoices,
 * computed by {@link com.bob.angularspringbootfullstack.service.CustomerService#getStats()}.
 *
 * <p><b>Deliberately no {@code @JsonInclude(NON_DEFAULT)}</b> (removed 2026-08-28): every field
 * here is a real, meaningful count that is legitimately zero for an empty scope — e.g. a
 * brand-new organization with no customers yet — not an optional value whose absence means
 * "not applicable". {@code StatsInterface} on the frontend has always declared these as required
 * {@code number}, never optional; {@code NON_DEFAULT} silently dropped the key whenever the true
 * value was {@code 0}, handing the client {@code undefined} for a field its own types promised
 * would always be a number. See {@code OrganizationStats}, which embeds this class and hit the
 * same bug for a zero-activity organization's KPI tiles.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Stats {
    /** Total number of customer records in the system. */
    private int totalCustomers;
    /** Total number of invoices across all customers. */
    private int totalInvoices;
    /** Sum of all invoice {@code totalAmount} values, rounded to the nearest whole number. */
    private double totalBilled;

}