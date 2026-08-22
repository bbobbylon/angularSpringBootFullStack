package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.BatchImportResult;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.report.CustomerReport;
import com.bob.angularspringbootfullstack.report.InvoicePdfReport;
import com.bob.angularspringbootfullstack.report.InvoiceReport;
import com.bob.angularspringbootfullstack.service.BatchImportService;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.EmailService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.utils.SortUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.parseMediaType;

/**
 * CustomerController handles all REST endpoints under {@code /customer}.
 * <p>
 * Provides endpoints for managing customers and their associated invoices.
 * Every response embeds the currently authenticated user alongside the
 * requested data, following the project's standard {@link HttpResponse} envelope.
 * <p>
 * All endpoints require a valid JWT — unauthenticated requests are rejected
 * by the security filter chain before reaching this controller.
 *
 * <p><b>Organization scoping (FR-ORG-2, 2026-08-08; extended to every role below the unscoped
 * tiers, 2026-08-21).</b> Every read here is restricted to customers/invoices owned by the
 * caller's active organizations — the same restriction {@link AnalyticsController} already
 * applied to the admin-only rollups, extended to this shared surface because customers and
 * invoices are browsed here directly, not just through the analytics dashboards. Only
 * {@code ROLE_ADMIN} and {@code ROLE_APPLICATION_ADMIN} — the two tiers
 * {@link RoleType#isOrganizationScoped()} treats as platform operators — keep today's
 * system-wide view; see {@link #resolveScope} for the exact rule. Single-record gets
 * ({@code /get/{id}}, {@code /invoice/get/{id}}) are checked post-fetch via
 * {@link #requireInScope}; every list/search/export goes through the organization-scoped
 * repository methods so the restriction lives in SQL, never in a post-filter that would corrupt
 * pagination totals.
 */
@RestController
@RequestMapping(path = "/customer")
@RequiredArgsConstructor
public class CustomerController {
    /**
     * Service layer for all customer and invoice persistence operations.
     * Injected by Lombok's {@code @RequiredArgsConstructor}.
     */
    private final CustomerService customerService;
    /**
     * Service layer for user profile lookups.
     * Used to embed the authenticated user in every response envelope.
     */
    private final UserService userService;
    /**
     * Resolves the creator's organization so new customers are owned from the moment they exist
     * (FR-ORG-2). Without this, every customer created after the org-scoping change would be
     * orphaned and invisible to the scoped dashboards that are supposed to report on it.
     */
    private final OrganizationService organizationService;
    /**
     * Sends the PDF invoice email dispatched by {@link #emailInvoice}.
     */
    private final EmailService emailService;
    /**
     * Backs {@link #batchImportCustomers} and {@link #batchImportInvoices} — parses an uploaded
     * CSV/XLSX file and persists it row by row (POST-SUBMISSION-UPGRADES.md #8).
     */
    private final BatchImportService batchImportService;

    /**
     * JPA property paths the {@code /customer/list} and {@code /customer/search} endpoints may
     * sort by. Enforced by {@link SortUtils#resolveSort} — see that class for why this is an
     * allow-list rather than passing the client's field straight through.
     */
    private static final Set<String> CUSTOMER_SORT_FIELDS =
            Set.of("customerName", "status", "type", "email", "createdAt");

    /**
     * JPA property paths the {@code /customer/invoice/list} and {@code /customer/invoice/search}
     * endpoints may sort by. {@code customer.customerName} is a joined path — the customer
     * association Hibernate already loads to render the invoice's owner column.
     */
    private static final Set<String> INVOICE_SORT_FIELDS =
            Set.of("invoiceNumber", "status", "invoiceDate", "totalAmount", "customer.customerName");

    /**
     * Returns aggregated dashboard statistics: total customers, total invoices,
     * and the sum of all invoice totalAmount values.
     *
     * @param user the authenticated user making the request
     * @return 200 OK with the authenticated user and a {@code Stats} object
     */
    @GetMapping("/stats")
    public ResponseEntity<HttpResponse> getStats(@AuthenticationPrincipal UserDTO user) {
        Collection<Long> scope = resolveScope(user);
        Stats stats;
        Map<String, Integer> statusBreakdown;
        if (scope == null) {
            stats = customerService.getStats();
            statusBreakdown = customerService.getCustomerStatusBreakdown();
        } else if (scope.isEmpty()) {
            stats = new Stats();
            statusBreakdown = Map.of();
        } else {
            stats = customerService.getStatsForOrganizations(scope);
            statusBreakdown = customerService.getCustomerStatusBreakdownForOrganizations(scope);
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "stats", stats,
                                "statusBreakdown", statusBreakdown))
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
     * @param sort the column to order by as {@code field,direction} (e.g. {@code customerName,desc});
     *             unset or unrecognized falls back to unsorted — see {@link #CUSTOMER_SORT_FIELDS}
     * @return 200 OK with the authenticated user and a page of customers
     */
    @GetMapping("/list")
    public ResponseEntity<HttpResponse> getCustomers(@AuthenticationPrincipal UserDTO user, @RequestParam Optional<Integer> page, @RequestParam Optional<Integer> size, @RequestParam Optional<String> sort) {
        Collection<Long> scope = resolveScope(user);
        int pageIndex = page.orElse(0);
        int pageSize = size.orElse(20);
        Sort resolvedSort = SortUtils.resolveSort(sort, CUSTOMER_SORT_FIELDS);
        Page<Customer> customers;
        Stats stats;
        Map<String, Integer> statusBreakdown;
        if (scope == null) {
            customers = customerService.getCustomers(pageIndex, pageSize, resolvedSort);
            stats = customerService.getStats();
            statusBreakdown = customerService.getCustomerStatusBreakdown();
        } else if (scope.isEmpty()) {
            customers = Page.empty(PageRequest.of(pageIndex, pageSize));
            stats = new Stats();
            statusBreakdown = Map.of();
        } else {
            customers = customerService.getCustomersForOrganizations(scope, pageIndex, pageSize, resolvedSort);
            stats = customerService.getStatsForOrganizations(scope);
            statusBreakdown = customerService.getCustomerStatusBreakdownForOrganizations(scope);
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "page", customers,
                                "stats", stats,
                                "statusBreakdown", statusBreakdown))
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
    public ResponseEntity<HttpResponse> getCustomer(@AuthenticationPrincipal UserDTO user, @PathVariable Long customerId) {
        Customer customer = customerService.getCustomer(customerId);
        requireInScope(resolveScope(user), customer);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customer))
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
     * @param sort the column to order by as {@code field,direction}; unset or unrecognized falls
     *             back to unsorted — see {@link #CUSTOMER_SORT_FIELDS}
     * @return 200 OK with the authenticated user and a page of matching customers
     * // @throws InterruptedException if the thread is interrupted during the artificial delay
     */
    @GetMapping("/search")
    public ResponseEntity<HttpResponse> searchCustomer(@AuthenticationPrincipal UserDTO user, @RequestParam Optional<String> name, @RequestParam Optional<Integer> page, @RequestParam Optional<Integer> size, @RequestParam Optional<String> sort) { //throws InterruptedException {
        //TimeUnit.SECONDS.sleep(2); // Artificial delay to simulate real-world search latency
        Collection<Long> scope = resolveScope(user);
        int pageIndex = page.orElse(0);
        int pageSize = size.orElse(20);
        String term = name.orElse("");
        Sort resolvedSort = SortUtils.resolveSort(sort, CUSTOMER_SORT_FIELDS);
        Page<Customer> results;
        if (scope == null) {
            results = customerService.searchCustomers(term, pageIndex, pageSize, resolvedSort);
        } else if (scope.isEmpty()) {
            results = Page.empty(PageRequest.of(pageIndex, pageSize));
        } else {
            results = customerService.searchCustomersForOrganizations(term, scope, pageIndex, pageSize, resolvedSort);
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "page", results))
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
    public ResponseEntity<HttpResponse> updateCustomer(@AuthenticationPrincipal UserDTO user, @PathVariable Long customerId, @RequestBody @Valid Customer customer) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customerService.updateCustomer(customerId, customer)))
                        .message("Customer updated!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a new customer record, stamped with the creator's organization (FR-ORG-2).
     *
     * <p>The owning organization is taken from the <em>authenticated principal</em>, never from the
     * request body. A client-supplied {@code organizationId} would let any user file a customer
     * into an organization they do not belong to — writing rows into another tenant's dashboards —
     * so the value is overwritten here regardless of what was posted.
     *
     * <p>A creator belonging to several organizations is attributed to the lowest id, and one
     * belonging to none leaves the customer unowned (invisible to scoped reporting, visible to the
     * unscoped admin tiers). Both are placeholders for an explicit organization picker on the
     * new-customer form; picking deterministically here is what keeps newly created rows from
     * silently vanishing from the creator's own dashboard in the meantime.
     *
     * @param user     the authenticated user making the request
     * @param customer the customer data to create
     * @return 201 Created with the authenticated user and the newly created customer
     */
    @PostMapping("/create")
    public ResponseEntity<HttpResponse> createCustomer(@AuthenticationPrincipal UserDTO user, @RequestBody @Valid Customer customer) {
        customer.setOrganizationId(
                organizationService.findActiveOrganizationIds(user.getId()).stream()
                        .min(Long::compareTo)
                        .orElse(null));
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

    /**
     * Bulk-creates customers from an uploaded CSV or XLSX file (POST-SUBMISSION-UPGRADES.md #8,
     * FUTURE-ENHANCEMENTS.md §3.3 "P2-2").
     *
     * <p>Partial-success by design: the response is 200 OK whenever the file itself was
     * readable, even if every row inside it failed validation — {@code data.result} carries the
     * per-row breakdown ({@code imported} count plus a {@code failed} list with a reason per
     * row) for the UI to render as a report. Only a structurally bad request (unreadable file,
     * wrong file type, more rows than {@code BatchImportServiceImpl.MAX_BATCH_ROWS}) throws
     * before any row is attempted.
     *
     * <p>Every created customer is stamped with the caller's organization exactly like {@link
     * #createCustomer} — a client-supplied organization column in the file would let an org
     * admin file rows into an organization they don't belong to, so there is deliberately no
     * such column in the expected schema.
     *
     * <p>Gated by the same {@code POST /**} catch-all as every other write here — see {@link
     * BatchImportService#importCustomers} for the expected columns.
     *
     * @param user the authenticated user making the request
     * @param file the uploaded {@code .csv} or {@code .xlsx} file, sent as {@code
     *             multipart/form-data} under the key {@code "file"}
     * @return 200 OK with the authenticated user and the row-by-row {@link BatchImportResult}
     */
    @PostMapping("/batch")
    public ResponseEntity<HttpResponse> batchImportCustomers(@AuthenticationPrincipal UserDTO user, @RequestParam("file") MultipartFile file) {
        Long organizationId = organizationService.findActiveOrganizationIds(user.getId()).stream()
                .min(Long::compareTo)
                .orElse(null);
        BatchImportResult result = batchImportService.importCustomers(file, organizationId);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "result", result))
                        .message(result.imported() + " of " + (result.imported() + result.failed().size()) + " rows imported")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Links an existing standalone (draft) invoice to a customer.
     *
     * <p>The completion of {@code POST /invoice/create}, which raises an invoice with no owner.
     * Distinct from {@code POST /invoice/addtocustomer/{customerId}} below, which <em>creates</em>
     * a new invoice already attached: the two differ in whether the invoice exists yet, and
     * conflating them into one endpoint would mean a request whose meaning depended on whether a
     * field happened to be populated.
     *
     * <p>{@code PUT} because assigning an owner is idempotent — repeating it lands the invoice on
     * the same customer.
     *
     * @param user       the authenticated user making the request
     * @param invoiceId  the id of the existing invoice
     * @param customerId the id of the customer to attach it to
     * @return 200 OK with the authenticated user and the now-owned invoice
     */
    @PutMapping("/invoice/{invoiceId}/addtocustomer/{customerId}")
    public ResponseEntity<HttpResponse> linkInvoiceToCustomer(@AuthenticationPrincipal UserDTO user,
                                                              @PathVariable Long invoiceId,
                                                              @PathVariable Long customerId) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoice", customerService.linkInvoiceToCustomer(invoiceId, customerId)))
                        .message("Invoice linked to customer successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Applies edits to an existing invoice (ROADMAP §2 — "Edit invoices").
     *
     * <p>Invoices were create-only, so a wrong amount or a status needing correction could only be
     * addressed by issuing a second invoice — leaving the incorrect one in the customer's history
     * and in every revenue figure derived from it.
     *
     * <p>{@code PATCH} because the service applies only the editable fields: the invoice number
     * and the owning customer are deliberately not changeable here (the number is an external
     * reference already on documents the customer holds; reassigning ownership is the separate
     * operation above).
     *
     * <p>Authorization: {@code PATCH} falls through to SecurityConfig's
     * {@code .requestMatchers(POST, "/**")}-adjacent rules via {@code anyRequest().authenticated()},
     * with method-level intent recorded here — {@code UPDATE:CUSTOMER} or {@code UPDATE:USER} is
     * the same pair that gates creating one, since being able to rewrite an invoice is at least as
     * consequential as raising it.
     *
     * @param user      the authenticated user making the request
     * @param invoiceId the id of the invoice to edit
     * @param invoice   the submitted values
     * @return 200 OK with the authenticated user and the updated invoice
     */
    @PatchMapping("/invoice/update/{invoiceId}")
    @PreAuthorize("hasAnyAuthority('UPDATE:CUSTOMER', 'UPDATE:USER')")
    public ResponseEntity<HttpResponse> updateInvoice(@AuthenticationPrincipal UserDTO user,
                                                      @PathVariable Long invoiceId,
                                                      @RequestBody @Valid Invoice invoice) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoice", customerService.updateInvoice(invoiceId, invoice)))
                        .message("Invoice updated successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a new standalone invoice (not yet linked to a customer).
     * Use {@code /invoice/addtocustomer/{customerId}} to attach it to a customer.
     *
     * @param user    the authenticated user making the request
     * @param invoice the invoice data to create
     * @return 201 Created with the authenticated user and the newly created invoice
     */
    @PostMapping("/invoice/create")
    public ResponseEntity<HttpResponse> createInvoice(@AuthenticationPrincipal UserDTO user, @RequestBody @Valid Invoice invoice) {
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
     * Bulk-creates invoices from an uploaded CSV or XLSX file, each row linked to an existing
     * customer by email (POST-SUBMISSION-UPGRADES.md #8, FUTURE-ENHANCEMENTS.md §3.3 "P2-2").
     *
     * <p>Unlike {@link #batchImportCustomers}, this endpoint creates no customers — a row whose
     * {@code customerEmail} does not match an existing customer is rejected rather than treated
     * as an implicit customer-creation request, the same "link to existing" contract {@link
     * #linkInvoiceToCustomer} follows for a single invoice.
     *
     * <p>Organization-scoped the same way every other invoice-touching endpoint here is: a
     * scoped caller's rows are checked against {@link #resolveScope}, so a row cannot bill an
     * invoice to a customer outside the caller's organizations just by naming their email.
     *
     * @param user the authenticated user making the request
     * @param file the uploaded {@code .csv} or {@code .xlsx} file, sent as {@code
     *             multipart/form-data} under the key {@code "file"}
     * @return 200 OK with the authenticated user and the row-by-row {@link BatchImportResult}
     */
    @PostMapping("/invoice/batch")
    public ResponseEntity<HttpResponse> batchImportInvoices(@AuthenticationPrincipal UserDTO user, @RequestParam("file") MultipartFile file) {
        Collection<Long> scope = resolveScope(user);
        BatchImportResult result = batchImportService.importInvoices(file, scope);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "result", result))
                        .message(result.imported() + " of " + (result.imported() + result.failed().size()) + " rows imported")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns a paginated list of all invoices.
     *
     * @param user the authenticated user making the request
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @param sort the column to order by as {@code field,direction}; unset or unrecognized falls
     *             back to unsorted — see {@link #INVOICE_SORT_FIELDS}
     * @return 200 OK with the authenticated user and a page of invoices
     */
    @GetMapping("/invoice/list")
    public ResponseEntity<HttpResponse> getInvoices(@AuthenticationPrincipal UserDTO user, @RequestParam Optional<Integer> page, @RequestParam Optional<Integer> size, @RequestParam Optional<String> sort) {
        Collection<Long> scope = resolveScope(user);
        int pageIndex = page.orElse(0);
        int pageSize = size.orElse(20);
        Sort resolvedSort = SortUtils.resolveSort(sort, INVOICE_SORT_FIELDS);
        Page<Invoice> invoices;
        if (scope == null) {
            invoices = customerService.getInvoices(pageIndex, pageSize, resolvedSort);
        } else if (scope.isEmpty()) {
            invoices = Page.empty(PageRequest.of(pageIndex, pageSize));
        } else {
            invoices = customerService.getInvoicesForOrganizations(scope, pageIndex, pageSize, resolvedSort);
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoices", invoices))
                        .message("All Invoices retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Searches for invoices whose invoice number or owning customer's name contains the given
     * search term.
     *
     * @param user the authenticated user making the request
     * @param term the substring to search for, matched against the invoice number and the
     *             owning customer's name (defaults to empty, returning all)
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @param sort the column to order by as {@code field,direction}; unset or unrecognized falls
     *             back to unsorted — see {@link #INVOICE_SORT_FIELDS}
     * @return 200 OK with the authenticated user and a page of matching invoices
     */
    @GetMapping("/invoice/search")
    public ResponseEntity<HttpResponse> searchInvoices(@AuthenticationPrincipal UserDTO user, @RequestParam Optional<String> term, @RequestParam Optional<Integer> page, @RequestParam Optional<Integer> size, @RequestParam Optional<String> sort) {
        Collection<Long> scope = resolveScope(user);
        int pageIndex = page.orElse(0);
        int pageSize = size.orElse(20);
        String search = term.orElse("");
        Sort resolvedSort = SortUtils.resolveSort(sort, INVOICE_SORT_FIELDS);
        Page<Invoice> results;
        if (scope == null) {
            results = customerService.searchInvoices(search, pageIndex, pageSize, resolvedSort);
        } else if (scope.isEmpty()) {
            results = Page.empty(PageRequest.of(pageIndex, pageSize));
        } else {
            results = customerService.searchInvoicesForOrganizations(search, scope, pageIndex, pageSize, resolvedSort);
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoices", results))
                        .message("Invoices found!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns all data needed to populate the new-invoice creation form in the UI.
     * <p>
     * Returns the authenticated user, the full unpaginated customer list (for the
     * customer dropdown), and the full services catalog (for the service line-item
     * dropdown). All three are needed before the user can fill in the form.
     *
     * @param user the authenticated user making the request
     * @return 200 OK with {@code "user"}, {@code "customers"}, and {@code "availableServices"}
     */
    @GetMapping("/invoice/new")
    public ResponseEntity<HttpResponse> newInvoice(@AuthenticationPrincipal UserDTO user) {
        Collection<Long> scope = resolveScope(user);
        Iterable<Customer> customers = scope == null ? customerService.getCustomers()
                : scope.isEmpty() ? List.of()
                : customerService.getCustomersForOrganizations(scope);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customers,
                                "availableServices", customerService.getServices()))
                        .message("New invoice page reached and Customers have been retrieved!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Returns a single invoice and its associated customer by invoice ID.
     * <p>
     * The invoice is fetched once and reused for both the {@code "invoice"} payload
     * and the {@code "customer"} payload via {@link Invoice#getCustomer()}, avoiding
     * a redundant second database round-trip.
     *
     * @param user      the authenticated user making the request
     * @param invoiceId the ID of the invoice to retrieve
     * @return 200 OK with three data keys: {@code "user"} (authenticated principal),
     * {@code "invoice"} (the matching invoice), and {@code "customer"}
     * (the customer the invoice belongs to)
     */
    @GetMapping("/invoice/get/{invoiceId}")
    public ResponseEntity<HttpResponse> getInvoice(@AuthenticationPrincipal UserDTO user, @PathVariable Long invoiceId) {
        Invoice invoice = customerService.getInvoice(invoiceId);
        requireInScope(resolveScope(user), invoice);
        // A draft invoice (ROADMAP: nullable customer) has no customer to embed. Map.of(...)
        // throws NullPointerException on a null VALUE — not just a null key — so a draft fetched
        // through this endpoint 500'd unconditionally before this fix, independent of scoping.
        // A mutable map is used here specifically because this is the one response in the
        // controller where a value can legitimately be null.
        Map<String, Object> data = new HashMap<>();
        data.put("user", userService.getUserByEmail(user.getEmail()));
        data.put("invoice", invoice);
        data.put("customer", invoice.getCustomer());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(data)
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
    public ResponseEntity<HttpResponse> addInvoiceToCustomer(@AuthenticationPrincipal UserDTO user, @PathVariable Long customerId, @RequestBody @Valid Invoice invoice) {
        customerService.addInvoiceToCustomer(customerId, invoice);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "customers", customerService.getCustomers()))
                        .message(String.format("Invoice added to customer for Customer with ID: %s!", customerId))
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Streams a full customer list as an XLSX file.
     *
     * <p>Fetches every customer via {@link CustomerService#getCustomers()} (no-arg, unpaginated),
     * builds the workbook in {@link CustomerReport}, and returns it as an attachment so the
     * browser triggers a file-save dialog. The Angular frontend calls this via
     * {@code CustomerService.downloadCustomerReport$()}.
     *
     * @return 200 OK with an XLSX body and {@code Content-Disposition: attachment}
     */
    @GetMapping("/download/report")
    public ResponseEntity<Resource> exportReport(@AuthenticationPrincipal UserDTO user) { //throws InterruptedException {
        //TimeUnit.SECONDS.sleep(2); // Simulate report generation time
        Collection<Long> scope = resolveScope(user);
        Iterable<Customer> source = scope == null ? customerService.getCustomers()
                : scope.isEmpty() ? List.of()
                : customerService.getCustomersForOrganizations(scope);
        List<Customer> customers = new ArrayList<>();
        source.iterator().forEachRemaining(customers::add);
        CustomerReport customerReport = new CustomerReport(customers);
        HttpHeaders headers = new HttpHeaders();
        headers.add("File-Name", "customer_report.xlsx");
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=customer_report.xlsx");
        return ResponseEntity.ok()
                .contentType(parseMediaType("application/vnd.ms-excel"))
                .headers(headers)
                .body(customerReport.exportReport());
    }

    /**
     * Streams a full invoice list as an XLSX file.
     *
     * <p>Fetches every invoice via {@link CustomerService#getInvoices()} (no-arg, unpaginated),
     * builds the workbook in {@link InvoiceReport}, and returns it as an attachment so the
     * browser triggers a file-save dialog. The Angular frontend calls this via
     * {@code CustomerService.downloadInvoiceReport$()}.
     *
     * @return 200 OK with an XLSX body and {@code Content-Disposition: attachment}
     */
    @GetMapping("/invoice/download/report")
    public ResponseEntity<Resource> exportInvoiceReport(@AuthenticationPrincipal UserDTO user) {
        Collection<Long> scope = resolveScope(user);
        Iterable<Invoice> source = scope == null ? customerService.getInvoices()
                : scope.isEmpty() ? List.of()
                : customerService.getInvoicesForOrganizations(scope);
        List<Invoice> invoices = new ArrayList<>();
        source.iterator().forEachRemaining(invoices::add);
        InvoiceReport invoiceReport = new InvoiceReport(invoices);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice_report.xlsx\"");
        return ResponseEntity.ok()
                .contentType(parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .headers(headers)
                .body(invoiceReport.exportReport());
    }

    /**
     * Streams a single invoice as a PDF file (POST-SUBMISSION-UPGRADES.md "PDF invoice
     * attachments"), and the server-side rendering also reused by {@link #emailInvoice} below.
     *
     * <p>Distinct from the invoice screen's existing "Export PDF" button, which renders
     * client-side (jsPDF, DOM-to-canvas) and never touches this endpoint — that path stays as-is.
     * This one exists because a server-side render is what {@link #emailInvoice} needs to attach
     * to an outbound email, and exposing it as a direct download too avoids maintaining two PDF
     * layouts. A draft invoice (no linked customer) still downloads; see {@link InvoicePdfReport}
     * for why only the email path below refuses one.
     *
     * @param user      the authenticated user making the request
     * @param invoiceId the ID of the invoice to render
     * @return 200 OK with a PDF body and {@code Content-Disposition: attachment}
     */
    @GetMapping("/invoice/{invoiceId}/download/pdf")
    public ResponseEntity<Resource> exportInvoicePdf(@AuthenticationPrincipal UserDTO user, @PathVariable Long invoiceId) {
        Invoice invoice = customerService.getInvoice(invoiceId);
        requireInScope(resolveScope(user), invoice);
        byte[] pdfBytes = new InvoicePdfReport(invoice).exportReport();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"invoice-" + invoice.getInvoiceNumber() + ".pdf\"");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .headers(headers)
                .body(new ByteArrayResource(pdfBytes));
    }

    /**
     * Emails a PDF copy of an invoice to its owning customer — the manual "Email Invoice" button
     * on the invoice screen (POST-SUBMISSION-UPGRADES.md "PDF invoice attachments").
     *
     * <p>Refused with 400 for a draft invoice (no linked customer yet — see
     * {@link Invoice#getCustomer()}): there is no address to send it to, and linking it to a
     * customer first via {@code PUT .../addtocustomer/{customerId}} is the existing, correct fix
     * rather than something this endpoint should paper over.
     *
     * <p>Sent synchronously, unlike the account-lifecycle emails in {@link EmailService} — those
     * are fire-and-forget by design (see {@code EmailServiceImpl}'s class Javadoc), but a manual
     * button click is exactly the case where the caller needs to know whether the send actually
     * succeeded rather than getting an optimistic 200 regardless.
     *
     * <p>Gated the same as editing the invoice ({@link #updateInvoice}) rather than a new
     * capability: sending a customer their invoice is at least as consequential as changing it.
     *
     * @param user      the authenticated user making the request
     * @param invoiceId the ID of the invoice to email
     * @return 200 OK with the authenticated user, or 400 if the invoice has no customer yet
     */
    @PostMapping("/invoice/{invoiceId}/email")
    @PreAuthorize("hasAnyAuthority('UPDATE:CUSTOMER', 'UPDATE:USER')")
    public ResponseEntity<HttpResponse> emailInvoice(@AuthenticationPrincipal UserDTO user, @PathVariable Long invoiceId) {
        Invoice invoice = customerService.getInvoice(invoiceId);
        requireInScope(resolveScope(user), invoice);
        Customer customer = invoice.getCustomer();
        if (customer == null) {
            throw new ApiException("This invoice has no customer attached yet — link it to a customer before emailing it.");
        }
        byte[] pdfBytes = new InvoicePdfReport(invoice).exportReport();
        emailService.sendInvoiceEmail(customer.getCustomerName(), customer.getEmail(), invoice.getInvoiceNumber(), pdfBytes);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail())))
                        .message("Invoice emailed to " + customer.getEmail() + "!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Resolves the organization restriction that applies to this caller's view of customers and
     * invoices (FR-ORG-2, 2026-08-08), mirroring {@code AnalyticsController#resolveScope} exactly
     * so "who is scoped?" has one answer across the admin AND shared surfaces.
     *
     * <p>Delegates to {@link RoleType#isOrganizationScoped(String)} rather than naming
     * {@code ROLE_ORGANIZATION_ADMIN} literally: every role below the unscoped tiers
     * ({@code ROLE_ADMIN}, {@code ROLE_APPLICATION_ADMIN}) is scoped, including plain
     * {@code ROLE_USER}, {@code ROLE_MODERATOR}, and {@code ROLE_HELP_DESK_ADMIN} — the same
     * literal-name bug {@code AnalyticsController#resolveScope} fixed on 2026-08-13 was still
     * live here until now, so a plain user saw every organization's customers and invoices
     * regardless of which business they actually belonged to.
     *
     * @param caller the authenticated principal from the JWT
     * @return {@code null} when unscoped, otherwise the caller's active organization ids (possibly empty)
     */
    private Collection<Long> resolveScope(UserDTO caller) {
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) {
            return null;
        }
        return organizationService.findActiveOrganizationIds(caller.getId());
    }

    /**
     * Enforces the resolved scope on a single customer already fetched by id. A {@code null} scope
     * (unscoped caller) always passes; a scoped caller is refused with a generic 403 — naming
     * nothing about the customer — when its {@code organizationId} is not in their active set,
     * including when it is {@code null} (an unowned customer is not evidence of shared ownership).
     *
     * @param scope    the caller's resolved scope from {@link #resolveScope}, or {@code null}
     * @param customer the customer to check
     */
    private static void requireInScope(Collection<Long> scope, Customer customer) {
        if (scope == null) return;
        // Checked separately from the contains() call below: List.of(...)'s immutable-list
        // implementation THROWS NullPointerException from contains(null) rather than returning
        // false, so an unowned customer (organizationId == null) would crash this check instead
        // of being correctly refused.
        Long organizationId = customer.getOrganizationId();
        if (organizationId == null || !scope.contains(organizationId)) {
            throw new AccessDeniedException("This customer is outside your organization scope.");
        }
    }

    /**
     * Enforces the resolved scope on a single invoice already fetched by id. Invoices carry no
     * tenant column of their own — the check reads {@code invoice.getCustomer()}'s organization,
     * and a draft invoice (no customer yet) is treated as out of scope for a scoped caller, the
     * same fail-closed direction {@code InvoiceRepo#findByOrganizationIdIn} already takes.
     *
     * @param scope   the caller's resolved scope from {@link #resolveScope}, or {@code null}
     * @param invoice the invoice to check
     */
    private static void requireInScope(Collection<Long> scope, Invoice invoice) {
        if (scope == null) return;
        Long organizationId = invoice.getCustomer() != null ? invoice.getCustomer().getOrganizationId() : null;
        // See the Customer overload above: contains(null) throws on an immutable List.of(...)
        // rather than returning false, so the null case must short-circuit before it.
        if (organizationId == null || !scope.contains(organizationId)) {
            throw new AccessDeniedException("This invoice is outside your organization scope.");
        }
    }

}
