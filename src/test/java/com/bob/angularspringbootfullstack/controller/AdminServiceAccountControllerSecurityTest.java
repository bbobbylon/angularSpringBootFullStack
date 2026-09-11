package com.bob.angularspringbootfullstack.controller;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization guard for {@link AdminServiceAccountController} ({@code /admin/serviceaccounts/**},
 * FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A), driven through the genuine {@code SecurityFilterChain}
 * bean rather than a method-security-only slice.
 *
 * <p>Unlike {@code AdminUserController}'s role-reassignment/settings endpoints, nothing under
 * {@code /admin/serviceaccounts/**} carries its own {@code @PreAuthorize} — every route here is
 * gated purely by {@code SecurityConfig}'s broad {@code /admin/**} matcher
 * ({@code UPDATE:USER}/{@code UPDATE:ROLE}), per this feature's design (the tier ceiling on which
 * role a service account may be granted is enforced by {@code RoleType#canAssign} in the service
 * layer, not by which specific authority the caller holds). That makes a full-filter-chain test —
 * mirroring {@code SecurityFilterChainIntegrationTest} — the faithful way to exercise this surface's
 * actual authorization boundary, since there is no method-level annotation to proxy-test in
 * isolation the way {@code SecurityDashboardControllerSecurityTest} does for its controller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminServiceAccountControllerSecurityTest {

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

    /** Mints a real, validly-signed access token for a seeded demo account — see
     * {@code SecurityFilterChainIntegrationTest#accessTokenFor} for why tokens are minted
     * directly rather than via {@code POST /user/login}. */
    private String accessTokenFor(String email) {
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(email);
        return tokenProvider.createAccessToken(principal, "service-account-security-test-session");
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }

    @Test
    @DisplayName("GET /admin/serviceaccounts with no Authorization header at all -> 401")
    void withNoTokenIsUnauthorized() {
        ResponseEntity<String> response =
                restTemplate.exchange("/admin/serviceaccounts", HttpMethod.GET, bearer(null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /admin/serviceaccounts with a real token lacking UPDATE:USER/UPDATE:ROLE -> 403")
    void withInsufficientAuthorityIsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/serviceaccounts", HttpMethod.GET, bearer(accessTokenFor(GUEST_EMAIL)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /admin/serviceaccounts with UPDATE:ROLE -> 200")
    void withSufficientAuthorityIsAllowed() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/admin/serviceaccounts", HttpMethod.GET, bearer(accessTokenFor(ADMIN_EMAIL)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("POST /admin/serviceaccounts with a real token lacking UPDATE:USER/UPDATE:ROLE -> 403")
    void createWithInsufficientAuthorityIsForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessTokenFor(GUEST_EMAIL));
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> body = new HttpEntity<>("{\"name\":\"nope\",\"role\":\"ROLE_USER\"}", headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity("/admin/serviceaccounts", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
