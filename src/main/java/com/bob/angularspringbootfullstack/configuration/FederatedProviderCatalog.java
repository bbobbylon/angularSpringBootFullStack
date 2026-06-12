package com.bob.angularspringbootfullstack.configuration;

import java.util.List;

/**
 * Immutable record of which federated identity providers are actually configured
 * (i.e., have real client credentials supplied through the environment, per EIR-SW-5).
 *
 * <p>Built once at startup by {@link OAuth2ClientConfig} and consumed by
 * {@link com.bob.angularspringbootfullstack.controller.FederatedAuthController}, whose
 * public {@code GET /oauth2/providers} endpoint lets the Angular login screen render a
 * button only for providers that will genuinely complete the flow. The placeholder
 * registration that {@link OAuth2ClientConfig} adds when nothing is configured (to keep
 * Spring Security's OAuth2 machinery bootable) is deliberately absent from this list.
 */
public class FederatedProviderCatalog {

    /** Registration ids (e.g. {@code google}, {@code github}, {@code microsoft}) with real credentials. */
    private final List<String> providers;

    /**
     * @param providers the registration ids that carry real client credentials
     */
    public FederatedProviderCatalog(List<String> providers) {
        this.providers = List.copyOf(providers);
    }

    /**
     * @return an immutable list of configured provider registration ids; empty when
     *         federated login is not configured in this environment
     */
    public List<String> getProviders() {
        return providers;
    }
}
