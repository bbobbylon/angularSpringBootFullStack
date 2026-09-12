package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.OAuthClientCredentials;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.OAuthClientService;
import com.bob.angularspringbootfullstack.service.ServiceAccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of {@link OAuthTokenController} ({@code POST /oauth/token},
 * FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B — RFC 6749 §4.4), driven through the genuine
 * {@code SecurityFilterChain} and rate limiter, same style as
 * {@code AdminServiceAccountControllerSecurityTest}. Exercises the real HTTP contract — form-encoded
 * request, RFC-shaped JSON responses, and that a minted token actually authenticates — rather than
 * mocking {@link OAuthClientService}, since the entire point of this endpoint is standards
 * compliance across the whole stack (route publicity, form binding, response shape), not just the
 * service method in isolation (see {@code OAuthClientServiceImplTest} for that).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OAuthTokenControllerTest {

    /** DemoDataSeeder's ROLE_ADMIN account — includes {@code UPDATE:ROLE}/{@code UPDATE:USER}. */
    private static final String ADMIN_EMAIL = "eve.admin@tessera.dev";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServiceAccountService serviceAccountService;

    @Autowired
    private OAuthClientService oAuthClientService;

    @Autowired
    private UserDetailsService userDetailsService;

    private Long adminId() {
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(ADMIN_EMAIL);
        return principal.getUser().getId();
    }

    private static HttpEntity<MultiValueMap<String, String>> formBody(MultiValueMap<String, String> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new HttpEntity<>(params, headers);
    }

    private static MultiValueMap<String, String> params(String grantType, String clientId, String clientSecret) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (grantType != null) params.add("grant_type", grantType);
        if (clientId != null) params.add("client_id", clientId);
        if (clientSecret != null) params.add("client_secret", clientSecret);
        return params;
    }

    @Test
    @DisplayName("the route is public — an unknown client with no Authorization header at all reaches the controller, not the 401 entry point")
    void routeIsPublicEvenWithNoAuthorizationHeader() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/oauth/token", formBody(params("client_credentials", "unknown", "unknown")), Map.class);

        // If this route were NOT public, a request with no Bearer token would 401 from
        // CustomAuthFilter/the entry point before ever reaching this controller. Getting the
        // controller's OWN invalid_client body instead proves the route is genuinely public.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "invalid_client");
    }

    @Test
    @DisplayName("an unsupported grant_type is rejected before any credential is checked")
    void unsupportedGrantTypeIsRejected() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/oauth/token", formBody(params("password", "whatever", "whatever")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "unsupported_grant_type");
    }

    @Test
    @DisplayName("a missing client_secret is rejected as invalid_request")
    void missingCredentialIsInvalidRequest() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/oauth/token", formBody(params("client_credentials", "only-client-id-present", null)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_request");
    }

    @Test
    @DisplayName("valid client_id/client_secret mints a token that authenticates (403, not 401, on an authority-gated route)")
    void validCredentialsMintUsableAccessToken() {
        UserDTO serviceAccount = serviceAccountService.create("OAuth Token Test " + UUID.randomUUID(), "ROLE_USER", adminId());
        OAuthClientCredentials credentials = oAuthClientService.register(serviceAccount.getId(), "Test client", adminId());

        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(
                "/oauth/token", formBody(params("client_credentials", credentials.getClientId(), credentials.getClientSecret())), Map.class);

        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResponse.getBody())
                .containsEntry("token_type", "Bearer")
                .containsKey("access_token")
                .containsKey("expires_in");

        String accessToken = (String) tokenResponse.getBody().get("access_token");
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        // ROLE_USER lacks UPDATE:USER/UPDATE:ROLE, so a genuinely authenticated request here must
        // 403 (authority check reached) rather than 401 (no principal installed at all) — the same
        // 403-proves-authenticated pattern AdminServiceAccountControllerSecurityTest uses.
        ResponseEntity<String> guarded = restTemplate.exchange(
                "/admin/serviceaccounts", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(guarded.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a revoked client can no longer mint a token even with the correct secret")
    void revokedClientCannotMintToken() {
        UserDTO serviceAccount = serviceAccountService.create("OAuth Revoke Test " + UUID.randomUUID(), "ROLE_USER", adminId());
        OAuthClientCredentials credentials = oAuthClientService.register(serviceAccount.getId(), "Test client", adminId());
        Long clientRowId = oAuthClientService.listForUser(serviceAccount.getId()).getFirst().getId();
        oAuthClientService.revoke(clientRowId);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/oauth/token", formBody(params("client_credentials", credentials.getClientId(), credentials.getClientSecret())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "invalid_client");
    }
}
