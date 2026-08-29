package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.event.OrgSsoConfigChangedEvent;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService.SamlIdpConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.bob.angularspringbootfullstack.constants.Constants.ORG_SAML_REGISTRATION_PREFIX;

/**
 * The SAML sibling of {@link OrgAwareClientRegistrationRepository} (FUTURE-ENHANCEMENTS.md §3.1
 * "Per-organization external IdP", Stage 3): the {@link RelyingPartyRegistrationRepository} Spring
 * Security's {@code .saml2Login()} resolves at runtime, for organizations whose IdP only speaks SAML
 * 2.0 rather than OIDC.
 *
 * <p><b>No static fallback, unlike the OIDC repository.</b> This application has no
 * Google/GitHub/Microsoft equivalent for SAML — every registration id this repository is ever asked
 * for is, by construction, {@code org-saml-{organizationId}} (the id format
 * {@code OrganizationIdentityProviderServiceImpl} constructs when it builds a login URL). An id that
 * does not match that shape, or an organization with no active SAML configuration, fails closed to
 * {@code null} directly — there is nothing else to delegate to.
 *
 * <p><b>Why this is cached.</b> Same reasoning as {@link OrgAwareClientRegistrationRepository}:
 * {@link RelyingPartyRegistrations#fromMetadataLocation} makes a live HTTP fetch of the IdP's SAML
 * metadata XML document, and {@code findByRegistrationId} runs on every login redirect and every
 * assertion-consumer-service callback for that organization. Resolved registrations are cached for a
 * short, fixed TTL and evicted the moment {@code OrganizationIdentityProviderServiceImpl} publishes
 * {@link OrgSsoConfigChangedEvent} after an admin edits or removes the organization's configuration.
 *
 * <p><b>Fails closed.</b> An unknown organization id, an inactive/deleted/non-SAML configuration, or
 * a metadata document that fails to resolve (unreachable location, malformed XML) all return
 * {@code null} — Spring Security turns that into its own standard "invalid registration id" failure.
 * Matches this codebase's user-enumeration discipline: a broken IdP configuration looks, from
 * outside, identical to an organization that never configured SSO at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrgAwareRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

    /** Same TTL as {@link OrgAwareClientRegistrationRepository#CACHE_TTL}, for the same reasons. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final OrganizationIdentityProviderService organizationIdentityProviderService;
    private final Cache<String, RelyingPartyRegistration> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .build();

    /**
     * Resolves one {@code org-saml-{organizationId}} registration id, dynamically resolving and
     * caching the organization's {@link RelyingPartyRegistration} from its stored metadata location.
     *
     * @param registrationId the id Spring Security's SAML2 login filters are resolving
     * @return the registration, or {@code null} if it cannot be resolved for any reason
     */
    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(ORG_SAML_REGISTRATION_PREFIX)) {
            return null;
        }
        RelyingPartyRegistration cached = cache.getIfPresent(registrationId);
        if (cached != null) {
            return cached;
        }
        return resolveOrgRegistration(registrationId);
    }

    private RelyingPartyRegistration resolveOrgRegistration(String registrationId) {
        Long organizationId = parseOrganizationId(registrationId);
        if (organizationId == null) {
            log.warn("Rejected malformed org-saml registration id: {}", registrationId);
            return null;
        }
        return organizationIdentityProviderService.resolveActiveSamlConfig(organizationId)
                .map(config -> buildAndCache(registrationId, config))
                .orElse(null);
    }

    private Long parseOrganizationId(String registrationId) {
        try {
            return Long.parseLong(registrationId.substring(ORG_SAML_REGISTRATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Builds the live {@link RelyingPartyRegistration} from the organization's metadata location,
     * accepting Spring Security's default assertion-consumer-service location
     * ({@code {baseUrl}/login/saml2/sso/{registrationId}}) and entity id — this MVP has no per-org
     * customization need beyond which IdP metadata to parse.
     *
     * <p>Any failure here (an unreachable metadata location, malformed XML) is caught and logged
     * rather than propagated, the same fail-closed contract {@link #findByRegistrationId} documents.
     *
     * @param registrationId the {@code org-saml-{organizationId}} id being resolved
     * @param config         the organization's metadata location and display name
     * @return the resolved, now-cached registration, or {@code null} if metadata resolution failed
     */
    private RelyingPartyRegistration buildAndCache(String registrationId, SamlIdpConfig config) {
        try {
            RelyingPartyRegistration registration = RelyingPartyRegistrations.fromMetadataLocation(config.metadataUri())
                    .registrationId(registrationId)
                    .build();
            cache.put(registrationId, registration);
            return registration;
        } catch (Exception e) {
            log.error("Failed to resolve SAML metadata for {} ('{}'): {}",
                    registrationId, config.displayName(), e.getMessage());
            return null;
        }
    }

    /**
     * Invalidates one organization's cached SAML registration whenever
     * {@code OrganizationIdentityProviderServiceImpl} publishes {@link OrgSsoConfigChangedEvent}.
     * Harmless no-op for an organization that isn't SAML-configured — the cache simply has no entry
     * under that key.
     *
     * @param event carries the organization id whose cached registration should be dropped
     */
    @EventListener
    public void onOrgSsoConfigChanged(OrgSsoConfigChangedEvent event) {
        cache.invalidate(ORG_SAML_REGISTRATION_PREFIX + event.getOrganizationId());
    }
}
