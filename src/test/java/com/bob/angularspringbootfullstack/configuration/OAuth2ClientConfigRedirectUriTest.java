package com.bob.angularspringbootfullstack.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the redirect-URI template that every federated provider is registered with.
 *
 * <p><b>Why this needs a test at all.</b> Spring's default template is
 * {@code {baseUrl}/login/oauth2/code/{registrationId}}, where {@code baseUrl} is reconstructed from
 * the incoming request — scheme included, taken from {@code X-Forwarded-Proto}. In the deployed
 * topology that header cannot be trusted: CloudFront sets it to {@code https} and the ALB then
 * overwrites it with its own listener protocol ({@code http}, there being no TLS listener without a
 * domain). The app consequently advertised {@code redirect_uri=http://…} through an HTTPS front
 * door, and both Google and Entra reject any non-{@code https} redirect URI outside
 * {@code localhost}. {@code OAUTH2_REDIRECT_BASE_URL} pins the origin so the app stops depending on
 * a header it does not control.
 *
 * <p><b>What actually gets asserted, and why it is not the obvious thing.</b> The interesting
 * property is not that the pinning works for one provider — it is that it applies to
 * <em>all three</em>. Before this change Microsoft spelled the template out by hand while Google and
 * GitHub inherited theirs from {@code CommonOAuth2Provider}, which is the precise shape of bug where
 * someone fixes the provider they happened to be testing and leaves the other two emitting the wrong
 * scheme. {@link Pinned#everyProviderUsesThePinnedOrigin} is therefore parameterized over the
 * registration ids rather than written once against a convenient provider.
 *
 * <p>The failure this prevents is also unusually expensive to diagnose in the wild: a wrong
 * {@code redirect_uri} is rejected at the <em>provider</em>, so the app's own logs show a user who
 * simply never came back, with no error on this side.
 *
 * <p>Fields are populated via {@link ReflectionTestUtils} because they are {@code @Value}-injected
 * and the point of the test is the bean-construction logic, not Spring's property binding — which
 * would require booting a context and give slower, less specific failures.
 */
class OAuth2ClientConfigRedirectUriTest {

    private static final String PINNED_ORIGIN = "https://d3911jyxcju4q4.cloudfront.net";
    private static final String CALLBACK_PATH = "/login/oauth2/code/{registrationId}";

    /**
     * Builds the config with all three providers credentialed and the given redirect origin.
     *
     * @param redirectBaseUrl value for {@code OAUTH2_REDIRECT_BASE_URL}; {@code ""} means unset
     * @return a config ready to produce registrations
     */
    private static OAuth2ClientConfig configWith(String redirectBaseUrl) {
        OAuth2ClientConfig config = new OAuth2ClientConfig();
        ReflectionTestUtils.setField(config, "googleClientId", "google-id");
        ReflectionTestUtils.setField(config, "googleClientSecret", "google-secret");
        ReflectionTestUtils.setField(config, "githubClientId", "github-id");
        ReflectionTestUtils.setField(config, "githubClientSecret", "github-secret");
        ReflectionTestUtils.setField(config, "microsoftClientId", "microsoft-id");
        ReflectionTestUtils.setField(config, "microsoftClientSecret", "microsoft-secret");
        ReflectionTestUtils.setField(config, "microsoftTenant", "common");
        ReflectionTestUtils.setField(config, "redirectBaseUrl", redirectBaseUrl);
        return config;
    }

    /** Runs the two beans in the same order the container does. */
    private static ClientRegistrationRepository registrationsFrom(OAuth2ClientConfig config) {
        return config.clientRegistrationRepository(config.federatedProviderCatalog());
    }

    private static String redirectUriOf(ClientRegistrationRepository repository, String registrationId) {
        ClientRegistration registration = repository.findByRegistrationId(registrationId);
        assertNotNull(registration, "expected a registration for " + registrationId);
        return registration.getRedirectUri();
    }

    @Nested
    @DisplayName("with OAUTH2_REDIRECT_BASE_URL unset (the local-development default)")
    class Unpinned {

        @ParameterizedTest(name = "{0} keeps Spring''s request-derived template")
        @ValueSource(strings = {"google", "github", "microsoft"})
        void everyProviderKeepsTheRequestDerivedTemplate(String registrationId) {
            String redirectUri = redirectUriOf(registrationsFrom(configWith("")), registrationId);

            assertEquals("{baseUrl}" + CALLBACK_PATH, redirectUri,
                    "unset must mean 'behave exactly as Spring does', or local development breaks");
        }
    }

    @Nested
    @DisplayName("with OAUTH2_REDIRECT_BASE_URL pinned to the public origin")
    class Pinned {

        /**
         * The drift guard. Every provider must honour the pinned origin — a single provider left on
         * the request-derived template is the bug this property exists to make impossible.
         */
        @ParameterizedTest(name = "{0} advertises the pinned https origin")
        @ValueSource(strings = {"google", "github", "microsoft"})
        void everyProviderUsesThePinnedOrigin(String registrationId) {
            String redirectUri = redirectUriOf(registrationsFrom(configWith(PINNED_ORIGIN)), registrationId);

            assertEquals(PINNED_ORIGIN + CALLBACK_PATH, redirectUri);
            assertFalse(redirectUri.contains("{baseUrl}"),
                    "a surviving {baseUrl} means this provider still derives the scheme from the "
                            + "untrustworthy X-Forwarded-Proto header");
            assertTrue(redirectUri.startsWith("https://"),
                    "Google and Entra reject any non-https redirect URI outside localhost");
        }

        @Test
        @DisplayName("only the origin is pinned — the callback path stays Spring's own")
        void theCallbackPathIsUnchanged() {
            String redirectUri = redirectUriOf(registrationsFrom(configWith(PINNED_ORIGIN)), "google");

            assertTrue(redirectUri.endsWith(CALLBACK_PATH),
                    "the path is the contract Spring's authorization-code filter listens on; "
                            + "changing it would strand the callback");
        }

        @Test
        @DisplayName("a trailing slash on the configured origin does not produce a double slash")
        void trailingSlashesAreTrimmed() {
            String redirectUri = redirectUriOf(registrationsFrom(configWith(PINNED_ORIGIN + "///")), "github");

            assertEquals(PINNED_ORIGIN + CALLBACK_PATH, redirectUri,
                    "providers match the registered callback URL as a literal string, so "
                            + "'…net//login/oauth2/code/github' would be rejected as unregistered");
        }
    }

    @Nested
    @DisplayName("when no provider is credentialed")
    class NoProviders {

        /**
         * Spring Security's OAuth2 login machinery needs a non-empty repository to bootstrap, so a
         * placeholder registration stands in. It must never be advertised to the login screen.
         */
        @Test
        @DisplayName("the app still boots on a placeholder registration that the UI never offers")
        void placeholderKeepsTheContextBootableWithoutOfferingAButton() {
            OAuth2ClientConfig config = new OAuth2ClientConfig();
            ReflectionTestUtils.setField(config, "googleClientId", "");
            ReflectionTestUtils.setField(config, "githubClientId", "");
            ReflectionTestUtils.setField(config, "microsoftClientId", "");
            ReflectionTestUtils.setField(config, "redirectBaseUrl", "");

            FederatedProviderCatalog catalog = config.federatedProviderCatalog();
            ClientRegistrationRepository repository = config.clientRegistrationRepository(catalog);

            assertTrue(catalog.getProviders().isEmpty(),
                    "an empty catalog is what keeps dead buttons off the login screen");
            assertNotNull(repository.findByRegistrationId("placeholder"),
                    "without it the OAuth2 login filter chain cannot start");
            assertNull(repository.findByRegistrationId("google"));
        }
    }
}
