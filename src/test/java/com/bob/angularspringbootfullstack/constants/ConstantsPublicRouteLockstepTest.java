package com.bob.angularspringbootfullstack.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the invariant {@link Constants#PUBLIC_URLS}' own Javadoc states but never
 * mechanically checks: every entry must have a corresponding {@link Constants#PUBLIC_ROUTES}
 * prefix, and vice versa.
 *
 * <p><b>Why this matters.</b> {@code PUBLIC_URLS} (consumed by {@code SecurityConfig}'s
 * {@code permitAll()} matchers) and {@code PUBLIC_ROUTES} (consumed by {@code CustomAuthFilter
 * #shouldNotFilter} to decide whether to even attempt JWT parsing) describe the SAME set of
 * unauthenticated routes in two different matcher dialects — {@code /**}-suffixed Ant patterns
 * for one, bare {@code startsWith} prefixes for the other. A route added to only one list is a
 * silent split: added to {@code PUBLIC_URLS} alone, a stale {@code Authorization: Bearer} header
 * makes {@code CustomAuthFilter} attempt to parse it and fail before the request ever reaches
 * the public controller; added to {@code PUBLIC_ROUTES} alone, the filter skips validation but
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()} catch-all then refuses the
 * unauthenticated request anyway. Either way, the route quietly breaks for exactly the callers
 * it exists to serve. {@link SecurityFilterChainIntegrationTest} additionally proves the
 * end-to-end behavior over real HTTP; this class is the fast, DB-free companion that pinpoints
 * WHICH entry drifted the moment it does.
 *
 * <p>Actuator's {@code /actuator/health}/{@code /actuator/info} are deliberately excluded from
 * both lists (permitted directly in {@code SecurityConfig}, ahead of {@code PUBLIC_URLS} — see
 * that matcher's own comment) and are therefore not part of this comparison.
 */
class ConstantsPublicRouteLockstepTest {

    /**
     * {@link Constants#PUBLIC_ROUTES} entries permitted by a route matcher OTHER than {@link
     * Constants#PUBLIC_URLS} — {@code SecurityConfig}'s dedicated {@code /actuator/health},
     * {@code /actuator/health/**}, {@code /actuator/info} {@code permitAll()} matcher, evaluated
     * ahead of {@code PUBLIC_URLS} so it wins before the broader {@code /actuator/**}
     * authority-gated rule ever applies. Deliberate, per both that matcher's comment and {@link
     * Constants#PUBLIC_URLS}' own Javadoc — excluded here, not a lockstep gap.
     */
    private static final Set<String> PUBLIC_ROUTES_WITHOUT_A_PUBLIC_URLS_ENTRY =
            Set.of("/actuator/health", "/actuator/info");

    /**
     * Strips a trailing {@code /**} Ant-pattern suffix, the only wildcard {@link
     * Constants#PUBLIC_URLS} entries use, so a URL pattern and its {@link Constants#PUBLIC_ROUTES}
     * prefix compare as the same string.
     */
    private static String stripWildcard(String urlPattern) {
        return urlPattern.endsWith("/**") ? urlPattern.substring(0, urlPattern.length() - 3) : urlPattern;
    }

    @Test
    @DisplayName("every PUBLIC_URLS entry has a matching PUBLIC_ROUTES prefix")
    void everyPublicUrlHasAPublicRoute() {
        List<String> routes = Arrays.asList(Constants.PUBLIC_ROUTES);

        for (String urlPattern : Constants.PUBLIC_URLS) {
            String prefix = stripWildcard(urlPattern);
            assertThat(routes)
                    .as("PUBLIC_URLS entry '%s' (stripped: '%s') must have a corresponding PUBLIC_ROUTES prefix, " +
                        "or CustomAuthFilter will attempt to parse a stale Bearer token on this public route", urlPattern, prefix)
                    .contains(prefix);
        }
    }

    @Test
    @DisplayName("every PUBLIC_ROUTES prefix has a matching PUBLIC_URLS entry")
    void everyPublicRouteHasAPublicUrl() {
        List<String> withWildcard = Arrays.stream(Constants.PUBLIC_URLS).map(ConstantsPublicRouteLockstepTest::stripWildcard).toList();

        for (String route : Constants.PUBLIC_ROUTES) {
            if (PUBLIC_ROUTES_WITHOUT_A_PUBLIC_URLS_ENTRY.contains(route)) {
                continue;
            }
            assertThat(withWildcard)
                    .as("PUBLIC_ROUTES prefix '%s' must have a corresponding PUBLIC_URLS entry ('%s' or '%s/**'), " +
                        "or SecurityConfig's anyRequest().authenticated() catch-all will refuse this unauthenticated route " +
                        "even though CustomAuthFilter correctly skipped JWT parsing for it", route, route, route)
                    .contains(route);
        }
    }
}
