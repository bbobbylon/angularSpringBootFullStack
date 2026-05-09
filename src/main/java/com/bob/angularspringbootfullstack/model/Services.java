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

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
@Entity

// This might be changed later down the road to something else that is related to the app, for example cars, patients, etc. etc. Services is just a placeholder for now, and it is related to the invoice, which is also a placeholder for now. The invoice will be used to generate the invoice for the customer, and the services will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and the invoice will be used to generate the invoice for the customer. The services will be used to generate the invoice for the customer, and it might have some other fields that are related to it, such as a description of what it is, or a price of what it is, etc. etc.
public class Services {
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;
    private String name;
    private String description;
    private Double price;
}