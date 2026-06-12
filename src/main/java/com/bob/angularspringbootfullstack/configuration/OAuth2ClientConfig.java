package com.bob.angularspringbootfullstack.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Programmatic OAuth2 client registrations for federated login (SRS §4.3, EIR-SW-1,
 * CON-5).
 *
 * <p>Registrations are built in code rather than YAML so that each provider activates
 * only when its credentials are present in the environment (EIR-SW-5 / CON-7): a
 * deployment with just {@code GITHUB_CLIENT_ID}/{@code GITHUB_CLIENT_SECRET} set gets a
 * working GitHub button and nothing else. Google and GitHub reuse Spring Security's
 * {@link CommonOAuth2Provider} endpoint presets; Microsoft (Azure AD v2) has no preset,
 * so its endpoints are declared explicitly against the configurable tenant.
 *
 * <p>When no provider is configured at all, a single non-functional placeholder
 * registration is installed instead. Spring Security's OAuth2 login machinery requires a
 * non-empty {@link ClientRegistrationRepository} to bootstrap; the placeholder keeps the
 * application bootable while {@link FederatedProviderCatalog} (which excludes it) keeps
 * the login screen free of dead buttons.
 *
 * <p>The redirect URI template {@code {baseUrl}/login/oauth2/code/{registrationId}} is
 * Spring's default callback shape — register exactly that URL (e.g.
 * {@code http://localhost:8080/login/oauth2/code/github}) in each provider's developer
 * console.
 */
@Configuration
@Slf4j
public class OAuth2ClientConfig {

    /** Registration id under which the boot-only placeholder is stored; never shown to users. */
    private static final String PLACEHOLDER_REGISTRATION_ID = "placeholder";

    @Value("${oauth2.google.client-id:${GOOGLE_CLIENT_ID:}}")
    private String googleClientId;
    @Value("${oauth2.google.client-secret:${GOOGLE_CLIENT_SECRET:}}")
    private String googleClientSecret;

    @Value("${oauth2.github.client-id:${GITHUB_CLIENT_ID:}}")
    private String githubClientId;
    @Value("${oauth2.github.client-secret:${GITHUB_CLIENT_SECRET:}}")
    private String githubClientSecret;

    @Value("${oauth2.microsoft.client-id:${MICROSOFT_CLIENT_ID:}}")
    private String microsoftClientId;
    @Value("${oauth2.microsoft.client-secret:${MICROSOFT_CLIENT_SECRET:}}")
    private String microsoftClientSecret;
    /** Azure AD tenant; "common" accepts both work/school and personal Microsoft accounts. */
    @Value("${oauth2.microsoft.tenant:${MICROSOFT_TENANT_ID:common}}")
    private String microsoftTenant;

    /**
     * Lists the providers whose credentials are actually present, in the order the
     * login screen should render them. Consumed by the public
     * {@code GET /oauth2/providers} endpoint.
     *
     * @return the catalog of genuinely configured providers (placeholder excluded)
     */
    @Bean
    public FederatedProviderCatalog federatedProviderCatalog() {
        List<String> providers = new ArrayList<>();
        if (isNotBlank(googleClientId)) providers.add("google");
        if (isNotBlank(githubClientId)) providers.add("github");
        if (isNotBlank(microsoftClientId)) providers.add("microsoft");
        log.info("Federated login providers configured: {}", providers.isEmpty() ? "none" : providers);
        return new FederatedProviderCatalog(providers);
    }

    /**
     * Builds the registration repository from whichever providers are configured,
     * falling back to the boot-only placeholder when none are.
     *
     * @param catalog the configured-provider catalog from {@link #federatedProviderCatalog()}
     * @return the repository backing Spring Security's OAuth2 login filters
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(FederatedProviderCatalog catalog) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (catalog.getProviders().contains("google")) {
            registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .build());
        }
        if (catalog.getProviders().contains("github")) {
            registrations.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(githubClientId)
                    .clientSecret(githubClientSecret)
                    // read:user is the preset; user:email additionally exposes the verified
                    // primary email so find-or-create can converge accounts by address.
                    .scope("read:user", "user:email")
                    .build());
        }
        if (catalog.getProviders().contains("microsoft")) {
            registrations.add(microsoftRegistration());
        }
        if (registrations.isEmpty()) {
            registrations.add(placeholderRegistration());
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    /**
     * Azure AD v2 (Microsoft identity platform) registration built against the
     * configured tenant. Uses OIDC scopes so the id token carries {@code sub} and
     * {@code email} claims, and {@code client_secret_post} authentication as Azure's
     * v2 token endpoint expects form-encoded client credentials.
     */
    private ClientRegistration microsoftRegistration() {
        String base = "https://login.microsoftonline.com/" + microsoftTenant;
        return ClientRegistration.withRegistrationId("microsoft")
                .clientId(microsoftClientId)
                .clientSecret(microsoftClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(base + "/oauth2/v2.0/authorize")
                .tokenUri(base + "/oauth2/v2.0/token")
                .jwkSetUri(base + "/discovery/v2.0/keys")
                .userInfoUri("https://graph.microsoft.com/oidc/userinfo")
                .userNameAttributeName("sub")
                .clientName("Microsoft")
                .build();
    }

    /**
     * A syntactically complete but non-functional registration used only so the
     * OAuth2 login filter chain can bootstrap when no real provider is configured.
     * It is excluded from {@link FederatedProviderCatalog}, so no UI ever offers it;
     * navigating to it manually fails at Google with an invalid-client error,
     * disclosing nothing about this system.
     */
    private ClientRegistration placeholderRegistration() {
        return CommonOAuth2Provider.GOOGLE.getBuilder(PLACEHOLDER_REGISTRATION_ID)
                .clientId("not-configured")
                .clientSecret("not-configured")
                .build();
    }
}
