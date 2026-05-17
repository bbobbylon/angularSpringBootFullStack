package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * Invoice is a JPA entity representing a billing record issued to a customer.
 * <p>
 * Each invoice is linked to a {@link Customer} via a {@code @ManyToOne} relationship
 * and to a {@link Services} record describing what was performed. The {@code customer}
 * field is the JPA-managed foreign key and is excluded from JSON output to prevent
 * circular serialization; the {@code customerId} field is a separate denormalized
 * column for direct queries that do not need the full customer object.
 * <p>
 * Fields:
 * - id: auto-generated primary key
 * - invoiceNumber: unique human-readable identifier (e.g., "A3F9KQ2B")
 * - service: the type of service this invoice is for
 * - amount: the billed amount for this invoice
 * - status: payment state (e.g., "Pending", "Paid")
 * - customerId: denormalized foreign key for direct lookups
 * - invoiceDate: date the invoice was issued
 * - totalAmount: final amount after any adjustments or taxes
 * - customer: JPA relationship to the owning Customer (hidden from JSON)
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(NON_DEFAULT)
@Entity
public class Invoice {
    /**
     * Auto-generated unique identifier for the invoice.
     */
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;
    /**
     * Unique human-readable invoice reference code (e.g., "A3F9KQ2B").
     * Generated automatically on creation via {@code randomAlphanumeric}.
     */
    private String invoiceNumber;
    /**
     * The type of service this invoice covers, referenced from the Services table.
     */
    @ManyToOne
    @JoinColumn(name = "services_id")
    private Services service;
    /**
     * Free-text description of the service provided, submitted directly from the invoice form.
     */
    private String services;
    /**
     * The billed amount for this invoice in the application's default currency.
     */
    private Double amount;
    /**
     * Payment state of the invoice (e.g., "Pending", "Paid", "Overdue").
     */
    private String status;
    /**
     * Denormalized foreign key to the owning customer, for direct queries.
     */
    private Long customerId;
    /**
     * Date on which the invoice was issued to the customer.
     */
    private Date invoiceDate;
    /**
     * Final total amount after any adjustments, discounts, or taxes are applied.
     */
    private Double totalAmount;
    /**
     * JPA relationship to the owning Customer. Excluded from JSON output to prevent
     * circular serialization between Customer and Invoice.
     * <p>
     * TODO: Change nullable = false to nullable = true to support draft invoices
     *  (created standalone via POST /invoice/create, linked to a customer later).
     */
    @ManyToOne
    @JoinColumn(name = "customer", nullable = false)
    @JsonIgnore
    private Customer customer;
}
