package com.bob.angularspringbootfullstack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An embeddable line item on an {@link Invoice}, representing a single service rendered.
 * <p>
 * Stored via {@code @ElementCollection} in a separate {@code invoiceserviceitems} table
 * rather than as a stand-alone entity, because line items are owned exclusively by their
 * invoice and have no independent lifecycle or shared references.
 */
@Data
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLineItem {
    /**
     * Human-readable name of the service rendered (e.g., "Web Development", "Consulting").
     */
    @SuppressWarnings("JpaDataSourceORMInspection")
    @Column(name = "name")
    private String name;

    /**
     * Price charged for this line item in the application's default currency.
     */
    @SuppressWarnings("JpaDataSourceORMInspection")
    @Column(name = "price")
    private Double price;
}
