package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

/**
 * Aggregated dashboard statistics returned by {@code GET /customer/stats}.
 *
 * @param totalCustomers total number of customer records in the system
 * @param totalInvoices  total number of invoices across all customers
 * @param totalBilled    sum of all invoice {@code totalAmount} values
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class Stats {
    private int totalCustomers;
    private int totalInvoices;
    private double totalBilled;

}