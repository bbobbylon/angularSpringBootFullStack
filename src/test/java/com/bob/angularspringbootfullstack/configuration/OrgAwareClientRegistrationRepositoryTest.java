package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.event.OrgSsoConfigChangedEvent;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService.DecryptedOidcCredentials;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@link OrgAwareClientRegistrationRepository} — the dynamic
 * {@link ClientRegistrationRepository} that resolves per-organization OIDC logins
 * (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 2).
 *
 * <p>Covers the three fail-closed paths that matter most (unknown provider id falls through to the
 * static delegate, a malformed {@code org-oidc-*} id, and an organization with no active OIDC
 * configuration) plus the caching contract, without needing a live IdP: the one path that would
 * require a real HTTP discovery call ({@code ClientRegistrations.fromIssuerLocation}) is exercised
 * with that static method mocked, since Mockito's default inline mock maker (bundled from Mockito 5)
 * supports mocking static methods without an extra dependency.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgAwareClientRegistrationRepositoryTest {

    @Mock
    private ClientRegistrationRepository staticDelegate;
    @Mock
    private OrganizationIdentityProviderService organizationIdentityProviderService;

    private OrgAwareClientRegistrationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OrgAwareClientRegistrationRepository(
                staticDelegate, organizationIdentityProviderService, new OAuth2ClientConfig());
    }

    @Test
    @DisplayName("a non-org-oidc id is delegated to the static repository untouched")
    void delegatesKnownProviderIds() {
        ClientRegistration google = googleStyleRegistration("google");
        when(staticDelegate.findByRegistrationId("google")).thenReturn(google);

        ClientRegistration result = repository.findByRegistrationId("google");

        assertThat(result).isSameAs(google);
    }

    @Test
    @DisplayName("a malformed org-oidc id (non-numeric suffix) fails closed to null")
    void rejectsMalformedOrgOidcId() {
        ClientRegistration result = repository.findByRegistrationId("org-oidc-not-a-number");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("an organization with no active OIDC configuration fails closed to null")
    void failsClosedWhenNoActiveCredentials() {
        when(organizationIdentityProviderService.resolveActiveOidcCredentials(42L)).thenReturn(Optional.empty());

        ClientRegistration result = repository.findByRegistrationId("org-oidc-42");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("a discovery failure (unreachable issuer, malformed metadata) fails closed to null")
    void failsClosedWhenDiscoveryThrows() {
        when(organizationIdentityProviderService.resolveActiveOidcCredentials(42L))
                .thenReturn(Optional.of(new DecryptedOidcCredentials(
                        "https://unreachable.example.com", "client-id", "secret", "Acme Okta")));

        try (MockedStatic<ClientRegistrations> mocked = mockStatic(ClientRegistrations.class)) {
            mocked.when(() -> ClientRegistrations.fromIssuerLocation(anyString()))
                    .thenThrow(new IllegalArgumentException("discovery failed"));

            ClientRegistration result = repository.findByRegistrationId("org-oidc-42");

            assertThat(result).isNull();
        }
    }

    @Test
    @DisplayName("a resolved org-oidc registration is built from decrypted credentials and cached")
    void buildsAndCachesOrgRegistration() {
        when(organizationIdentityProviderService.resolveActiveOidcCredentials(42L))
                .thenReturn(Optional.of(new DecryptedOidcCredentials(
                        "https://acme.okta.com", "client-id", "s3cret", "Acme Okta")));

        try (MockedStatic<ClientRegistrations> mocked = mockStatic(ClientRegistrations.class)) {
            mocked.when(() -> ClientRegistrations.fromIssuerLocation(anyString()))
                    .thenReturn(discoveredBuilder());

            ClientRegistration first = repository.findByRegistrationId("org-oidc-42");
            ClientRegistration second = repository.findByRegistrationId("org-oidc-42");

            assertThat(first).isNotNull();
            assertThat(first.getRegistrationId()).isEqualTo("org-oidc-42");
            assertThat(first.getClientId()).isEqualTo("client-id");
            assertThat(first.getClientName()).isEqualTo("Acme Okta");
            // The second lookup must come from the cache, not a second discovery call.
            assertThat(second).isSameAs(first);
            mocked.verify(() -> ClientRegistrations.fromIssuerLocation(anyString()));
        }
    }

    @Test
    @DisplayName("OrgSsoConfigChangedEvent evicts exactly that organization's cache entry")
    void eventEvictsOnlyItsOwnOrganization() {
        seedCache("org-oidc-42", googleStyleRegistration("org-oidc-42"));
        seedCache("org-oidc-99", googleStyleRegistration("org-oidc-99"));

        repository.onOrgSsoConfigChanged(new OrgSsoConfigChangedEvent(42L));

        assertThat(cache().getIfPresent("org-oidc-42")).isNull();
        assertThat(cache().getIfPresent("org-oidc-99")).isNotNull();
    }

    @Test
    @DisplayName("an unresolvable google/github/microsoft id never reaches organization lookup")
    void staticProviderIdsNeverConsultTheOrganizationService() {
        when(staticDelegate.findByRegistrationId("github")).thenReturn(null);

        ClientRegistration result = repository.findByRegistrationId("github");

        assertThat(result).isNull();
        verify(organizationIdentityProviderService, never()).resolveActiveOidcCredentials(any());
    }

    @SuppressWarnings("unchecked")
    private Cache<String, ClientRegistration> cache() {
        return (Cache<String, ClientRegistration>) ReflectionTestUtils.getField(repository, "cache");
    }

    private void seedCache(String registrationId, ClientRegistration registration) {
        cache().put(registrationId, registration);
    }

    private static ClientRegistration.Builder discoveredBuilder() {
        return ClientRegistration.withRegistrationId("placeholder")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://acme.okta.com/oauth2/v1/authorize")
                .tokenUri("https://acme.okta.com/oauth2/v1/token");
    }

    private static ClientRegistration googleStyleRegistration(String registrationId) {
        return ClientRegistration.withRegistrationId(registrationId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .clientId("client-id")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();
    }
}
