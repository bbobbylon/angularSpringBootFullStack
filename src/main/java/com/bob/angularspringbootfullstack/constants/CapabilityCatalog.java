package com.bob.angularspringbootfullstack.constants;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Translates a forbidden request into the name of the capability the caller was missing
 * (ROADMAP §2 — "Contact your admin to do X", API level).
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
 * <h3>Non-enumeration</h3>
 * Every phrase names a <b>capability</b> and nothing else. None reveals whether a particular
 * record, account, or organization exists, which matters because a 403 is returned for
 * out-of-scope resources as well as unauthorized ones — an attacker must not be able to tell
 * "this exists but is not yours" from "you may not do this at all". The phrases are also identical
 * to the {@code deniedAction} strings the SPA's route guards use, so a user who meets the same
 * restriction at the route level and at the API level reads one consistent sentence rather than
 * two different ones.
 */
public final class CapabilityCatalog {

    private CapabilityCatalog() {
    }

    /** Used when no rule matches — deliberately vague rather than guessing wrongly. */
    public static final String DEFAULT_ACTION = "perform this action";

    /**
     * The sentence template shared by this class and the SPA's {@code adminGuard} /
     * {@code capabilityGuard}. Changing it here without changing it there produces two subtly
     * different messages for the same refusal.
     */
    public static final String MESSAGE_TEMPLATE = "You don't have permission to %s — contact your administrator.";

    /**
     * One path/method rule. Ordered most specific first, exactly like {@code SecurityConfig}'s
     * request matchers — and for the same reason: a broad {@code /admin/**} rule placed above the
     * narrow ones would swallow every administrative case and report them all as "manage users".
     *
     * @param method      the HTTP method this rule applies to, or null for any
     * @param pathPattern a path prefix, or a pattern containing {@code *} for one path segment
     * @param action      the verb phrase to slot into {@link #MESSAGE_TEMPLATE}
     */
    private record Rule(String method, String pathPattern, String action) {
    }

    private static final List<Rule> RULES = List.of(
            // Administrative surfaces — narrowest first.
            new Rule("PATCH", "/admin/user/*/role", "assign roles"),
            new Rule("PATCH", "/admin/user/*/settings", "change account state"),
            new Rule("PATCH", "/admin/user/*/update", "edit other users' profiles"),
            new Rule(null, "/admin/security", "view security monitoring"),
            new Rule(null, "/admin/analytics", "view billing and analytics"),
            new Rule(null, "/admin/services", "manage the services catalog"),
            new Rule(null, "/admin/user", "manage users"),
            new Rule(null, "/admin", "access administrative features"),

            // Business domain.
            new Rule("DELETE", "/customer/delete", "delete customers"),
            new Rule("DELETE", "/user/delete", "delete users"),
            new Rule("PATCH", "/customer/invoice/update", "edit invoices"),
            new Rule("PUT", "/customer/invoice", "edit invoices"),
            new Rule("POST", "/customer/invoice", "create invoices"),
            new Rule("POST", "/customer/create", "create customers"),
            new Rule("POST", "/customer/update", "update customers"),
            new Rule(null, "/customer/invoice", "work with invoices"),
            new Rule(null, "/customer", "work with customers")
    );

    /**
     * Returns the capability phrase for a forbidden request.
     *
     * @param request the request that was refused
     * @return a verb phrase such as {@code "assign roles"}, or {@link #DEFAULT_ACTION}
     */
    public static String actionFor(HttpServletRequest request) {
        if (request == null) return DEFAULT_ACTION;

        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path == null) return DEFAULT_ACTION;

        for (Rule rule : RULES) {
            if (rule.method() != null && !rule.method().equalsIgnoreCase(method)) continue;
            if (matches(path, rule.pathPattern())) return rule.action();
        }
        return DEFAULT_ACTION;
    }

    /**
     * Builds the complete, client-facing refusal message for a forbidden request.
     *
     * @param request the request that was refused
     * @return the message, naming the capability where one is known
     */
    public static String messageFor(HttpServletRequest request) {
        return MESSAGE_TEMPLATE.formatted(actionFor(request));
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
