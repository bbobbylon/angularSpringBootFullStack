package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.event.OrgSsoConfigChangedEvent;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService.SamlIdpConfig;
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
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for {@link OrgAwareRelyingPartyRegistrationRepository} — the SAML sibling of
 * {@code OrgAwareClientRegistrationRepository} (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization
 * external IdP", Stage 3).
 *
 * <p>Mirrors {@code OrgAwareClientRegistrationRepositoryTest}'s structure: the one path that would
 * require a live metadata fetch ({@code RelyingPartyRegistrations.fromMetadataLocation}) is exercised
 * with that static method mocked. The returned {@code RelyingPartyRegistration.Builder}/
 * {@code RelyingPartyRegistration} are themselves mocked rather than built for real, since a real
 * build requires asserting-party signing credentials this test has no need to construct — the
 * repository's own logic (id parsing, caching, eviction, fail-closed branches) is what's under test,
 * not Spring Security's SAML metadata parser.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgAwareRelyingPartyRegistrationRepositoryTest {

    @Mock
    private OrganizationIdentityProviderService organizationIdentityProviderService;
    @Mock
    private RelyingPartyRegistration.Builder builder;
    @Mock
    private RelyingPartyRegistration registration;

    private OrgAwareRelyingPartyRegistrationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OrgAwareRelyingPartyRegistrationRepository(organizationIdentityProviderService);
    }

    @Test
    @DisplayName("a non-org-saml id fails closed to null without consulting the organization service")
    void rejectsNonOrgSamlId() {
        RelyingPartyRegistration result = repository.findByRegistrationId("google");

        assertThat(result).isNull();
        verify(organizationIdentityProviderService, never()).resolveActiveSamlConfig(any());
    }

    @Test
    @DisplayName("a null registration id fails closed to null")
    void rejectsNullId() {
        assertThat(repository.findByRegistrationId(null)).isNull();
    }

    @Test
    @DisplayName("a malformed org-saml id (non-numeric suffix) fails closed to null")
    void rejectsMalformedOrgSamlId() {
        RelyingPartyRegistration result = repository.findByRegistrationId("org-saml-not-a-number");

        assertThat(result).isNull();
        verify(organizationIdentityProviderService, never()).resolveActiveSamlConfig(any());
    }

    @Test
    @DisplayName("an organization with no active SAML configuration fails closed to null")
    void failsClosedWhenNoActiveConfig() {
        when(organizationIdentityProviderService.resolveActiveSamlConfig(42L)).thenReturn(Optional.empty());

        RelyingPartyRegistration result = repository.findByRegistrationId("org-saml-42");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("a metadata resolution failure (unreachable location, malformed XML) fails closed to null")
    void failsClosedWhenMetadataResolutionThrows() {
        when(organizationIdentityProviderService.resolveActiveSamlConfig(42L))
                .thenReturn(Optional.of(new SamlIdpConfig("https://unreachable.example.com/metadata", "Acme Okta")));

        try (MockedStatic<RelyingPartyRegistrations> mocked = mockStatic(RelyingPartyRegistrations.class)) {
            mocked.when(() -> RelyingPartyRegistrations.fromMetadataLocation(anyString()))
                    .thenThrow(new IllegalArgumentException("metadata resolution failed"));

            RelyingPartyRegistration result = repository.findByRegistrationId("org-saml-42");

            assertThat(result).isNull();
        }
    }

    @Test
    @DisplayName("a resolved org-saml registration is built from stored metadata and cached")
    void buildsAndCachesOrgRegistration() {
        when(organizationIdentityProviderService.resolveActiveSamlConfig(42L))
                .thenReturn(Optional.of(new SamlIdpConfig("https://acme.okta.com/metadata", "Acme Okta")));
        when(builder.registrationId("org-saml-42")).thenReturn(builder);
        when(builder.build()).thenReturn(registration);

        try (MockedStatic<RelyingPartyRegistrations> mocked = mockStatic(RelyingPartyRegistrations.class)) {
            mocked.when(() -> RelyingPartyRegistrations.fromMetadataLocation(anyString())).thenReturn(builder);

            RelyingPartyRegistration first = repository.findByRegistrationId("org-saml-42");
            RelyingPartyRegistration second = repository.findByRegistrationId("org-saml-42");

            assertThat(first).isSameAs(registration);
            // The second lookup must come from the cache, not a second metadata fetch.
            assertThat(second).isSameAs(first);
            mocked.verify(() -> RelyingPartyRegistrations.fromMetadataLocation(anyString()));
        }
    }

    @Test
    @DisplayName("OrgSsoConfigChangedEvent evicts exactly that organization's cache entry")
    void eventEvictsOnlyItsOwnOrganization() {
        seedCache("org-saml-42", mock(RelyingPartyRegistration.class));
        seedCache("org-saml-99", mock(RelyingPartyRegistration.class));

        repository.onOrgSsoConfigChanged(new OrgSsoConfigChangedEvent(42L));

        assertThat(cache().getIfPresent("org-saml-42")).isNull();
        assertThat(cache().getIfPresent("org-saml-99")).isNotNull();
    }

    @Test
    @DisplayName("evicting an organization with no SAML cache entry is a harmless no-op")
    void evictingUnconfiguredOrganizationIsNoop() {
        repository.onOrgSsoConfigChanged(new OrgSsoConfigChangedEvent(123L));
    }

    @SuppressWarnings("unchecked")
    private Cache<String, RelyingPartyRegistration> cache() {
        return (Cache<String, RelyingPartyRegistration>) ReflectionTestUtils.getField(repository, "cache");
    }

    private void seedCache(String registrationId, RelyingPartyRegistration value) {
        cache().put(registrationId, value);
    }
}
