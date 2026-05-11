package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Collection;
import java.util.Date;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.FetchType.EAGER;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * Customer is a JPA entity representing a business customer in the system.
 * <p>
 * Each customer can own zero or more invoices, modelled as a bidirectional
 * {@code @OneToMany} relationship. Invoices are loaded eagerly so that a
 * single fetch always returns the customer with their full invoice history.
 * <p>
 * Fields:
 * - id: auto-generated primary key
 * - name: full name or business name of the customer
 * - type: customer category (e.g., "Individual", "Business")
 * - email: primary contact email address
 * - phoneNumber: primary contact phone number
 * - address: physical or mailing address
 * - status: account standing (e.g., "Active", "Inactive")
 * - imageUrl: URL to the customer's profile or logo image
 * - createdAt: timestamp of when the customer record was created
 * - invoices: all invoices associated with this customer
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
@Entity
public class Customer {
    /**
     * Timestamp of when the customer record was first created.
     */
    public Date createdAt;
    /**
     * Auto-generated unique identifier for the customer.
     */
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;
    /**
     * Full name or business name of the customer.
     * <p>
     * Mapped explicitly to {@code customer_name} because {@code globally_quoted_identifiers: true}
     * in application-dev.yml suppresses Hibernate's default camelCase → snake_case naming strategy,
     * causing Hibernate to create/read a literal {@code "customerName"} column instead of the
     * existing {@code customer_name} column that holds the actual data.
     */
    @Column(name = "customer_name")
    private String customerName;
    /**
     * Customer category (e.g., "Individual", "Business").
     */
    private String type;
    /**
     * Primary contact email address for the customer.
     */
    private String email;
    /**
     * Primary contact phone number for the customer.
     */
    private String phoneNumber;
    /**
     * Physical or mailing address of the customer.
     */
    private String address;
    /**
     * Account standing of the customer (e.g., "Active", "Inactive").
     */
    private String status;
    /**
     * URL pointing to the customer's profile picture or business logo.
     */
    private String imageUrl;
    /**
     * All invoices associated with this customer.
     * Loaded eagerly so the full invoice history is always available with the customer.
     */
    @OneToMany(mappedBy = "customer", fetch = EAGER, cascade = ALL)
    private Collection<Invoice> invoices;
}
