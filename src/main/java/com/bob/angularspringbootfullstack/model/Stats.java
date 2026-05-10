package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * Aggregated dashboard statistics returned by {@code GET /customer/stats}
 * and embedded in the {@code GET /customer/list} response.
 * <p>
 * All values are system-wide totals across all customers and invoices,
 * computed by {@link com.bob.angularspringbootfullstack.service.CustomerService#getStats()}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class Stats {
    /** Total number of customer records in the system. */
    private int totalCustomers;
    /** Total number of invoices across all customers. */
    private int totalInvoices;
    /** Sum of all invoice {@code totalAmount} values, rounded to the nearest whole number. */
    private double totalBilled;

}