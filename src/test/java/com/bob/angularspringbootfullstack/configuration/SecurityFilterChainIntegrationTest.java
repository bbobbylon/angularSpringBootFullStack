package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real HTTP requests through the genuine {@code SecurityFilterChain} bean built by
 * {@link SecurityConfig} — the gap FUTURE-ENHANCEMENTS.md §5 calls out: every other security
 * test in this suite proxies a controller directly ({@link
 * org.springframework.test.web.servlet.setup.MockMvcBuilders#standaloneSetup}, used by e.g.
 * {@code UserControllerLoginEnumerationTest}) or exercises method security in isolation (e.g.
 * {@code AnalyticsControllerSecurityTest}), neither of which ever loads {@link SecurityConfig}.
 * Matcher <b>ordering</b> — the entire reason the top-down, specific-before-broad rules in
 * {@code securityFilterChain} are written in the order they are — therefore had zero automated
 * coverage before this class, and neither did the interaction between it and {@link
 * com.bob.angularspringbootfullstack.filter.CustomAuthFilter}'s {@code PUBLIC_ROUTES} skip list.
 * {@link com.bob.angularspringbootfullstack.constants.ConstantsPublicRouteLockstepTest} is this
 * class's fast, DB-free companion: that one proves the two constant lists agree in principle,
 * this one proves the agreement actually holds over the wire.
 *
 * <p><b>Why tokens are minted directly instead of calling {@code POST /user/login}.</b> The
 * login endpoint additionally runs anomaly/step-up detection ({@code LoginRiskService}, FR-TPF-1),
 * which is a separate, already-covered concern. Routing through it here would make this suite's
 * pass/fail depend on whether the test runner's IP and user agent happen to look "familiar"
 * against a seeded account's login history — exactly the kind of incidental coupling that makes
 * a CI run flaky for reasons unrelated to what it is meant to guard. What this class asserts is
 * narrower and unconditional: given a validly-signed, correctly-scoped JWT, is it honoured (or
 * refused) exactly as {@link SecurityConfig} and {@code CustomAuthFilter} say it should be? How
 * that token was minted does not change the answer, so {@link TokenProvider#createAccessToken}
 * is called directly — the same call {@code SessionService} makes after a real login succeeds.
 *
 * <p><b>Why {@code DemoDataSeeder}'s accounts, not fixtures created in {@code @BeforeEach}.</b>
 * This class boots the full application context on the {@code dev} profile (the default active
 * profile, same as {@code AngularSpringBootFullStackApplicationTests#contextLoads}), which is
 * exactly when {@code DemoDataSeeder} runs. Its accounts are stable, already span every SRS
 * role, and reusing them means this suite needs no database writes of its own — only reads
 * against rows the seeder guarantees exist. It therefore shares {@code contextLoads}' one
 * precondition (a live local MySQL with {@code schema.sql} applied) and no other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Boot 4.0 split TestRestTemplate's autoconfiguration into its own opt-in annotation (it no
// longer activates automatically off RANDOM_PORT alone the way it did pre-4.0) — see pom.xml's
// spring-boot-resttestclient dependency comment for the module split this pairs with.
@AutoConfigureTestRestTemplate
class SecurityFilterChainIntegrationTest {

    /** DemoDataSeeder's ROLE_GUEST account — authority {@code READ:USER} only. */
    private static final String GUEST_EMAIL = "alice.guest@tessera.dev";

    /** DemoDataSeeder's ROLE_ADMIN account — includes {@code UPDATE:ROLE}/{@code UPDATE:USER}. */
    private static final String ADMIN_EMAIL = "eve.admin@tessera.dev";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private UserDetailsService userDetailsService;

    /**
     * Mints a real, validly-signed access token for a seeded demo account.
     *
     * <p>{@link UserDetailsService#loadUserByUsername} is the exact lookup {@code
     * DaoAuthenticationProvider} performs during a real login, so the returned {@link
     * UserPrincipal} — and therefore the token's {@code authorities} claim — carries precisely
     * the account's actual role, with no hand-built stand-in that could drift from what
     * production would issue.
     *
     * @param email a seeded demo account's email
     * @return a signed access token good for the standard 30-minute expiry
     */
    private String accessTokenFor(String email) {
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(email);
        return tokenProvider.createAccessToken(principal, "filter-chain-test-session");
    }

    /**
     * Builds a request entity carrying the given bearer token, or no Authorization header at
     * all when {@code token} is {@code null}.
     */
    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }

    @Test
    @DisplayName("GET /admin/** with no Authorization header at all -> 401")
    void adminRouteWithNoTokenIsUnauthorized() {
        ResponseEntity<String> response =
                restTemplate.exchange("/admin/user/list", HttpMethod.GET, bearer(null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /admin/** with a real token that lacks UPDATE:USER/UPDATE:ROLE -> 403")
    void adminRouteWithInsufficientAuthorityIsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/user/list", HttpMethod.GET, bearer(accessTokenFor(GUEST_EMAIL)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /admin/** with UPDATE:ROLE -> 200 (proves the specific /admin/** matcher, " +
                 "not the broad GET catch-all beneath it, is what is granting access)")
    void adminRouteWithSufficientAuthorityIsAllowed() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/user/list", HttpMethod.GET, bearer(accessTokenFor(ADMIN_EMAIL)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a public route stays reachable even with a stale/garbage Bearer token")
    void publicRouteIgnoresGarbageBearerToken() {
        // The exact failure mode Constants.PUBLIC_ROUTES' Javadoc warns about: a route permitted
        // by SecurityConfig (PUBLIC_URLS) but missing from CustomAuthFilter's skip list
        // (PUBLIC_ROUTES) would let a stale Authorization header reach the token parser and fail
        // BEFORE the request ever reaches this public controller.
        ResponseEntity<String> response = restTemplate.exchange(
                "/services/public", HttpMethod.GET, bearer("garbage-not-a-real-jwt"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a public route is reachable with no Authorization header at all")
    void publicRouteReachableWithNoToken() {
        ResponseEntity<String> response =
                restTemplate.exchange("/services/public", HttpMethod.GET, bearer(null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
