package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Admin-only reporting surface backing the Billing overview ({@code /billing}) and the
 * Analytics hub ({@code /analytics}) SPA pages.
 *
 * <p><b>Why this controller exists.</b> The customer/invoice/stats data these two pages
 * visualise is served application-wide through {@link CustomerController} ({@code
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
 * components consume these admin URLs with no interface changes. The rollups remain
 * system-wide for now; org-scoping the aggregates (so an org admin sees only their
 * organization's numbers) is tracked as future work, consistent with the current
 * behaviour of the rest of the admin surface.
 */
@RestController
@RequestMapping(path = "/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final CustomerService customerService;
    private final UserService userService;

    /**
     * KPI summary for the Billing overview: system-wide totals plus the per-status
     * customer breakdown. Mirrors the {@code stats}/{@code statusBreakdown} keys of
     * {@code GET /customer/stats}, but admin-gated.
     *
     * @param user the authenticated (admin) principal, embedded in the envelope
     * @return 200 OK with {@code user}, {@code stats}, and {@code statusBreakdown}
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getSummary(@AuthenticationPrincipal UserDTO user) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "stats", customerService.getStats(),
                                "statusBreakdown", customerService.getCustomerStatusBreakdown()))
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
     * @return 200 OK with {@code user}, {@code page}, {@code stats}, {@code statusBreakdown}
     */
    @GetMapping("/customers")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getCustomers(@AuthenticationPrincipal UserDTO user,
                                                     @RequestParam Optional<Integer> page,
                                                     @RequestParam Optional<Integer> size) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "page", customerService.getCustomers(page.orElse(0), size.orElse(20)),
                                "stats", customerService.getStats(),
                                "statusBreakdown", customerService.getCustomerStatusBreakdown()))
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
     * @return 200 OK with {@code user} and {@code invoices}
     */
    @GetMapping("/invoices")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getInvoices(@AuthenticationPrincipal UserDTO user,
                                                    @RequestParam Optional<Integer> page,
                                                    @RequestParam Optional<Integer> size) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "invoices", customerService.getInvoices(page.orElse(0), size.orElse(20))))
                        .message("Analytics invoices retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
