package com.bob.angularspringbootfullstack.query;

/**
 * Holds native SQL query constants used by the customer feature.
 * <p>
 * Queries are defined here rather than inline in service classes, so they
 * can be read and maintained without opening the service implementation.
 */
public class CustomerQuery {
    /**
     * Returns a single row of system-wide aggregates: total customers,
     * total invoices, and the sum of all invoice {@code totalAmount} values
     * (rounded to the nearest whole number).
     * <p>
     * Uses inline subqueries rather than JOINs because there is no natural
     * key between the customer and invoice aggregate results.
     */
    public static final String STATS_QUERY =
            " SELECT c.total_customers, i.total_invoices, inv.total_billed FROM (SELECT COUNT(*) total_customers FROM customer) c, (SELECT COUNT(*) total_invoices FROM invoice) i, (SELECT ROUND(SUM(totalAmount)) total_billed FROM invoice) inv";
}
