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

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(NON_DEFAULT)
@Entity
public class Invoice {
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;
    private String invoiceNumber;
    @ManyToOne
    @JoinColumn(name = "services_id")
    private Services service;
    private Double amount;
    private String status;
    private Long customerId;
    private Date invoiceDate;
    private Double totalAmount;
    @ManyToOne
    @JoinColumn(name = "customer", nullable = false)
    @JsonIgnore
    private Customer customer;
}
