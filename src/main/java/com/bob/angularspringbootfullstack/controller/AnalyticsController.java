package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.model.Customer;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Invoice;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.ReportDigestService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.utils.OrganizationScope;
import com.bob.angularspringbootfullstack.utils.SortUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Admin-only reporting surface backing the Billing overview ({@code /billing}) and the
 * Analytics hub ({@code /analytics}) SPA pages.
 *
 * <p><b>Why this controller exists.</b> The customer/invoice/stats data these two pages
 * visualize is served application-wide through {@link CustomerController} ({@code
 * /customer/list}, {@code /customer/stats}, {@code /customer/invoice/list}) because the
 * home dashboard and the customers/invoices pages — visible to every authenticated user —
 * legitimately need it. That means those endpoints cannot be locked to admins without
 * breaking non-admin pages. Yet the <em>aggregate financial rollups</em> (total billed,
 * revenue trends, cross-customer status breakdowns) are a privileged, admin-only view.
 *
 * <p>Resolving that tension is the whole point of this class: it re-exposes exactly the
 * data the two dashboards need, but under {@code /admin/analytics/**}, so a genuine
 * server-side authority check gates it. A plain {@code ROLE_USER} (which holds
 * {@code READ:USER}/{@code READ:CUSTOMER} and can therefore still reach the shared
 * {@code /customer/**} GETs) receives <b>403</b> here — closing the mismatch where the
 * SPA's {@code adminGuard} hid the dashboards but the underlying data was fetchable
 * directly.
 *
 * <p><b>Authorization is enforced at two levels</b>, mirroring {@link AdminUserController}:
 * <ul>
 *   <li><b>URL level</b> — {@code SecurityConfig} already requires
 *       {@code UPDATE:USER} or {@code UPDATE:ROLE} for everything under {@code /admin/**},
 *       and that matcher is evaluated <em>above</em> the broad {@code GET /**} catch-all,
 *       so no new (mis-orderable) matcher was introduced.</li>
 *   <li><b>Method level</b> — {@link PreAuthorize} repeats the requirement so a future
 *       routing change cannot silently reopen access.</li>
 * </ul>
 * These are exactly the authorities the frontend {@code adminGuard} checks, keeping the
 * route gate and the API gate in lockstep (NFR-SEC-4).
 *
 * <p>Each endpoint returns the project's standard {@link HttpResponse} envelope and
 * deliberately reuses the <em>same data keys</em> the shared {@code /customer/**}
 * endpoints emit ({@code stats}, {@code page}, {@code invoices}), so the Angular
 * components consume these admin URLs with no interface changes.
 *
 * <p><b>Organization scoping (FR-ORG-2).</b> Authority answers <em>whether</em> a caller may open
 * these dashboards; it says nothing about <em>whose</em> numbers they contain. Until customers
 * carried an owning organization there was no way to express the difference, so every
 * {@code ROLE_ORGANIZATION_ADMIN} saw system-wide totals — including the customer counts and
 * revenue of organizations they have no relationship with. That is now closed: a scoped caller's
 * rollups are restricted to the organizations they actively belong to, while {@code ROLE_ADMIN}
 * and {@code ROLE_APPLICATION_ADMIN} remain unscoped (FR-ORG-3), matching how
 * {@link AdminUserController} already treats the user directory.
 *
 * <p>The restriction is applied inside the SQL rather than to the returned rows. An aggregate has
 * already discarded its attribution by the time it is a number — there is no way to subtract
 * another organization's contribution from a {@code SUM} after the fact — and filtering a page
 * after retrieval would corrupt {@code totalElements} and return short pages.
 */
@RestController
@RequestMapping(path = "/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final CustomerService customerService;
    private final UserService userService;
    /** Resolves which organizations a scoped caller may see (FR-ORG-2). */
    private final OrganizationService organizationService;
    /** Builds and sends the report digest emailed by {@link #emailReport}. */
    private final ReportDigestService reportDigestService;

    /** Mirrors {@code CustomerController}'s customer sort allow-list — kept as a separate
     * constant because the two controllers are not otherwise coupled and a shared field would
     * invite one to change silently for the other. */
    private static final Set<String> CUSTOMER_SORT_FIELDS =
            Set.of("customerName", "status", "type", "email", "createdAt");

    /** Mirrors {@code CustomerController}'s invoice sort allow-list. */
    private static final Set<String> INVOICE_SORT_FIELDS =
            Set.of("invoiceNumber", "status", "invoiceDate", "totalAmount", "customer.customerName");

    /**
     * KPI summary for the Billing overview: system-wide totals plus the per-status
     * customer breakdown. Mirrors the {@code stats}/{@code statusBreakdown} keys of
     * {@code GET /customer/stats}, but admin-gated.
     *
     * @param user           the authenticated (admin) principal, embedded in the envelope
     * @param organizationId optional org-filter dropdown selection (dashboard revamp,
     *                       2026-08-22): narrows the rollups to one organization. Silently has no
     *                       effect if the id falls outside the caller's own scope — see
     *                       {@link #resolveScope(UserDTO, Long)}.
     * @return 200 OK with {@code user}, {@code stats}, and {@code statusBreakdown}
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getSummary(@AuthenticationPrincipal UserDTO user,
                                                    @RequestParam(required = false) Long organizationId) {
        Collection<Long> scope = resolveScope(user, organizationId);
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
                        .message("Analytics summary retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Paginated customer set for the Analytics hub's growth/acquisition charts. Mirrors
     * the {@code page} key of {@code GET /customer/list}, but admin-gated.
     *
     * @param user the authenticated (admin) principal, embedded in the envelope
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @param sort the column to order by as {@code field,direction}; unset or unrecognized falls
     *             back to unsorted
     * @param organizationId optional org-filter dropdown selection; see
     *                       {@link #getSummary(UserDTO, Long)}.
     * @return 200 OK with {@code user}, {@code page}, {@code stats}, {@code statusBreakdown}
     */
    @GetMapping("/customers")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getCustomers(@AuthenticationPrincipal UserDTO user,
                                                     @RequestParam Optional<Integer> page,
                                                     @RequestParam Optional<Integer> size,
                                                     @RequestParam Optional<String> sort,
                                                     @RequestParam(required = false) Long organizationId) {
        Collection<Long> scope = resolveScope(user, organizationId);
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
                        .message("Analytics customers retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Paginated invoice set for the revenue/status charts on both dashboards. Mirrors the
     * {@code invoices} key of {@code GET /customer/invoice/list}, but admin-gated.
     *
     * @param user the authenticated (admin) principal, embedded in the envelope
     * @param page zero-based page index (defaults to 0)
     * @param size number of records per page (defaults to 20)
     * @param sort the column to order by as {@code field,direction}; unset or unrecognized falls
     *             back to unsorted
     * @param organizationId optional org-filter dropdown selection; see
     *                       {@link #getSummary(UserDTO, Long)}.
     * @return 200 OK with {@code user} and {@code invoices}
     */
    @GetMapping("/invoices")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getInvoices(@AuthenticationPrincipal UserDTO user,
                                                    @RequestParam Optional<Integer> page,
                                                    @RequestParam Optional<Integer> size,
                                                    @RequestParam Optional<String> sort,
                                                    @RequestParam(required = false) Long organizationId) {
        Collection<Long> scope = resolveScope(user, organizationId);
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
                        .message("Analytics invoices retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Every invoice for the caller's scope, unpaginated — for chart derivations that need
     * a true total (monthly revenue trend, status breakdown, service revenue table) rather
     * than a page.
     *
     * <p><b>Why this exists alongside {@link #getInvoices}.</b> The Billing overview computed
     * its charts from {@code GET /admin/analytics/invoices?size=200} entirely client-side, so
     * any account with more than 200 invoices silently dropped the rest — the chart looked
     * complete but was quietly wrong, with nothing to indicate truncation. Reuses the exact
     * unpaginated {@code CustomerService} methods {@code CustomerController#exportInvoiceReport}
     * already calls for the invoice XLSX export, which are already org-scoped correctly
     * (FR-ORG-2) — this is not new data access, just a second way to reach data the app
     * already fetches in full elsewhere.
     *
     * @param user the authenticated (admin) principal, embedded in the envelope
     * @return 200 OK with {@code user} and the full unpaginated {@code invoices}
     */
    @GetMapping("/invoices/all")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getAllInvoices(@AuthenticationPrincipal UserDTO user) {
        Collection<Long> scope = resolveScope(user);
        Iterable<Invoice> invoices;
        if (scope == null) {
            invoices = customerService.getInvoices();
        } else if (scope.isEmpty()) {
            invoices = List.of();
        } else {
            invoices = customerService.getInvoicesForOrganizations(scope);
        }
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoices", invoices))
                        .message("Analytics invoices retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Emails the caller their own report digest — the "Email me this report" button on the
     * Analytics screen (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails").
     *
     * <p>Reuses {@link #resolveScope} exactly, so the digest always covers precisely the figures
     * this caller could already see through {@link #getSummary}: nothing wider, nothing narrower.
     * Delegates the actual build-and-send to {@link ReportDigestService}, which the scheduled
     * weekly digest ({@code SchedulingConfig}) also calls — this endpoint and that cron job produce
     * identical digests, just for different recipients.
     *
     * <p>Sent synchronously, matching {@code CustomerController#emailInvoice}: a manual button
     * click should tell the caller whether delivery actually succeeded rather than returning an
     * optimistic 200 regardless.
     *
     * @param user the authenticated (admin) principal — both the caller whose scope is resolved
     *             and the digest's sole recipient
     * @return 200 OK with {@code user}, once the email has been sent
     */
    @PostMapping("/report/email")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> emailReport(@AuthenticationPrincipal UserDTO user) {
        Collection<Long> scope = resolveScope(user);
        reportDigestService.emailReportForCaller(user, scope);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail())))
                        .message("Report emailed to " + user.getEmail() + "!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Resolves the organization restriction that applies to this caller's reports (FR-ORG-2).
     *
     * <p>Delegates to {@link RoleType#isOrganizationScoped(String)} — {@code null} for the two
     * unscoped tiers ({@code ROLE_ADMIN}, {@code ROLE_APPLICATION_ADMIN}), which see every
     * organization's data; scoped for everyone else. The same rule
     * {@link AdminUserController#isOrganizationScoped} applies to the user directory, kept
     * deliberately in one shape (now the same method, not two independently-maintained copies of
     * the same check) so "who is scoped?" has a single answer across the admin surface.
     *
     * <p><b>Fixed 2026-08-13:</b> this used to check the caller's role name against the literal
     * string {@code "ROLE_ORGANIZATION_ADMIN"}, which had the identical bug
     * {@code AdminUserController#isOrganizationScoped} did for the identical reason —
     * {@code ROLE_HELP_DESK_ADMIN} also carries {@code UPDATE:USER} / reaches this controller, but
     * was never in that one-name check, so it saw every organization's rollups unscoped.
     *
     * <p>For a scoped caller this returns their active organization ids, <em>which may be
     * empty</em>. Empty is a meaningful verdict, not an error: an administrator belonging to no
     * active organization may see nothing, and callers render zeros rather than falling back to
     * system-wide data. Collapsing the empty case into "unscoped" would hand the global view to
     * precisely the account with the least established membership.
     *
     * <p>The three-way return ({@code null} / empty / populated) is why callers branch explicitly
     * rather than passing this straight through: an empty collection cannot be handed to the
     * scoped service methods, since SQL has no valid {@code IN ()} and the service fails closed
     * on it by design.
     *
     * @param caller the authenticated principal from the JWT
     * @return {@code null} when the caller is unscoped, otherwise their active organization ids
     */
    private Collection<Long> resolveScope(UserDTO caller) {
        return OrganizationScope.resolve(caller, organizationService);
    }

    /**
     * {@link #resolveScope(UserDTO)}, additionally narrowed to one organization for the Analytics
     * page's org-filter dropdown (dashboard revamp, 2026-08-22).
     *
     * <p>A {@code null} {@code organizationId} (no filter selected) defers entirely to
     * {@link #resolveScope(UserDTO)}. Otherwise the requested id is intersected with the caller's
     * own scope rather than substituted for it: an unscoped caller may filter to any organization,
     * but a scoped caller filtering to an organization outside {@link #resolveScope(UserDTO)}
     * resolves to an <em>empty</em> scope (zero rows), never to that other organization's data —
     * the same fail-closed direction {@link #resolveScope(UserDTO)}'s own Javadoc already commits
     * to for an empty result. This keeps the filter a pure narrowing control; it can only shrink
     * what a caller already sees, never widen it.
     *
     * @param caller         the authenticated principal from the JWT
     * @param organizationId the org-filter dropdown's selection, or {@code null} for "all"
     * @return {@code null} when unscoped and unfiltered; otherwise the effective organization id
     *         set, possibly empty
     */
    private Collection<Long> resolveScope(UserDTO caller, Long organizationId) {
        Collection<Long> scope = resolveScope(caller);
        if (organizationId == null) {
            return scope;
        }
        if (scope == null) {
            return Set.of(organizationId);
        }
        return scope.contains(organizationId) ? Set.of(organizationId) : Set.of();
    }
}
