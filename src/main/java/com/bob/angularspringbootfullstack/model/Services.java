package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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

    /**
     * Whether this service is still offered.
     *
     * <p>Retiring a service is a <em>deactivation</em>, never a delete. Invoices copy a service's
     * name and price into their own line items at the moment they are raised, so deleting the
     * catalog row would not corrupt historical invoices — but it would destroy the catalog's own
     * history, making it impossible to answer "what did we used to sell?" or to bring an offering
     * back without retyping it. A boolean costs nothing and keeps that answerable.
     *
     * <p>Modelled as a {@code Boolean} rather than a primitive on purpose: the entity is annotated
     * {@code @JsonInclude(NON_DEFAULT)}, under which a primitive {@code false} equals the default
     * and would be silently dropped from the JSON — so a deactivated service would serialize
     * looking exactly like an active one. With the wrapper, the field defaults to {@code null} in a
     * no-args instance, so both {@code true} and {@code false} are non-default and both survive.
     *
     * <p>The column is {@code NOT NULL DEFAULT TRUE}, so rows that predate this field read back as
     * active — the correct interpretation of a catalog that had no concept of retirement.
     */
    private Boolean active;
}
