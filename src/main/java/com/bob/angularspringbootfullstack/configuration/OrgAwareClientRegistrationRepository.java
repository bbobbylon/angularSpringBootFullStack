package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.event.OrgSsoConfigChangedEvent;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService.DecryptedOidcCredentials;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.bob.angularspringbootfullstack.constants.Constants.ORG_OIDC_REGISTRATION_PREFIX;

/**
 * The single {@link ClientRegistrationRepository} Spring Security's {@code .oauth2Login()} resolves
 * at runtime (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP", Stage 2), extending the
 * fixed, boot-time set of consumer providers ({@link OAuth2ClientConfig}'s Google/GitHub/Microsoft
 * registrations) with registrations resolved dynamically, per organization, from the database.
 *
 * <p><b>Two kinds of registration id, two resolution paths.</b> Every id Spring Security asks for
 * either belongs to a fixed consumer provider ({@code "google"}, {@code "github"}, {@code "microsoft"},
 * or the boot-only {@code "placeholder"}) — delegated as-is to
 * {@link OAuth2ClientConfig#staticClientRegistrationRepository}, unchanged from before this class
 * existed — or matches {@code org-oidc-{organizationId}}, the id format
 * {@code OrganizationIdentityProviderServiceImpl} already constructs when it builds a login URL for
 * the admin frontend and the email-domain lookup endpoint. Only the second path does any work: it
 * decrypts that organization's stored OIDC credentials
 * ({@link OrganizationIdentityProviderService#resolveActiveOidcCredentials}) and calls
 * {@link ClientRegistrations#fromIssuerLocation}, which makes a live HTTP call to the IdP's
 * {@code .well-known/openid-configuration} discovery document.
 *
 * <p><b>Why this is cached.</b> {@code findByRegistrationId} runs on every single SSO redirect and
 * every callback for that organization — without caching, each login would cost an extra live HTTP
 * round trip to the IdP before Spring Security could even begin the OAuth2 exchange. Resolved
 * registrations are cached for a short, fixed TTL ({@link #CACHE_TTL}) keyed by registration id.
 * {@link #onOrgSsoConfigChanged} evicts a single organization's entry the moment
 * {@code OrganizationIdentityProviderServiceImpl} publishes {@link OrgSsoConfigChangedEvent} after an
 * admin edits or removes its configuration, so a change takes effect on the next login rather than
 * silently waiting out the TTL.
 *
 * <p><b>Fails closed.</b> An unknown organization id, an inactive or deleted configuration, or a
 * discovery document that fails to resolve (unreachable issuer, malformed metadata) all return
 * {@code null} from {@code findByRegistrationId} — Spring Security turns that into its own standard
 * "invalid registration id" failure page. Nothing here ever surfaces the underlying reason to the
 * browser, matching this codebase's user-enumeration discipline: a broken IdP configuration should
 * look, from outside, identical to an organization that never configured SSO at all.
 */
@Component
@Primary
@Slf4j
public class OrgAwareClientRegistrationRepository implements ClientRegistrationRepository {

    /**
     * How long a resolved {@link ClientRegistration} is trusted before this repository re-resolves
     * it from the database and the IdP's discovery document. Short enough that a revoked or edited
     * configuration can't be exploited for long by a login flow already in flight; long enough that
     * the discovery document isn't refetched on every redirect in a normal login burst.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final ClientRegistrationRepository staticDelegate;
    private final OrganizationIdentityProviderService organizationIdentityProviderService;
    private final OAuth2ClientConfig oauth2ClientConfig;
    private final Cache<String, ClientRegistration> cache;

    public OrgAwareClientRegistrationRepository(
            @Qualifier("staticClientRegistrationRepository") ClientRegistrationRepository staticDelegate,
            OrganizationIdentityProviderService organizationIdentityProviderService,
            OAuth2ClientConfig oauth2ClientConfig) {
        this.staticDelegate = staticDelegate;
        this.organizationIdentityProviderService = organizationIdentityProviderService;
        this.oauth2ClientConfig = oauth2ClientConfig;
        this.cache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).build();
    }

    /**
     * Resolves one registration id, either by delegating to the fixed consumer-provider repository
     * or by dynamically resolving and caching a per-organization OIDC registration.
     *
     * @param registrationId the id Spring Security's OAuth2 login filters are resolving, e.g.
     *                        {@code "google"} or {@code "org-oidc-42"}
     * @return the registration, or {@code null} if it cannot be resolved for any reason
     */
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null) {
            return null;
        }
        if (!registrationId.startsWith(ORG_OIDC_REGISTRATION_PREFIX)) {
            return staticDelegate.findByRegistrationId(registrationId);
        }
        ClientRegistration cached = cache.getIfPresent(registrationId);
        if (cached != null) {
            return cached;
        }
        return resolveOrgRegistration(registrationId);
    }

    /**
     * Parses the organization id out of an {@code org-oidc-*} registration id, decrypts that
     * organization's active OIDC credentials, and builds a fresh {@link ClientRegistration} from its
     * issuer's discovery document — caching the result on success.
     *
     * @param registrationId the full {@code org-oidc-{organizationId}} id
     * @return the freshly resolved registration, or {@code null} if the id is malformed, the
     * organization has no active OIDC configuration, or discovery fails
     */
    private ClientRegistration resolveOrgRegistration(String registrationId) {
        Long organizationId = parseOrganizationId(registrationId);
        if (organizationId == null) {
            log.warn("Rejected malformed org-oidc registration id: {}", registrationId);
            return null;
        }
        return organizationIdentityProviderService.resolveActiveOidcCredentials(organizationId)
                .map(credentials -> buildAndCache(registrationId, credentials))
                .orElse(null);
    }

    private Long parseOrganizationId(String registrationId) {
        try {
            return Long.parseLong(registrationId.substring(ORG_OIDC_REGISTRATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Builds the live {@link ClientRegistration} from decrypted credentials, using the same
     * redirect-URI template every other registration in this application uses
     * ({@link OAuth2ClientConfig#redirectUriTemplate()}) so an org's IdP callback lands on exactly
     * the same {@code {baseUrl}/login/oauth2/code/{registrationId}} shape.
     *
     * <p>Any failure here (an unreachable issuer, a malformed discovery document) is caught and
     * logged rather than propagated — a dynamic registration failing to resolve must fail closed
     * the same way an unconfigured organization does, not surface a stack trace mid-login-redirect.
     *
     * @param registrationId the {@code org-oidc-{organizationId}} id being resolved
     * @param credentials    the organization's decrypted issuer/client id/secret
     * @return the resolved, now-cached registration, or {@code null} if discovery failed
     */
    private ClientRegistration buildAndCache(String registrationId, DecryptedOidcCredentials credentials) {
        try {
            ClientRegistration registration = ClientRegistrations.fromIssuerLocation(credentials.issuerUri())
                    .registrationId(registrationId)
                    .clientId(credentials.clientId())
                    .clientSecret(credentials.clientSecret())
                    .scope("openid", "profile", "email")
                    .redirectUri(oauth2ClientConfig.redirectUriTemplate())
                    .clientName(credentials.displayName())
                    .build();
            cache.put(registrationId, registration);
            return registration;
        } catch (Exception e) {
            log.error("Failed to resolve OIDC discovery document for {} ('{}'): {}",
                    registrationId, credentials.displayName(), e.getMessage());
            return null;
        }
    }

    /**
     * Invalidates one organization's cached registration whenever
     * {@code OrganizationIdentityProviderServiceImpl} publishes {@link OrgSsoConfigChangedEvent}
     * after an admin updates, deactivates, or deletes that organization's configuration — so the
     * change is visible on the very next login attempt instead of waiting out {@link #CACHE_TTL}.
     *
     * @param event carries the organization id whose cached registration should be dropped
     */
    @EventListener
    public void onOrgSsoConfigChanged(OrgSsoConfigChangedEvent event) {
        cache.invalidate(ORG_OIDC_REGISTRATION_PREFIX + event.getOrganizationId());
    }
}
