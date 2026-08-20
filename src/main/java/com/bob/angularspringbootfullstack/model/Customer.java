package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.util.Collection;
import java.util.Date;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static jakarta.persistence.CascadeType.ALL;
import static jakarta.persistence.FetchType.EAGER;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * Customer is a JPA entity representing a business customer in the system.
 * <p>
 * Each customer can own zero or more invoices, modeled as a bidirectional
 * {@code @OneToMany} relationship. Invoices are loaded eagerly so that a
 * single fetch always returns the customer with their full invoice history.
 * <p>
 * Fields:
 * - Id: auto-generated primary key
 * - Name: full name or business name of the customer
 * - Type: customer category (e.g., "Individual", "Business")
 * - Email: primary contact email address
 * - phoneNumber: primary contact phone number
 * - Address: physical or mailing address
 * - Status: account standing (e.g., "Active", "Inactive")
 * - imageUrl: URL to the customer's profile or logo image
 * - createdAt: timestamp of when the customer record was created
 * - Invoices: all invoices associated with this customer
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
    @NotBlank(message = "Customer name is required")
    private String customerName;
    /**
     * Customer category (e.g., "Individual", "Business").
     */
    @NotBlank(message = "Customer type is required")
    private String type;
    /**
     * Primary contact email address for the customer.
     */
    @NotBlank(message = "Email is required")
    @Email(message = "A valid email address is required")
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
    @NotBlank(message = "Status is required")
    private String status;
    /**
     * URL pointing to the customer's profile picture or business logo.
     */
    private String imageUrl;
    /**
     * The organization that owns this customer record (FR-ORG-2).
     * <p>
     * This is what makes org-scoped reporting possible. Organizations previously scoped only
     * <em>users</em> (via the {@code userorganizations} join table), so the analytics rollups had
     * no tenant dimension to filter on and every organization administrator saw system-wide
     * totals — including the revenue of organizations they have no relationship with.
     * <p>
     * Modelled as a plain {@code Long} rather than a {@code @ManyToOne} association because
     * {@code organizations} is owned by the JDBC half of this codebase ({@code schema.sql} +
     * {@code OrganizationQuery}), not by JPA. Mapping a relationship here would force
     * {@code Organization} to become a second, JPA-managed view of the same table, and Hibernate's
     * {@code ddl-auto: validate} would then police a table it does not otherwise manage. Holding
     * the raw foreign key keeps the two persistence styles from colliding while still giving every
     * query something to filter on.
     * <p>
     * Explicitly mapped to {@code organization_id} for the same reason as {@code customer_name}:
     * {@code globally_quoted_identifiers: true} suppresses the camelCase → snake_case strategy,
     * so without this Hibernate would look for a literal {@code "organizationId"} column.
     * <p>
     * Nullable by design. Rows created before this column existed are backfilled by
     * {@code schema.sql}, but a null must remain <em>possible</em> rather than fatal — and the
     * scoped queries treat it as "belongs to no organization", i.e. invisible to a scoped admin
     * rather than visible to all of them. Unscoped tiers ({@code ROLE_ADMIN},
     * {@code ROLE_APPLICATION_ADMIN}) still see every row.
     */
    @Column(name = "organization_id")
    private Long organizationId;
    /**
     * All invoices associated with this customer.
     * Loaded eagerly, so the full invoice history is always available with the customer.
     *
     * <p><b>{@code @BatchSize} is load-bearing, not a micro-optimisation.</b> An eager
     * {@code @OneToMany} on a <em>paged</em> query is the textbook N+1: Spring Data issues one
     * {@code SELECT ... FROM Customer LIMIT ?,?} and Hibernate then issues one
     * {@code SELECT ... FROM Invoice WHERE customer = ?} <em>per row returned</em>. Observed on the
     * live ECS deployment 2026-08-02: a single customer-list request produced ~35 sequential invoice
     * queries at roughly 67&nbsp;ms per Aiven round trip — about 2.4 seconds of pure network latency
     * before the response could be serialized.
     *
     * <p>With {@code @BatchSize}, Hibernate collects the pending collection loads and satisfies them
     * with {@code ... WHERE customer IN (?, ?, ?, …)} — so a 35-row page costs 2 queries instead of
     * 35. The size is chosen to comfortably exceed a normal page of customers so a page almost
     * always resolves in one batch.
     *
     * <p>The reason this is {@code @BatchSize} and <em>not</em> a {@code JOIN FETCH} query: fetching a
     * collection while paginating makes Hibernate log
     * {@code HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory}
     * and pull <em>every</em> matching row into the JVM to paginate there — strictly worse than the
     * problem being solved. Batching is the correct fix for the paged + eager shape.
     *
     * <p>The deeper fix (LAZY + a projection/DTO for the list view, so invoices are never loaded for
     * a screen that does not display them) is tracked in {@code ROADMAP.md}.
     */
    @OneToMany(mappedBy = "customer", fetch = EAGER, cascade = ALL)
    @BatchSize(size = 50)
    private Collection<Invoice> invoices;
}
