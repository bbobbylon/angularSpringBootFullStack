package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * Services represent a type of service that can be associated with an invoice.
 * <p>
 * This entity acts as a reference table of available service offerings. Rather than
 * storing a raw string on each invoice, invoices hold a foreign key to this entity,
 * ensuring consistency and enabling reporting by service type.
 * <p>
 * Note: The name "Services" is used because "Service" is a reserved Spring stereotype
 * annotation. This entity may be renamed to match the domain of the final application
 * (e.g., Product, Procedure, Subscription).
 * <p>
 * Fields:
 * - Id: auto-generated primary key
 * - Name: canonical display name of the service (e.g., "Web Development")
 * - Description: human-readable summary of what the service entails
 * - Price: standard base price for the service
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
@Entity
@Table(name = "services")
public class Services {
    /**
     * Auto-generated unique identifier for the service.
     */
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;
    /**
     * Canonical display name of the service (e.g., "Web Development", "Consulting").
     */
    private String name;
    /**
     * Human-readable summary of what the service entails.
     */
    private String description;
    /**
     * Standard base price for the service, in the application's default currency.
     */
    private Double price;
}
