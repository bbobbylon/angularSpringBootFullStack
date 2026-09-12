package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.OAuthTokenResponse;
import com.bob.angularspringbootfullstack.service.OAuthClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.constants.Constants.ACCESS_TOKEN_EXPIRE_TIME;

/**
 * The RFC 6749 §4.4 client-credentials token endpoint (FUTURE-ENHANCEMENTS.md §3.1, P2-3
 * Option B) — the standards-compliant sibling of {@code ApiKeyAuthFilter}'s {@code X-API-Key}
 * header (Option A).
 * <p>
 * Deliberately the one endpoint in this application that does <b>not</b> return
 * {@code ResponseEntity<HttpResponse>}: an off-the-shelf OAuth2 client library (Postman, a CI
 * system's OAuth2 client, {@code oauth2-client} in another Spring app) expects exactly the
 * request/response shapes RFC 6749 mandates, and diverging from them would defeat the entire
 * reason to offer this grant type alongside the simpler, already-bespoke {@code X-API-Key} scheme.
 * Request body is {@code application/x-www-form-urlencoded} (client authentication via
 * {@code client_secret_post}, RFC 6749 §2.3.1 — not HTTP Basic, which this endpoint does not
 * accept), and every response — success or error — is built by hand rather than through
 * {@code HandleException}/{@code ApiException}, whose {@code HttpResponse} envelope would break a
 * spec-compliant caller just as badly as a wrong field name would.
 * <p>
 * Public by construction ({@code /oauth/token} is in {@code Constants.PUBLIC_URLS}/
 * {@code PUBLIC_ROUTES} and {@code RateLimitFilter}'s auth tier) — the presented
 * {@code client_id}/{@code client_secret} pair IS the credential, exactly as a bare
 * {@code Authorization} header is for {@code POST /user/login}.
 */
@RestController
@RequestMapping(path = "/oauth")
@RequiredArgsConstructor
public class OAuthTokenController {

    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
    private static final String BEARER = "Bearer";

    private final OAuthClientService oAuthClientService;

    /**
     * Exchanges a {@code client_id}/{@code client_secret} pair for a bearer access token.
     * <p>
     * Every rejection reason — an unsupported grant type, a missing parameter, an unknown
     * client_id, a wrong secret, a revoked client, or a deactivated service account — resolves to
     * one of exactly two RFC 6749 §5.2 error codes ({@code unsupported_grant_type} for the first,
     * {@code invalid_request} for the second, {@code invalid_client} for the rest), never leaking
     * which specific reason applied — same anti-enumeration posture as every other authentication
     * path in this codebase (see {@code OAuthClientService#authenticate}'s Javadoc).
     *
     * @param grantType    must be {@code "client_credentials"} — no other grant type is served here
     * @param clientId     the presented client_id
     * @param clientSecret the presented client_secret
     * @return 200 with an RFC 6749 §5.1 token response on success; 400/401 with an RFC 6749 §5.2
     * error body otherwise
     */
    @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Object> issueToken(@RequestParam(name = "grant_type", required = false) String grantType,
                                              @RequestParam(name = "client_id", required = false) String clientId,
                                              @RequestParam(name = "client_secret", required = false) String clientSecret) {
        if (!GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
            return oauthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "This endpoint serves only grant_type=client_credentials.");
        }
        if (isBlank(clientId) || isBlank(clientSecret)) {
            return oauthError(HttpStatus.BAD_REQUEST, "invalid_request",
                    "client_id and client_secret are both required.");
        }
        Optional<String> accessToken = oAuthClientService.authenticate(clientId, clientSecret);
        if (accessToken.isEmpty()) {
            return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client",
                    "Client authentication failed.");
        }
        OAuthTokenResponse body = OAuthTokenResponse.builder()
                .accessToken(accessToken.get())
                .tokenType(BEARER)
                .expiresIn(ACCESS_TOKEN_EXPIRE_TIME / 1000)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }

    /**
     * Builds an RFC 6749 §5.2 error body — {@code {"error": ..., "error_description": ...}} — at
     * the given status, with the same no-store caching headers the success path sets.
     */
    private ResponseEntity<Object> oauthError(HttpStatus status, String error, String description) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(Map.of("error", error, "error_description", description));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
