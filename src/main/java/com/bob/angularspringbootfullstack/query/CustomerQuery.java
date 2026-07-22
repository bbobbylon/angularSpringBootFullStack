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

    /**
     * Returns the system-wide count of customers grouped by account status
     * (e.g. {@code ACTIVE}, {@code PENDING}, {@code INACTIVE}, {@code BANNED}).
     * <p>
     * Powers the home-dashboard status donut. Aggregating in SQL keeps the chart
     * accurate across the whole table rather than just the page the UI happens to
     * have loaded. Ordered by descending count so the largest segment leads the
     * legend.
     */
    public static final String CUSTOMER_STATUS_BREAKDOWN_QUERY =
            "SELECT status, COUNT(*) AS count FROM customer GROUP BY status ORDER BY count DESC";
}
