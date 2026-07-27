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

    /**
     * Organization-scoped variant of {@link #STATS_QUERY} (FR-ORG-2).
     *
     * <p>Every subquery carries the same {@code organization_id IN (:orgIds)} restriction, and the
     * invoice totals reach it by joining back to the owning customer — invoices inherit their
     * tenant from the customer they bill rather than carrying their own copy, so there is one
     * place to change if ownership is ever reassigned.
     *
     * <p>The filter must live inside each aggregate rather than being applied to the result,
     * because a {@code COUNT} or {@code SUM} has already discarded the attribution needed to
     * subtract other organizations' contributions afterwards.
     *
     * <p>{@code COALESCE} on the billed total keeps the shape identical to the unscoped query for
     * an organization with no invoices yet: {@code SUM} over no rows returns NULL, which would
     * surface as a blank tile instead of a zero. Parameter: orgIds.
     */
    public static final String STATS_BY_ORGANIZATION_QUERY =
            " SELECT c.total_customers, i.total_invoices, inv.total_billed FROM " +
            "(SELECT COUNT(*) total_customers FROM customer WHERE organization_id IN (:orgIds)) c, " +
            "(SELECT COUNT(*) total_invoices FROM invoice iv " +
            " JOIN customer cu ON cu.id = iv.customer WHERE cu.organization_id IN (:orgIds)) i, " +
            "(SELECT COALESCE(ROUND(SUM(iv.totalAmount)), 0) total_billed FROM invoice iv " +
            " JOIN customer cu ON cu.id = iv.customer WHERE cu.organization_id IN (:orgIds)) inv";

    /**
     * Organization-scoped variant of {@link #CUSTOMER_STATUS_BREAKDOWN_QUERY} (FR-ORG-2).
     * Powers the same status donut, counting only customers owned by the caller's organizations.
     * Parameter: orgIds.
     */
    public static final String CUSTOMER_STATUS_BREAKDOWN_BY_ORGANIZATION_QUERY =
            "SELECT status, COUNT(*) AS count FROM customer WHERE organization_id IN (:orgIds) " +
            "GROUP BY status ORDER BY count DESC";
}
