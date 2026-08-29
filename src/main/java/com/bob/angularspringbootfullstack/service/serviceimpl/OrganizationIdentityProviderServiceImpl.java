package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.OrgSsoLookupResult;
import com.bob.angularspringbootfullstack.event.OrgSsoConfigChangedEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.OrgSsoDomain;
import com.bob.angularspringbootfullstack.model.OrganizationIdentityProvider;
import com.bob.angularspringbootfullstack.rowmapper.OrgSsoDomainRowMapper;
import com.bob.angularspringbootfullstack.rowmapper.OrganizationIdentityProviderRowMapper;
import com.bob.angularspringbootfullstack.service.OrganizationIdentityProviderService;
import com.bob.angularspringbootfullstack.utils.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.DELETE_DOMAIN_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.DELETE_IDP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.INSERT_DOMAIN_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.INSERT_IDP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.INSERT_SAML_IDP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_ACTIVE_OIDC_CREDENTIALS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_ACTIVE_PROVIDER_BY_DOMAIN_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_ACTIVE_SAML_METADATA_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_DOMAINS_BY_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.SELECT_IDP_BY_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_IDP_KEEP_SECRET_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_IDP_WITH_SECRET_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationIdentityProviderQuery.UPDATE_SAML_IDP_QUERY;
import static com.bob.angularspringbootfullstack.constants.Constants.ORG_OIDC_REGISTRATION_PREFIX;
import static com.bob.angularspringbootfullstack.constants.Constants.ORG_SAML_REGISTRATION_PREFIX;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * JDBC-backed implementation of per-organization external IdP configuration
 * (FUTURE-ENHANCEMENTS.md §3.1), following the same service-owns-its-SQL shape as
 * {@link OrganizationServiceImpl}: SQL lives in {@code OrganizationIdentityProviderQuery}, this
 * class talks to {@link NamedParameterJdbcTemplate} directly with no Repo/RepoImpl layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationIdentityProviderServiceImpl implements OrganizationIdentityProviderService {

    private static final Set<String> VALID_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EncryptionUtil encryptionUtil;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<OrganizationIdentityProvider> getConfig(Long organizationId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(SELECT_IDP_BY_ORGANIZATION_QUERY,
                    Map.of("organizationId", organizationId), new OrganizationIdentityProviderRowMapper()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the existing configuration first (this class's usual insert-then-catch idiom does
     * not fit here, since which columns to write depends on whether a new secret was supplied, not
     * just on whether a row already exists) and picks one of three statements: {@link
     * #INSERT_IDP_QUERY} for a brand-new configuration, {@link #UPDATE_IDP_WITH_SECRET_QUERY} when
     * the admin supplied a new secret, or {@link #UPDATE_IDP_KEEP_SECRET_QUERY} when they left the
     * secret field blank to keep editing without re-entering it. A first-time configuration must
     * supply a secret — there is nothing to "keep" yet.
     */
    @Override
    public OrganizationIdentityProvider upsertOidcConfig(Long organizationId, String displayName, String issuerUri,
                                                          String clientId, String plaintextSecret) {
        if (isBlank(displayName) || isBlank(issuerUri) || isBlank(clientId)) {
            throw new ApiException("Display name, issuer URI, and client ID are all required.");
        }
        boolean secretSupplied = !isBlank(plaintextSecret);
        boolean isNewConfig = getConfig(organizationId).isEmpty();
        if (isNewConfig && !secretSupplied) {
            throw new ApiException("A client secret is required when configuring SSO for the first time.");
        }

        log.info("Configuring SSO for organization {}", organizationId);
        try {
            if (isNewConfig) {
                Map<String, Object> params = Map.of(
                        "organizationId", organizationId,
                        "protocol", "OIDC",
                        "displayName", displayName.trim(),
                        "status", "ACTIVE",
                        "oidcIssuerUri", issuerUri.trim(),
                        "oidcClientId", clientId.trim(),
                        "oidcClientSecretCiphertext", encryptionUtil.encrypt(plaintextSecret.trim()));
                jdbcTemplate.update(INSERT_IDP_QUERY, params);
            } else if (secretSupplied) {
                Map<String, Object> params = Map.of(
                        "organizationId", organizationId,
                        "protocol", "OIDC",
                        "displayName", displayName.trim(),
                        "status", "ACTIVE",
                        "oidcIssuerUri", issuerUri.trim(),
                        "oidcClientId", clientId.trim(),
                        "oidcClientSecretCiphertext", encryptionUtil.encrypt(plaintextSecret.trim()));
                jdbcTemplate.update(UPDATE_IDP_WITH_SECRET_QUERY, params);
            } else {
                Map<String, Object> params = Map.of(
                        "organizationId", organizationId,
                        "protocol", "OIDC",
                        "displayName", displayName.trim(),
                        "status", "ACTIVE",
                        "oidcIssuerUri", issuerUri.trim(),
                        "oidcClientId", clientId.trim());
                jdbcTemplate.update(UPDATE_IDP_KEEP_SECRET_QUERY, params);
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException("This organization already has an identity provider configured.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while saving the identity provider configuration. Please try again.");
        }
        eventPublisher.publishEvent(new OrgSsoConfigChangedEvent(organizationId));
        return getConfig(organizationId).orElseThrow(() -> new ApiException("Identity provider configuration not found."));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@link #upsertOidcConfig}'s insert-vs-update branching, minus the
     * secret-supplied/secret-kept split — a SAML metadata URI is not a secret, so there is nothing
     * to "keep unchanged" the way an OIDC client secret can be; every save simply writes whatever
     * URI was supplied.
     */
    @Override
    public OrganizationIdentityProvider upsertSamlConfig(Long organizationId, String displayName, String metadataUri) {
        if (isBlank(displayName) || isBlank(metadataUri)) {
            throw new ApiException("Display name and metadata URI are both required.");
        }
        boolean isNewConfig = getConfig(organizationId).isEmpty();
        log.info("Configuring SAML SSO for organization {}", organizationId);
        Map<String, Object> params = Map.of(
                "organizationId", organizationId,
                "protocol", "SAML",
                "displayName", displayName.trim(),
                "status", "ACTIVE",
                "samlMetadataUri", metadataUri.trim());
        try {
            jdbcTemplate.update(isNewConfig ? INSERT_SAML_IDP_QUERY : UPDATE_SAML_IDP_QUERY, params);
        } catch (DuplicateKeyException e) {
            throw new ApiException("This organization already has an identity provider configured.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while saving the identity provider configuration. Please try again.");
        }
        eventPublisher.publishEvent(new OrgSsoConfigChangedEvent(organizationId));
        return getConfig(organizationId).orElseThrow(() -> new ApiException("Identity provider configuration not found."));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Branches on the existing row's protocol, not just the status value: {@link
     * #UPDATE_IDP_KEEP_SECRET_QUERY} unconditionally writes {@code NULL} into
     * {@code saml_metadata_uri} (see that constant's Javadoc), so running it against a SAML row here
     * would silently wipe the org's metadata URI on a mere status toggle rather than actually
     * switching protocols. {@link #UPDATE_SAML_IDP_QUERY} is the SAML-safe equivalent, and
     * {@code Map.of} would NPE on this row's null {@code oidcIssuerUri}/{@code oidcClientId} fields
     * regardless, since a SAML row never populates them.
     */
    @Override
    public OrganizationIdentityProvider setStatus(Long organizationId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!VALID_STATUSES.contains(normalized)) {
            throw new ApiException("Status must be one of " + VALID_STATUSES + ".");
        }
        OrganizationIdentityProvider existing = getConfig(organizationId)
                .orElseThrow(() -> new ApiException("This organization has no identity provider configured."));
        boolean isSaml = "SAML".equals(existing.getProtocol());
        Map<String, Object> params = isSaml
                ? Map.of(
                        "organizationId", organizationId,
                        "protocol", existing.getProtocol(),
                        "displayName", existing.getDisplayName(),
                        "status", normalized,
                        "samlMetadataUri", existing.getSamlMetadataUri())
                : Map.of(
                        "organizationId", organizationId,
                        "protocol", existing.getProtocol(),
                        "displayName", existing.getDisplayName(),
                        "status", normalized,
                        "oidcIssuerUri", existing.getOidcIssuerUri(),
                        "oidcClientId", existing.getOidcClientId());
        log.info("Setting organization {} SSO status to '{}'", organizationId, normalized);
        try {
            jdbcTemplate.update(isSaml ? UPDATE_SAML_IDP_QUERY : UPDATE_IDP_KEEP_SECRET_QUERY, params);
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while updating the identity provider status. Please try again.");
        }
        eventPublisher.publishEvent(new OrgSsoConfigChangedEvent(organizationId));
        return getConfig(organizationId).orElseThrow(() -> new ApiException("Identity provider configuration not found."));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteConfig(Long organizationId) {
        log.info("Removing SSO configuration for organization {}", organizationId);
        jdbcTemplate.update(DELETE_IDP_QUERY, Map.of("organizationId", organizationId));
        eventPublisher.publishEvent(new OrgSsoConfigChangedEvent(organizationId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrgSsoDomain addDomain(Long organizationId, String domain) {
        String normalized = normalizeDomain(domain);
        log.info("Claiming SSO domain '{}' for organization {}", normalized, organizationId);
        try {
            jdbcTemplate.update(INSERT_DOMAIN_QUERY, Map.of("organizationId", organizationId, "domain", normalized));
        } catch (DuplicateKeyException e) {
            throw new ApiException("The domain '" + normalized + "' is already claimed by an organization.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while adding the domain. Please try again.");
        }
        return listDomains(organizationId).stream()
                .filter(d -> d.getDomain().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ApiException("Domain not found after insert."));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeDomain(Long organizationId, Long domainId) {
        int rows = jdbcTemplate.update(DELETE_DOMAIN_QUERY, Map.of("id", domainId, "organizationId", organizationId));
        if (rows == 0) {
            throw new ApiException("That domain does not belong to this organization.");
        }
        log.info("Removed SSO domain id {} from organization {}", domainId, organizationId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<OrgSsoDomain> listDomains(Long organizationId) {
        return jdbcTemplate.query(SELECT_DOMAINS_BY_ORGANIZATION_QUERY,
                Map.of("organizationId", organizationId), new OrgSsoDomainRowMapper());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The registration id embedded in {@link OrgSsoLookupResult#loginUrl} ({@code
     * org-oidc-{organizationId}}) is a forward reference to Stage 2's
     * {@code OrgAwareClientRegistrationRepository}, which resolves exactly that id pattern into a
     * live {@code ClientRegistration}. The URL is safe to hand out before Stage 2 ships — it is
     * relative and inert until that repository exists to answer it.
     *
     * <p>Stage 3 extends this to branch on the resolved row's {@code protocol}: a SAML organization
     * gets a {@code /saml2/authenticate/org-saml-{id}} URL instead, resolved by
     * {@code OrgAwareRelyingPartyRegistrationRepository}. The frontend's discovery affordance treats
     * this as an opaque URL either way, needing no protocol awareness of its own.
     */
    @Override
    public Optional<OrgSsoLookupResult> resolveByEmailDomain(String email) {
        if (isBlank(email) || !email.contains("@")) {
            return Optional.empty();
        }
        String domain = normalizeDomain(email.substring(email.indexOf('@') + 1));
        try {
            return jdbcTemplate.query(SELECT_ACTIVE_PROVIDER_BY_DOMAIN_QUERY, Map.of("domain", domain), resultSet -> {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                String organizationName = resultSet.getString("organization_name");
                String displayName = resultSet.getString("display_name");
                String protocol = resultSet.getString("protocol");
                long organizationId = resultSet.getLong("organization_id");
                String loginUrl = "SAML".equals(protocol)
                        ? "/saml2/authenticate/" + ORG_SAML_REGISTRATION_PREFIX + organizationId
                        : "/oauth2/authorization/" + ORG_OIDC_REGISTRATION_PREFIX + organizationId;
                return Optional.of(new OrgSsoLookupResult(organizationName, displayName, loginUrl));
            });
        } catch (Exception e) {
            log.error("SSO domain lookup failed for domain '{}': {}", domain, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fails closed on any error, same direction as {@link #resolveByEmailDomain} — a login
     * redirect must never surface a stack trace or a decryption error to the browser; it should
     * simply fail as if the organization had no SSO configured.
     */
    @Override
    public Optional<OrganizationIdentityProviderService.DecryptedOidcCredentials> resolveActiveOidcCredentials(Long organizationId) {
        try {
            return jdbcTemplate.query(SELECT_ACTIVE_OIDC_CREDENTIALS_QUERY, Map.of("organizationId", organizationId), resultSet -> {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                String ciphertext = resultSet.getString("oidc_client_secret_ciphertext");
                if (ciphertext == null) {
                    // Configured but never given a secret — cannot build a working registration.
                    return Optional.empty();
                }
                return Optional.of(new OrganizationIdentityProviderService.DecryptedOidcCredentials(
                        resultSet.getString("oidc_issuer_uri"),
                        resultSet.getString("oidc_client_id"),
                        encryptionUtil.decrypt(ciphertext),
                        resultSet.getString("display_name")));
            });
        } catch (Exception e) {
            log.error("Failed to resolve active OIDC credentials for organization {}: {}", organizationId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fails closed on any error, same direction as {@link #resolveActiveOidcCredentials} — a
     * login redirect must never surface a stack trace to the browser.
     */
    @Override
    public Optional<OrganizationIdentityProviderService.SamlIdpConfig> resolveActiveSamlConfig(Long organizationId) {
        try {
            return jdbcTemplate.query(SELECT_ACTIVE_SAML_METADATA_QUERY, Map.of("organizationId", organizationId), resultSet -> {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                String metadataUri = resultSet.getString("saml_metadata_uri");
                if (metadataUri == null) {
                    // Configured but never given a metadata URI — cannot build a working registration.
                    return Optional.empty();
                }
                return Optional.of(new OrganizationIdentityProviderService.SamlIdpConfig(
                        metadataUri, resultSet.getString("display_name")));
            });
        } catch (Exception e) {
            log.error("Failed to resolve active SAML configuration for organization {}: {}", organizationId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Email domains are compared case-insensitively; the routing table stores them lowercase. */
    private static String normalizeDomain(String domain) {
        if (isBlank(domain)) {
            throw new ApiException("Domain is required.");
        }
        return domain.trim().toLowerCase();
    }
}
