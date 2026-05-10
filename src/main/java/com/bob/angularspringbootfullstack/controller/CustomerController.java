package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Optional;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

/**
 * CustomerController handles all REST endpoints under {@code /customer}.
 * <p>
 * Provides endpoints for managing customers and their associated invoices.
 * Every response embeds the currently authenticated user alongside the
 * requested data, following the project's standard {@link HttpResponse} envelope.
 * <p>
 * All endpoints require a valid JWT — unauthenticated requests are rejected
 * by the security filter chain before reaching this controller.
 */
@RestController
@RequestMapping(path = "/customer")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;
    private final UserService userService;

    /**
     * Returns aggregated dashboard statistics: total customers, total invoices,
     * and the sum of all invoice totalAmount values.
     *
     * @param user the authenticated user making the request
     * @return 200 OK with the authenticated user and a {@code Stats} object
     */
    @GetMapping("/stats")
    public ResponseEntity<HttpResponse> getStats(@AuthenticationPrincipal UserDTO user) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "stats", customerService.getStats()))
                        .message("Stats retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns a paginated list of all customers.
     *
     * @param user the authenticated user making the request
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @return 200 OK with the authenticated user and a page of customers
     */
    @GetMapping("/list")
    public ResponseEntity<HttpResponse> getCustomers(@AuthenticationPrincipal UserDTO user, @RequestParam Optional<Integer> page, @RequestParam Optional<Integer> size) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "page", customerService.getCustomers(page.orElse(0), size.orElse(20)),
                                "stats", customerService.getStats()))
                        .message("Customers retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns a single customer by their ID.
     *
     * @param user       the authenticated user making the request
     * @param customerId the ID of the customer to retrieve
     * @return 200 OK with the authenticated user and the matching customer
     */
    @GetMapping("/get/{customerId}")
    public ResponseEntity<HttpResponse> getCustomer(@AuthenticationPrincipal UserDTO user, @PathVariable("customerId") Long customerId) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customerService.getCustomer(customerId)))
                        .message("Customer retrieved!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Searches for customers whose name contains the given search term.
     *
     * @param user the authenticated user making the request
     * @param name the substring to search for within customer names (defaults to empty, returning all)
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @return 200 OK with the authenticated user and a page of matching customers
     */
    @GetMapping("/search/{customerId}")
    public ResponseEntity<HttpResponse> searchCustomer(@AuthenticationPrincipal UserDTO user, Optional<String> name, Optional<Integer> page, @RequestParam Optional<Integer> size) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customerService.searchCustomers(name.orElse(""), page.orElse(0), size.orElse(20))))
                        .message("Customers found!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Updates an existing customer's editable fields by ID.
     * The ID is taken from the URL path, not the request body, so the frontend
     * does not need to include it in the payload.
     *
     * @param user       the authenticated user making the request
     * @param customerId the ID of the customer to update
     * @param customer   the updated field values to apply
     * @return 200 OK with the authenticated user and the updated customer
     */
    @PutMapping("/update/{customerId}")
    public ResponseEntity<HttpResponse> updateCustomer(@AuthenticationPrincipal UserDTO user, @PathVariable Long customerId, @RequestBody Customer customer) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customer", customerService.updateCustomer(customerId, customer)))
                        .message("Customer updated!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a new customer record.
     *
     * @param user     the authenticated user making the request
     * @param customer the customer data to create
     * @return 201 Created with the authenticated user and the newly created customer
     */
    @PostMapping("/create")
    public ResponseEntity<HttpResponse> createCustomer(@AuthenticationPrincipal UserDTO user, @RequestBody Customer customer) {
        return ResponseEntity.created(URI.create("")).body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customer", customerService.createCustomer(customer)))
                        .message("Customer has been created!")
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    // TODO: Add PUT /invoice/{invoiceId}/addtocustomer/{customerId} endpoint to link an
    //  existing standalone invoice to a customer. Requires nullable = true on Invoice.customer
    //  and a new service method that loads the invoice by ID and sets the customer field.

    /**
     * Creates a new standalone invoice (not yet linked to a customer).
     * Use {@code /invoice/addtocustomer/{customerId}} to attach it to a customer.
     *
     * @param user    the authenticated user making the request
     * @param invoice the invoice data to create
     * @return 201 Created with the authenticated user and the newly created invoice
     */
    @PostMapping("/invoice/create")
    public ResponseEntity<HttpResponse> createInvoice(@AuthenticationPrincipal UserDTO user, @RequestBody Invoice invoice) {
        return ResponseEntity.created(URI.create("")).body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoice", customerService.createInvoice(invoice)))
                        .message("Invoice has been created!")
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    /**
     * Returns a paginated list of all invoices.
     *
     * @param user the authenticated user making the request
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @return 200 OK with the authenticated user and a page of invoices
     */
    @GetMapping("/invoice/list")
    public ResponseEntity<HttpResponse> getInvoices(@AuthenticationPrincipal UserDTO user, @RequestParam Optional<Integer> page, @RequestParam Optional<Integer> size) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoices", customerService.getCustomers(page.orElse(0), size.orElse(20))))
                        .message("Invoice retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns all customers needed to populate the new-invoice creation form in the UI.
     * This endpoint is hit when the user navigates to the "New Invoice" page, providing
     * the customer list required to assign an invoice to a customer.
     *
     * @param user the authenticated user making the request
     * @return 200 OK with the authenticated user and all customers (unpaginated)
     */
    @PostMapping("/invoice/new")
    public ResponseEntity<HttpResponse> newInvoice(@AuthenticationPrincipal UserDTO user) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customerService.getCustomers()))
                        .message("New invoice page reached and Customers have been retrieved!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns a single invoice by its ID.
     *
     * @param user      the authenticated user making the request
     * @param invoiceId the ID of the invoice to retrieve
     * @return 200 OK with the authenticated user and the matching invoice
     */
    @GetMapping("/invoice/get/{invoiceId}")
    public ResponseEntity<HttpResponse> getInvoice(@AuthenticationPrincipal UserDTO user, @PathVariable("invoiceId") Long invoiceId) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoice", customerService.getInvoice(invoiceId)))
                        .message("Invoice retrieved!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a new invoice and associates it with a specific customer.
     *
     * @param user       the authenticated user making the request
     * @param customerId the ID of the customer to attach the invoice to
     * @param invoice    the invoice data to create and link
     * @return 200 OK with the authenticated user and all customers (for UI refresh)
     */
    @PostMapping("/invoice/addtocustomer/{customerId}")
    public ResponseEntity<HttpResponse> addInvoiceToCustomer(@AuthenticationPrincipal UserDTO user, @PathVariable("customerId") Long customerId, @RequestBody Invoice invoice) {
        customerService.addInvoiceToCustomer(customerId, invoice);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customerService.getCustomers()))
                        .message("Invoice added to customer!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

}
