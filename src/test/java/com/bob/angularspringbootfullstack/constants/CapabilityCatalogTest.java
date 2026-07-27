package com.bob.angularspringbootfullstack.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Specs for {@link CapabilityCatalog} — the mapping that lets a 403 name the capability the caller
 * was missing (ROADMAP §2, API-level permission-denied UX).
 *
 * <p>Two properties matter here, and they pull against each other. The message must be
 * <b>specific</b> enough to tell a user what to ask an administrator for, and <b>generic</b> enough
 * that it never becomes an enumeration channel. The ordering tests protect the first; the
 * non-enumeration tests protect the second.
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
            "PATCH,  /admin/user/12/role,           assign roles",
            "PATCH,  /admin/user/12/settings,       change account state",
            "PATCH,  /admin/user/12/update,         edit other users' profiles",
            "GET,    /admin/security/overview,      view security monitoring",
            "GET,    /admin/analytics/summary,      view billing and analytics",
            "POST,   /admin/services/create,        manage the services catalog",
            "GET,    /admin/user/list,              manage users",
            // Business domain.
            "DELETE, /customer/delete/9,            delete customers",
            "DELETE, /user/delete/9,                delete users",
            "PATCH,  /customer/invoice/update/4,    edit invoices",
            "POST,   /customer/create,              create customers",
            "POST,   /customer/invoice/create,      create invoices",
    })
    @DisplayName("names the capability behind each protected surface")
    void mapsRequestsToCapabilityPhrases(String method, String path, String expected) {
        assertEquals(expected, CapabilityCatalog.actionFor(request(method, path)));
    }

    @Test
    @DisplayName("a specific admin rule wins over the broad one, whatever order they appear in")
    void specificRulesTakePrecedenceOverBroadOnes() {
        // If a broad /admin rule ever drifts above the narrow ones, every administrative refusal
        // collapses into the same vague sentence and the feature quietly stops working — without
        // any test failing unless one asserts the distinction directly.
        String role = CapabilityCatalog.actionFor(request("PATCH", "/admin/user/12/role"));
        String users = CapabilityCatalog.actionFor(request("GET", "/admin/user/list"));
        String generic = CapabilityCatalog.actionFor(request("GET", "/admin/something-else"));

        assertEquals("assign roles", role);
        assertEquals("manage users", users);
        assertEquals("access administrative features", generic);
    }

    @Test
    @DisplayName("falls back to a vague phrase rather than guessing at an unmapped path")
    void unmappedPathsFallBack() {
        assertEquals(CapabilityCatalog.DEFAULT_ACTION,
                CapabilityCatalog.actionFor(request("GET", "/something/unmapped")));
        // A null request is not expected, but this runs on an error path — where the one outcome
        // that must never happen is a second exception.
        assertEquals(CapabilityCatalog.DEFAULT_ACTION, CapabilityCatalog.actionFor(null));
    }

    @Test
    @DisplayName("a prefix rule does not match a merely similar path")
    void prefixMatchingRespectsSegmentBoundaries() {
        // "/admin" must not match "/administration-console": prefix matching that ignores segment
        // boundaries would attach admin phrasing to unrelated endpoints.
        assertEquals(CapabilityCatalog.DEFAULT_ACTION,
                CapabilityCatalog.actionFor(request("GET", "/administration-console")));
    }

    @Test
    @DisplayName("the message never leaks internal vocabulary or record existence")
    void messagesAreNonEnumerating() {
        String[] paths = {
                "/admin/user/12/role", "/admin/security/overview", "/customer/delete/9",
                "/customer/invoice/update/4", "/something/unmapped",
        };

        for (String path : paths) {
            String message = CapabilityCatalog.messageFor(request("GET", path));

            // No authority strings, no role names — those are how the server reasons about the
            // decision, not something the user can act on.
            assertFalse(message.contains("UPDATE:"), "leaked an authority string: " + message);
            assertFalse(message.contains("READ:"), "leaked an authority string: " + message);
            assertFalse(message.contains("DELETE:"), "leaked an authority string: " + message);
            assertFalse(message.toLowerCase().contains("role_"), "leaked a role name: " + message);
            // No identifiers from the path: a 403 covers out-of-scope resources too, so echoing an
            // id back would confirm that the id is real.
            assertFalse(message.matches(".*\\d+.*"), "leaked a record identifier: " + message);
            // And it must still point the user somewhere useful.
            assertTrue(message.contains("contact your administrator"), "lost the remedy: " + message);
        }
    }

    @Test
    @DisplayName("uses the same sentence the SPA's route guards use")
    void messageMatchesTheFrontendPhrasing() {
        // adminGuard and capabilityGuard build exactly this sentence. A user who hits the same
        // restriction at the route level and again at the API level must read one message, not two.
        assertEquals("You don't have permission to assign roles — contact your administrator.",
                CapabilityCatalog.messageFor(request("PATCH", "/admin/user/12/role")));
    }
}
