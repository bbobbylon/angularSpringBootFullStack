package com.bob.angularspringbootfullstack.constants;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Translates a forbidden request into the {@code MessageSource} key for the capability the
 * caller was missing (ROADMAP §2 — "Contact your admin to do X", API level).
 *
 * <h3>The problem this solves</h3>
 * A 403 from this API said the same thing for every endpoint: <em>"You don't have enough
 * permission to access this resource!"</em>. That is true and useless. A user who cannot save a
 * customer, a user who cannot reassign a role, and a user who cannot open the security dashboard
 * were told the same sentence, so none of them learned what to ask an administrator for — and the
 * SPA, which forwards {@code error.error.reason} straight to a toast, could only repeat it.
 *
 * <h3>Why the mapping lives here rather than at each endpoint</h3>
 * The 403 is produced by {@code CustomAccessDeniedHandler} in the security filter chain, which
 * runs <em>before</em> any controller method is selected. There is no handler method to ask, so
 * the capability has to be derived from what the filter chain does have: the request's method and
 * path. Keeping that derivation in one table means the phrasing cannot drift between endpoints,
 * and it can be read alongside {@code SecurityConfig}'s matchers — which is the point, since the
 * two must describe the same rules to stay truthful.
 *
 * <h3>Backend-driven i18n (FUTURE-ENHANCEMENTS.md §3.3)</h3>
 * This class only resolves a request to a message <b>key</b> — {@code capability.assignRoles},
 * {@code capability.deleteCustomers}, and so on, backed by {@code messages*.properties} — rather
 * than a finished English sentence. It is a plain static utility with no Spring bean lifecycle,
 * so it cannot itself hold a {@code MessageSource}; resolving a key to text in the caller's
 * language is {@code CustomAccessDeniedHandler}'s job, since that class is a {@code @Component}
 * and can inject one. Keeping the key table here and the resolution there means the request →
 * capability mapping (the part that must stay truthful against {@code SecurityConfig}) is
 * separate from the capability → text mapping (the part that varies by locale).
 *
 * <h3>Non-enumeration</h3>
 * Every phrase names a <b>capability</b> and nothing else. None reveals whether a particular
 * record, account, or organization exists, which matters because a 403 is returned for
 * out-of-scope resources as well as unauthorized ones — an attacker must not be able to tell
 * "this exists but is not yours" from "you may not do this at all". The English phrases are also
 * identical to the {@code deniedAction} strings the SPA's route guards use, so a user who meets
 * the same restriction at the route level and at the API level reads one consistent sentence
 * rather than two different ones — that route-guard text is static English and not part of this
 * i18n pass, since it never leaves the client and is not "server-generated" in the sense the
 * roadmap item targets.
 */
public final class CapabilityCatalog {

    private CapabilityCatalog() {
    }

    /** Message key used when no rule matches — deliberately vague rather than guessing wrongly. */
    public static final String DEFAULT_ACTION_KEY = "capability.default";

    /**
     * The message key for the sentence template shared by this class and the SPA's
     * {@code adminGuard} / {@code capabilityGuard}. Its {@code messages*.properties} value uses
     * {@code MessageFormat}'s {@code {0}} placeholder (not {@code String#format}'s {@code %s}),
     * since it is resolved via {@code MessageSource#getMessage(key, args, locale)}.
     */
    public static final String MESSAGE_TEMPLATE_KEY = "capability.messageTemplate";

    /**
     * One path/method rule. Ordered most specific first, exactly like {@code SecurityConfig}'s
     * request matchers — and for the same reason: a broad {@code /admin/**} rule placed above the
     * narrow ones would swallow every administrative case and report them all as "manage users".
     *
     * @param method      the HTTP method this rule applies to, or null for any
     * @param pathPattern a path prefix, or a pattern containing {@code *} for one path segment
     * @param actionKey   the {@code messages*.properties} key naming the missing capability
     */
    private record Rule(String method, String pathPattern, String actionKey) {
    }

    private static final List<Rule> RULES = List.of(
            // Administrative surfaces — narrowest first.
            new Rule("PATCH", "/admin/user/*/role", "capability.assignRoles"),
            new Rule("PATCH", "/admin/user/*/settings", "capability.changeAccountState"),
            new Rule("PATCH", "/admin/user/*/update", "capability.editOtherUsersProfiles"),
            new Rule("PATCH", "/admin/security/anomaly-settings", "capability.changeSecuritySettings"),
            new Rule(null, "/admin/security", "capability.viewSecurityMonitoring"),
            new Rule(null, "/admin/analytics", "capability.viewBillingAnalytics"),
            new Rule(null, "/admin/services", "capability.manageServicesCatalog"),
            new Rule(null, "/admin/user", "capability.manageUsers"),
            new Rule(null, "/admin", "capability.accessAdministrativeFeatures"),

            // Business domain.
            new Rule("DELETE", "/customer/delete", "capability.deleteCustomers"),
            new Rule("DELETE", "/user/delete", "capability.deleteUsers"),
            new Rule("PATCH", "/customer/invoice/update", "capability.editInvoices"),
            new Rule("POST", "/customer/invoice/*/email", "capability.emailInvoices"),
            new Rule("PUT", "/customer/invoice", "capability.editInvoices"),
            new Rule("POST", "/customer/invoice", "capability.createInvoices"),
            new Rule("POST", "/customer/create", "capability.createCustomers"),
            new Rule("POST", "/customer/update", "capability.updateCustomers"),
            new Rule(null, "/customer/invoice", "capability.workWithInvoices"),
            new Rule(null, "/customer", "capability.workWithCustomers")
    );

    /**
     * Returns the {@code messages*.properties} key for the capability a forbidden request was
     * missing.
     *
     * @param request the request that was refused
     * @return a key such as {@code "capability.assignRoles"}, or {@link #DEFAULT_ACTION_KEY}
     */
    public static String actionKeyFor(HttpServletRequest request) {
        if (request == null) return DEFAULT_ACTION_KEY;

        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path == null) return DEFAULT_ACTION_KEY;

        for (Rule rule : RULES) {
            if (rule.method() != null && !rule.method().equalsIgnoreCase(method)) continue;
            if (matches(path, rule.pathPattern())) return rule.actionKey();
        }
        return DEFAULT_ACTION_KEY;
    }

    /**
     * Prefix match with support for a single-segment {@code *} wildcard.
     *
     * <p>Hand-rolled rather than delegating to {@code AntPathMatcher} because the patterns here
     * are a fixed, tiny set and this runs inside an error path in the filter chain — code on an
     * error path should be as close to incapable of failing as possible, and a five-line matcher
     * over a constant table has no interesting failure modes.
     *
     * @param path    the request URI
     * @param pattern the rule's pattern
     * @return true when the path matches the pattern
     */
    private static boolean matches(String path, String pattern) {
        if (!pattern.contains("*")) {
            return path.equals(pattern) || path.startsWith(pattern + "/") || path.startsWith(pattern + "?");
        }
        String[] patternSegments = pattern.split("/");
        String[] pathSegments = path.split("/");
        if (pathSegments.length < patternSegments.length) return false;

        for (int i = 0; i < patternSegments.length; i++) {
            if ("*".equals(patternSegments[i])) continue;
            if (!patternSegments[i].equals(pathSegments[i])) return false;
        }
        return true;
    }
}
