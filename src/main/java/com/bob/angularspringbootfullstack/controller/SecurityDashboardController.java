package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.SecurityOverview;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.SecurityDashboardService;
import com.bob.angularspringbootfullstack.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_ORGANIZATION_ADMIN;
import static com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl.DEFAULT_LIST_SIZE;
import static com.bob.angularspringbootfullstack.service.serviceimpl.SecurityDashboardServiceImpl.DEFAULT_WINDOW_DAYS;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Admin-only security dashboard API (SRS FR-TPF-2) backing the SPA's {@code /security-overview}
 * page.
 *
 * <h3>Why this completes FR-TPF-1</h3>
 * Anomaly detection shipped as a purely reactive control: a sign-in that did not match an account's
 * history was escalated, the account holder was emailed, and a {@code SUSPICIOUS_LOGIN} row was
 * written. Nobody could look at those rows. That is a real gap rather than a cosmetic one — a
 * detection capability whose output is never reviewed cannot be tuned, cannot be shown to be
 * working, and cannot tell an administrator that one account has been flagged eleven times this
 * week. This controller is the review surface.
 *
 * <h3>Authorization</h3>
 * Mounted under {@code /admin/**}, so {@code SecurityConfig}'s existing matcher
 * ({@code hasAnyAuthority("UPDATE:USER", "UPDATE:ROLE")}) gates it at the URL level with <b>no new
 * matcher to mis-order</b> — the same deliberate choice {@link AnalyticsController} made.
 * {@link PreAuthorize} repeats the requirement at the method level so a future routing change
 * cannot silently reopen it (FR-RBAC-2).
 *
 * <h3>Organization scoping (FR-ORG-2)</h3>
 * Security telemetry is exactly the kind of data where "may I open this screen?" and "whose
 * incidents am I looking at?" must be answered separately. A {@code ROLE_ORGANIZATION_ADMIN} may
 * open the dashboard, but must see only the accounts they administer — the alternative would let
 * any org admin watch every other organization's failed logins, locked accounts, and MFA gaps,
 * which is a more sensitive leak than the billing figures FR-ORG-2 originally closed.
 * {@link #resolveScope} is intentionally identical in shape to {@code AnalyticsController}'s, so
 * "who is scoped?" has one answer across the whole admin surface.
 */
@RestController
@RequestMapping(path = "/admin/security")
@RequiredArgsConstructor
public class SecurityDashboardController {

    private final SecurityDashboardService securityDashboardService;
    private final UserService userService;
    /** Resolves which organizations a scoped caller may see (FR-ORG-2). */
    private final OrganizationService organizationService;

    /**
     * The whole dashboard in one response: counters, the flagged-sign-in table, the login-outcome
     * trend, restricted accounts, MFA adoption, and live session totals.
     *
     * <p>Served as a single endpoint on purpose — see {@link SecurityOverview} for why six panels
     * assembled from six requests would be six different instants of the same database.
     *
     * @param user the authenticated (admin) principal, echoed in the envelope like every other
     *             endpoint in this application
     * @param days how many days of history to summarise; defaults to a week and is clamped by the
     *             service, since it is caller-supplied input to a set of aggregate queries
     * @param suspiciousPage 0-based page of the flagged sign-ins table, defaulting to the first.
     *                       Its own parameter rather than a shared {@code page} because the two
     *                       tables are read independently — paging through flagged sign-ins must not
     *                       silently reset the restricted-accounts list the admin was working down
     * @param suspiciousSize rows per page for the flagged sign-ins table (defaults to
     *                       {@value DEFAULT_LIST_SIZE}); clamped by the service, which also reports
     *                       the clamped value back in the table's {@code PageInfo}
     * @param restrictedPage 0-based page of the locked/disabled accounts table, same reasoning
     * @param restrictedSize rows per page for that table. Its own parameter rather than a shared
     *                       {@code size} so an admin can page a long lockout list in tens while
     *                       still scanning flagged sign-ins fifty at a time
     * @return 200 OK with {@code user} and {@code overview}; each paged table carries its own
     *         {@code PageInfo} inside the overview
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getOverview(@AuthenticationPrincipal UserDTO user,
                                                    @RequestParam Optional<Integer> days,
                                                    @RequestParam(defaultValue = "0") int suspiciousPage,
                                                    @RequestParam(defaultValue = "" + DEFAULT_LIST_SIZE) int suspiciousSize,
                                                    @RequestParam(defaultValue = "0") int restrictedPage,
                                                    @RequestParam(defaultValue = "" + DEFAULT_LIST_SIZE) int restrictedSize) {
        SecurityOverview overview = securityDashboardService.getOverview(
                resolveScope(user), days.orElse(DEFAULT_WINDOW_DAYS),
                suspiciousPage, suspiciousSize, restrictedPage, restrictedSize);

        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()),
                                "overview", overview))
                        .message("Security overview retrieved successfully!")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Resolves the organization restriction that applies to this caller (FR-ORG-2).
     *
     * <p>{@code null} for the unscoped tiers ({@code ROLE_ADMIN}, {@code ROLE_APPLICATION_ADMIN}),
     * which see every organization. For {@code ROLE_ORGANIZATION_ADMIN}, the ids of their active
     * memberships — possibly empty, which the service treats as "sees nothing" rather than
     * collapsing into "unscoped".
     *
     * @param caller the authenticated principal from the JWT
     * @return {@code null} when the caller is unscoped, otherwise their active organization ids
     */
    private Collection<Long> resolveScope(UserDTO caller) {
        if (!ROLE_ORGANIZATION_ADMIN.name().equals(caller.getRoleName())) {
            return null;
        }
        return organizationService.findActiveOrganizationIds(caller.getId());
    }
}
