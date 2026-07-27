package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * Invoice is a JPA entity representing a billing record issued to a customer.
 * <p>
 * Each invoice owns a list of {@link InvoiceLineItem} records stored via
 * {@code @ElementCollection} in the {@code invoiceserviceitems} table. Line items
 * are embedded value objects — they have no independent identity and are always
 * fetched and deleted with their owning invoice.
 * <p>
 * The {@code customer} field is the JPA-managed foreign key and is excluded from
 * JSON output to prevent circular serialization; {@code customerId} is a separate
 * denormalized column for direct queries.
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
     * The line items on this invoice — each represents one service rendered with a name and price.
     * <p>
     * Stored in a separate {@code invoiceserviceitems} table via {@code @ElementCollection}.
     * Fetched eagerly, so the list is always available when the invoice is serialized to JSON.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "invoiceserviceitems", joinColumns = @JoinColumn(name = "invoice_id"))
    @OrderColumn(name = "item_order")
    private List<InvoiceLineItem> services = new ArrayList<>();
    /**
     * The billed amount for this invoice in the application's default currency.
     */
    private Double amount;
    /**
     * Payment state of the invoice (e.g., "Pending", "Paid", "Overdue").
     */
    @NotBlank(message = "Invoice status is required")
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
    @NotNull(message = "Total amount is required")
    @PositiveOrZero(message = "Total amount cannot be negative")
    private Double totalAmount;
    /**
     * JPA relationship to the owning Customer. Excluded from JSON output to prevent
     * circular serialization between Customer and Invoice.
     *
     * <p><b>Nullable, to support draft invoices.</b> {@code POST /invoice/create} has always
     * advertised itself as creating a <em>standalone</em> invoice to be attached to a customer
     * later, but the column was {@code NOT NULL} — so that endpoint could only ever succeed if the
     * caller supplied a customer, which is precisely the case it was not for. The entity and the
     * schema now agree that a draft has no customer yet, and
     * {@code PUT /invoice/{invoiceId}/addtocustomer/{customerId}} is what gives it one.
     *
     * <p><b>Deployment note:</b> databases created before this change still carry
     * {@code customer bigint NOT NULL}. {@code schema.sql} contains a guarded, idempotent
     * {@code MODIFY COLUMN} that relaxes it; until that runs against an existing database, draft
     * creation will still be refused by the database itself. Hibernate's {@code ddl-auto: validate}
     * does not check nullability, so a stale database fails at insert time rather than at startup —
     * which is why the ALTER is called out here rather than left for someone to discover.
     */
    @ManyToOne
    @JoinColumn(name = "customer", nullable = true)
    @JsonIgnore
    private Customer customer;
}
