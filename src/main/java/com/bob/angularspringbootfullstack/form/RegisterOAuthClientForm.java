package com.bob.angularspringbootfullstack.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /admin/serviceaccounts/{id}/oauthclients} (FUTURE-ENHANCEMENTS.md
 * §3.1, P2-3 Option B) — registering a new OAuth2 client-credentials pair for a service account.
 */
@Data
public class RegisterOAuthClientForm {

    /**
     * A caller-chosen label for this client, e.g. {@code "CI pipeline"}, shown alongside its
     * {@code clientId} in the admin panel.
     */
    @NotBlank(message = "Name is required")
    private String name;
}
