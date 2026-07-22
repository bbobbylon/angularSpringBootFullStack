package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.configuration.FederatedProviderCatalog;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Public discovery endpoint for federated login (supports EIR-UI-1's "federated login
 * entry points").
 *
 * <p>{@code GET /oauth2/providers} tells the Angular login screen which identity
 * providers are configured in this environment, so it renders a button only for flows
 * that can actually complete. The path lives under the {@code /oauth2/**} public prefix
 * (Constants.PUBLIC_URLS) and does not collide with Spring Security's own
 * {@code /oauth2/authorization/{registrationId}} initiation endpoints.
 *
 * <p>Anti-enumeration note (NFR-SEC-7): this endpoint discloses only deployment
 * configuration (which providers exist), never anything about user accounts.
 */
@RestController
@RequestMapping(path = "/oauth2")
@RequiredArgsConstructor
public class FederatedAuthController {

    private final FederatedProviderCatalog catalog;

    /**
     * Lists the configured federated provider ids in render order.
     *
     * @return 200 OK with {@code providers}: e.g. {@code ["google","github"]}; an empty
     *         list when federated login is not configured in this environment
     */
    @GetMapping("/providers")
    public ResponseEntity<HttpResponse> providers() {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("providers", catalog.getProviders()))
                        .message("Federated providers retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
