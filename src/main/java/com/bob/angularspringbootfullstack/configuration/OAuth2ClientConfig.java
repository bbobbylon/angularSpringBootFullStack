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
     * Absolute public origin to build OAuth redirect URIs from, e.g.
     * {@code https://d3911jyxcju4q4.cloudfront.net}. Blank (the default) keeps the standard
     * request-derived behaviour, which is correct for local development.
     *
     * <p><b>Why this exists.</b> Spring's default template is
     * {@code {baseUrl}/login/oauth2/code/{registrationId}}, and {@code baseUrl} is reconstructed
     * from the incoming request — scheme included. That scheme comes from {@code X-Forwarded-Proto}.
     * In the CloudFront-in-front-of-ALB deployment that header is <em>wrong</em>: CloudFront sets it
     * to {@code https}, but the ALB then <b>overwrites</b> it with its own listener protocol
     * ({@code http}, since the ALB has no TLS listener without a domain). The app therefore emits
     * {@code redirect_uri=http://…} — which Google and Entra reject outright, because both refuse
     * any non-{@code https} redirect URI outside {@code localhost}. Verified empirically against the
     * live distribution before this property was added.
     *
     * <p>Setting an explicit origin removes the dependency on a header the app does not control.
     * Only the <em>scheme and host</em> come from here; the path is still Spring's, so the
     * callback contract is unchanged.
     *
     * <p>Deliberately its own variable rather than reusing {@code UI_APP_URL}: they happen to be
     * equal in the single-origin deployment, but they answer different questions ("where does the
     * SPA live" vs "what public address will the provider redirect back to"), and silently coupling
     * them would break the first split-origin deployment.
     */
    @Value("${oauth2.redirect-base-url:${OAUTH2_REDIRECT_BASE_URL:}}")
    private String redirectBaseUrl;

    /**
     * The redirect-URI template handed to every provider registration.
     *
     * <p>Returns Spring's request-derived default unless {@link #redirectBaseUrl} is set, in which
     * case the origin is pinned. Applied to all three providers so they cannot drift — Microsoft
     * previously spelled the template out by hand while Google and GitHub inherited it from
     * {@code CommonOAuth2Provider}, which is exactly the kind of split that lets one provider be
     * quietly fixed and the others left broken.
     *
     * @return the redirect-URI template, with {@code {registrationId}} still unexpanded
     */
    private String redirectUriTemplate() {
        String path = "/login/oauth2/code/{registrationId}";
        return isNotBlank(redirectBaseUrl)
                ? redirectBaseUrl.replaceAll("/+$", "") + path
                : "{baseUrl}" + path;
    }

    /**
     * Lists the providers whose credentials are actually present, in the order the
     * login screen should render them. Consumed by the public
     * {@code GET /oauth2/providers} endpoint.
     *
     * <p><b>The test is blankness, not plausibility.</b> A provider is included whenever its client
     * id is non-blank, so a <em>placeholder</em> value such as {@code CHANGE_ME} still produces a
     * rendered button — the flow then fails at the provider's authorize endpoint with
     * {@code client_id=CHANGE_ME} rather than being hidden here. This is not hypothetical: it is why
     * GitHub sign-in was long documented as "working in the deployed environment" when its ECS
     * credentials had never been populated. If that failure mode is worth closing, widen this check
     * to treat known placeholder values as unconfigured; it is deliberately left alone for now
     * because silently hiding a provider can be just as confusing as a failing one.
     *
     * @return the catalog of providers with a non-blank client id
     */
    @Bean
    public FederatedProviderCatalog federatedProviderCatalog() {
        List<String> providers = new ArrayList<>();
        if (isNotBlank(googleClientId)) providers.add("google");
        if (isNotBlank(githubClientId)) providers.add("github");
        if (isNotBlank(microsoftClientId)) providers.add("microsoft");
        log.info("Federated login providers configured: {}", providers.isEmpty() ? "none" : providers);
        warnIfPlaceholder("google", googleClientId);
        warnIfPlaceholder("github", githubClientId);
        warnIfPlaceholder("microsoft", microsoftClientId);
        return new FederatedProviderCatalog(providers);
    }

    /**
     * Logs a boot-time warning when a configured provider's client id still looks like the
     * {@code CHANGE_ME} placeholder every deploy script seeds Secrets Manager with. Deliberately a
     * warning, not a fail-fast guard like {@code JwtSecretGuard}: federated login is an optional
     * feature, and refusing to boot over it would take down the rest of the application for
     * something that previously only surfaced as a confusing "invalid_client" error page at the
     * provider — exactly the failure this warning exists to make loud earlier instead.
     *
     * <p>The check is a prefix match, not exact equality — {@code secrets-setup.sh} seeds slightly
     * different placeholder strings per credential (e.g. {@code CHANGE_ME_google_secret}), so this
     * catches all of them without needing to enumerate every script's exact literal.
     *
     * @param provider the registration id, for the log message
     * @param clientId the configured client id, or blank if this provider is not configured at all
     */
    private void warnIfPlaceholder(String provider, String clientId) {
        if (isNotBlank(clientId) && clientId.toUpperCase().startsWith("CHANGE_ME")) {
            log.warn("[FEDERATION] {} client-id looks like a placeholder ('CHANGE_ME...') — the {} " +
                    "login button will render but fail at {}'s authorize endpoint until real credentials are set.",
                    provider, provider, provider);
        }
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
                    .redirectUri(redirectUriTemplate())
                    .build());
        }
        if (catalog.getProviders().contains("github")) {
            registrations.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(githubClientId)
                    .clientSecret(githubClientSecret)
                    // read:user is the preset; user:email additionally exposes the verified
                    // primary email so find-or-create can converge accounts by address.
                    .scope("read:user", "user:email")
                    .redirectUri(redirectUriTemplate())
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
                .redirectUri(redirectUriTemplate())
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
