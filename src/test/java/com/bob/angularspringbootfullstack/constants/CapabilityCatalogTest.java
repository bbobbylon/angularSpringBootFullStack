package com.bob.angularspringbootfullstack.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Specs for {@link CapabilityCatalog} — the mapping that lets a 403 name the capability the caller
 * was missing (ROADMAP §2, API-level permission-denied UX).
 *
 * <p>Since backend-driven i18n (FUTURE-ENHANCEMENTS.md §3.3), this class resolves a request to a
 * {@code messages*.properties} <b>key</b> only — never a finished sentence, and never in a specific
 * language. Locale-aware text resolution, and the non-enumeration / frontend-phrasing-match
 * properties that depend on the resolved English text, moved to
 * {@code CustomAccessDeniedHandlerTest}, which is where a {@code MessageSource} is actually
 * available to resolve them.
 *
 * <p>Two properties matter here, and they pull against each other. The key must be
 * <b>specific</b> enough to eventually tell a user what to ask an administrator for, and
 * <b>generic</b> enough that it never becomes an enumeration channel. The ordering tests protect
 * the first; the prefix-matching test protects the second.
 *
 * <p>Rule ordering gets its own cases because it is the failure mode that will actually happen: the
 * table is ordered most-specific-first, exactly like {@code SecurityConfig}'s matchers, and someone
 * appending a new broad rule near the top would silently collapse several distinct messages into
 * one without breaking anything else.
 */
class CapabilityCatalogTest {

    /**
     * Builds the request the security filter chain would hand the access-denied handler.
     *
     * @param method the HTTP method
     * @param path   the request URI
     * @return a mock request carrying just those two
     */
    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        return request;
    }

    @ParameterizedTest(name = "{0} {1} → \"{2}\"")
    @CsvSource({
            // Administrative surfaces. The first three prove that method-specific rules beat the
            // broader /admin/user rule sitting below them.
            "PATCH,  /admin/user/12/role,           capability.assignRoles",
            "PATCH,  /admin/user/12/settings,       capability.changeAccountState",
            "PATCH,  /admin/user/12/update,         capability.editOtherUsersProfiles",
            "PATCH,  /admin/security/anomaly-settings, capability.changeSecuritySettings",
            "GET,    /admin/security/anomaly-settings, capability.viewSecurityMonitoring",
            "GET,    /admin/security/overview,      capability.viewSecurityMonitoring",
            "GET,    /admin/analytics/summary,      capability.viewBillingAnalytics",
            "POST,   /admin/services/create,        capability.manageServicesCatalog",
            "GET,    /admin/user/list,              capability.manageUsers",
            // Business domain.
            "DELETE, /customer/delete/9,            capability.deleteCustomers",
            "DELETE, /user/delete/9,                capability.deleteUsers",
            "PATCH,  /customer/invoice/update/4,    capability.editInvoices",
            "POST,   /customer/create,              capability.createCustomers",
            "POST,   /customer/invoice/create,      capability.createInvoices",
    })
    @DisplayName("names the capability key behind each protected surface")
    void mapsRequestsToCapabilityKeys(String method, String path, String expected) {
        assertEquals(expected, CapabilityCatalog.actionKeyFor(request(method, path)));
    }

    @Test
    @DisplayName("a specific admin rule wins over the broad one, whatever order they appear in")
    void specificRulesTakePrecedenceOverBroadOnes() {
        // If a broad /admin rule ever drifts above the narrow ones, every administrative refusal
        // collapses into the same vague key and the feature quietly stops working — without any
        // test failing unless one asserts the distinction directly.
        String role = CapabilityCatalog.actionKeyFor(request("PATCH", "/admin/user/12/role"));
        String users = CapabilityCatalog.actionKeyFor(request("GET", "/admin/user/list"));
        String generic = CapabilityCatalog.actionKeyFor(request("GET", "/admin/something-else"));

        assertEquals("capability.assignRoles", role);
        assertEquals("capability.manageUsers", users);
        assertEquals("capability.accessAdministrativeFeatures", generic);
    }

    @Test
    @DisplayName("falls back to the default key rather than guessing at an unmapped path")
    void unmappedPathsFallBack() {
        assertEquals(CapabilityCatalog.DEFAULT_ACTION_KEY,
                CapabilityCatalog.actionKeyFor(request("GET", "/something/unmapped")));
        // A null request is not expected, but this runs on an error path — where the one outcome
        // that must never happen is a second exception.
        assertEquals(CapabilityCatalog.DEFAULT_ACTION_KEY, CapabilityCatalog.actionKeyFor(null));
    }

    @Test
    @DisplayName("a prefix rule does not match a merely similar path")
    void prefixMatchingRespectsSegmentBoundaries() {
        // "/admin" must not match "/administration-console": prefix matching that ignores segment
        // boundaries would attach admin phrasing to unrelated endpoints.
        assertEquals(CapabilityCatalog.DEFAULT_ACTION_KEY,
                CapabilityCatalog.actionKeyFor(request("GET", "/administration-console")));
    }
}
