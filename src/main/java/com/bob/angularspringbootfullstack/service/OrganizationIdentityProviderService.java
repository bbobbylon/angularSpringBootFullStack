package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.OrgSsoLookupResult;
import com.bob.angularspringbootfullstack.model.OrgSsoDomain;
import com.bob.angularspringbootfullstack.model.OrganizationIdentityProvider;

import java.util.Collection;
import java.util.Optional;

/**
 * Manages an organization's external IdP configuration for single sign-on
 * (FUTURE-ENHANCEMENTS.md §3.1 "Per-organization external IdP") and the email domains routed to
 * it, backing {@code OrganizationIdentityProviderController}'s admin endpoints and the public
 * {@code GET /oauth2/org-sso-lookup} discovery endpoint on {@code FederatedAuthController}.
 * <p>
 * One IdP configuration per organization for this MVP — an organization replaces its row to switch
 * providers rather than layering several (schema's {@code UQ_OrgIdP_Organization}). Only OIDC is
 * wired today; SAML support is scoped as a follow-up (Stage 3).
 * <p>
 * <b>Never returns a decrypted client secret.</b> Every read-facing method reports only whether a
 * secret is configured ({@link OrganizationIdentityProvider#isSecretConfigured}) — the plaintext is
 * decrypted, internally, only at the point a login actually needs it to talk to the IdP's token
 * endpoint (Stage 2's {@code OrgAwareClientRegistrationRepository}).
 */
public interface OrganizationIdentityProviderService {

    /**
     * Fetches an organization's IdP configuration, if one exists.
     *
     * @param organizationId the organization to look up
     * @return the configuration, or empty if the organization has none configured
     */
    Optional<OrganizationIdentityProvider> getConfig(Long organizationId);

    /**
     * Creates or replaces an organization's OIDC configuration.
     *
     * @param organizationId  the organization being configured
     * @param displayName     shown on the login page's SSO redirect affordance
     * @param issuerUri       the IdP's OIDC issuer URI (discovery document lives at
     *                        {@code {issuerUri}/.well-known/openid-configuration})
     * @param clientId        the OAuth2 client id this application was registered under with the IdP
     * @param plaintextSecret the OAuth2 client secret; when {@code null} or blank on an update, the
     *                        previously stored secret is kept unchanged — allows an admin to edit
     *                        the issuer/client id/display name without re-entering the secret
     * @return the saved configuration
     */
    OrganizationIdentityProvider upsertOidcConfig(Long organizationId, String displayName, String issuerUri,
                                                   String clientId, String plaintextSecret);

    /**
     * Creates or replaces an organization's SAML configuration (Stage 3). Sibling of
     * {@link #upsertOidcConfig} for the SAML protocol — switches the organization's single IdP row
     * (schema's {@code UQ_OrgIdP_Organization}) to SAML, clearing any previously stored OIDC fields,
     * the same way {@link #upsertOidcConfig} clears any previously stored SAML metadata URI.
     *
     * @param organizationId the organization being configured
     * @param displayName    shown on the login page's SSO redirect affordance
     * @param metadataUri    the IdP's SAML metadata document location; unlike an OIDC client secret,
     *                       this is not sensitive and is stored in plaintext
     * @return the saved configuration
     */
    OrganizationIdentityProvider upsertSamlConfig(Long organizationId, String displayName, String metadataUri);

    /**
     * Activates or deactivates an organization's IdP configuration without deleting it — an
     * inactive configuration is excluded from {@link #resolveByEmailDomain} and, in Stage 2, from
     * dynamic {@code ClientRegistration} resolution.
     *
     * @param organizationId the organization whose configuration is being toggled
     * @param status         {@code "ACTIVE"} or {@code "INACTIVE"}
     * @return the updated configuration
     */
    OrganizationIdentityProvider setStatus(Long organizationId, String status);

    /**
     * Removes an organization's IdP configuration entirely. Its members fall back to ordinary
     * password or consumer-OAuth login on their next sign-in.
     *
     * @param organizationId the organization whose configuration is being removed
     */
    void deleteConfig(Long organizationId);

    /**
     * Claims an email domain for an organization's SSO routing.
     *
     * @param organizationId the organization claiming the domain
     * @param domain         the email domain (e.g. {@code "acme.com"}), case-insensitive
     * @return the created domain row
     */
    OrgSsoDomain addDomain(Long organizationId, String domain);

    /**
     * Releases a domain from an organization's SSO routing.
     *
     * @param organizationId the organization the domain must currently belong to
     * @param domainId       the domain row to remove
     */
    void removeDomain(Long organizationId, Long domainId);

    /**
     * Lists the email domains currently routed to one organization's SSO login.
     *
     * @param organizationId the organization to list domains for
     * @return the organization's claimed domains, in a stable order
     */
    Collection<OrgSsoDomain> listDomains(Long organizationId);

    /**
     * The email-domain discovery lookup: given a full email address, finds the organization (if
     * any) whose SSO configuration claims that email's domain.
     *
     * @param email the email address the login page's user entered
     * @return the lookup result, or empty when the domain is unclaimed, the organization's IdP is
     * inactive, or the organization itself is inactive — deliberately the same neutral "not found"
     * outcome in every case, so the response never distinguishes why
     */
    Optional<OrgSsoLookupResult> resolveByEmailDomain(String email);

    /**
     * Resolves the decrypted OIDC credentials for one organization's active configuration —
     * the single exception to this interface's "never returns a decrypted client secret" rule.
     * Exists solely for Stage 2's {@code OrgAwareClientRegistrationRepository}, which needs the
     * real issuer/client id/secret to build a live {@code ClientRegistration} at the moment a
     * login redirect actually needs to talk to the IdP's token endpoint.
     * <p>
     * <b>Callers must never expose this result</b> — not in a controller response, not in a log
     * line, not in an exception message. It exists to cross exactly one boundary: from encrypted
     * storage into an in-memory {@code ClientRegistration} that Spring Security's OAuth2 client
     * holds only as long as the login exchange takes.
     *
     * @param organizationId the organization whose OIDC configuration to resolve
     * @return the decrypted credentials, or empty when the organization has no configuration, its
     * configuration is inactive, its protocol is not OIDC, or no client secret has been stored
     */
    Optional<DecryptedOidcCredentials> resolveActiveOidcCredentials(Long organizationId);

    /**
     * The live credentials {@link #resolveActiveOidcCredentials} hands to
     * {@code OrgAwareClientRegistrationRepository} — never persisted, never serialized, held only
     * for the duration of building one {@code ClientRegistration}.
     *
     * @param issuerUri    the IdP's OIDC issuer URI
     * @param clientId     the OAuth2 client id registered with the IdP
     * @param clientSecret the decrypted OAuth2 client secret
     * @param displayName  shown in logs in place of the organization id, for readability
     */
    record DecryptedOidcCredentials(String issuerUri, String clientId, String clientSecret, String displayName) {
    }

    /**
     * Resolves the SAML metadata location for one organization's active configuration — the SAML
     * sibling of {@link #resolveActiveOidcCredentials}, used solely by Stage 3's
     * {@code OrgAwareRelyingPartyRegistrationRepository}. No decryption step, unlike the OIDC path:
     * a metadata URL is not a secret.
     *
     * @param organizationId the organization whose SAML configuration to resolve
     * @return the metadata location, or empty when the organization has no configuration, its
     * configuration is inactive, or its protocol is not SAML
     */
    Optional<SamlIdpConfig> resolveActiveSamlConfig(Long organizationId);

    /**
     * The live configuration {@link #resolveActiveSamlConfig} hands to
     * {@code OrgAwareRelyingPartyRegistrationRepository}.
     *
     * @param metadataUri the IdP's SAML metadata document location
     * @param displayName shown in logs in place of the organization id, for readability
     */
    record SamlIdpConfig(String metadataUri, String displayName) {
    }
}
