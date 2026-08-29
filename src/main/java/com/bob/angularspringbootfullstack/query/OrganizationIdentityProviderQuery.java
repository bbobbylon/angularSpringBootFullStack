package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for per-organization external IdP (enterprise SSO) configuration
 * (FUTURE-ENHANCEMENTS.md §3.1), consumed by {@code OrganizationIdentityProviderServiceImpl}
 * through {@code NamedParameterJdbcTemplate}, following the same centralized-query, service-owns-
 * its-SQL convention as {@link OrganizationQuery}.
 *
 * <p>Two tables: {@code organizationidentityproviders} (one row per organization,
 * {@code UQ_OrgIdP_Organization}) and {@code organizationssodomains} (many rows per organization,
 * globally unique per domain via {@code UQ_OrgSsoDomains_Domain}).
 */
public class OrganizationIdentityProviderQuery {

    /**
     * Fetches an organization's IdP configuration, if any. Parameter: organizationId.
     */
    public static final String SELECT_IDP_BY_ORGANIZATION_QUERY =
            "SELECT * FROM organizationidentityproviders WHERE organization_id = :organizationId";

    /**
     * Inserts an organization's first IdP configuration. Relies on
     * {@code UQ_OrgIdP_Organization} to reject a second row for the same organization with
     * {@link org.springframework.dao.DuplicateKeyException}, mirroring
     * {@link OrganizationQuery#INSERT_MEMBERSHIP_QUERY}'s insert-then-catch idiom rather than an
     * {@code INSERT ... ON DUPLICATE KEY UPDATE}, which {@code SqlTableCaseConsistencyTest} rejects.
     * Parameters: organizationId, protocol, displayName, status, oidcIssuerUri, oidcClientId,
     * oidcClientSecretCiphertext.
     */
    public static final String INSERT_IDP_QUERY =
            "INSERT INTO organizationidentityproviders " +
            "(organization_id, protocol, display_name, status, oidc_issuer_uri, oidc_client_id, oidc_client_secret_ciphertext) " +
            "VALUES (:organizationId, :protocol, :displayName, :status, :oidcIssuerUri, :oidcClientId, :oidcClientSecretCiphertext)";

    /**
     * Replaces an existing configuration's non-secret fields and its client secret together — used
     * when the admin supplies a new plaintext secret. Also clears {@code saml_metadata_uri}: an
     * organization replaces its row to switch protocols rather than layering both (schema's
     * {@code UQ_OrgIdP_Organization}), so writing OIDC fields must leave no stale SAML value behind
     * if this row previously held a SAML configuration. Parameters: protocol, displayName, status,
     * oidcIssuerUri, oidcClientId, oidcClientSecretCiphertext, organizationId.
     */
    public static final String UPDATE_IDP_WITH_SECRET_QUERY =
            "UPDATE organizationidentityproviders SET protocol = :protocol, display_name = :displayName, " +
            "status = :status, oidc_issuer_uri = :oidcIssuerUri, oidc_client_id = :oidcClientId, " +
            "oidc_client_secret_ciphertext = :oidcClientSecretCiphertext, saml_metadata_uri = NULL " +
            "WHERE organization_id = :organizationId";

    /**
     * Replaces an existing configuration's non-secret fields only, leaving the previously stored
     * client secret untouched — used when the admin edits the config without re-entering a secret.
     * Also clears {@code saml_metadata_uri}, same protocol-switch reasoning as
     * {@link #UPDATE_IDP_WITH_SECRET_QUERY}. Parameters: protocol, displayName, status,
     * oidcIssuerUri, oidcClientId, organizationId.
     */
    public static final String UPDATE_IDP_KEEP_SECRET_QUERY =
            "UPDATE organizationidentityproviders SET protocol = :protocol, display_name = :displayName, " +
            "status = :status, oidc_issuer_uri = :oidcIssuerUri, oidc_client_id = :oidcClientId, " +
            "saml_metadata_uri = NULL WHERE organization_id = :organizationId";

    /**
     * Inserts an organization's first SAML configuration. Sibling of {@link #INSERT_IDP_QUERY} for
     * the SAML protocol (FUTURE-ENHANCEMENTS.md §3.1, Stage 3) — the OIDC columns are simply not
     * written, so they keep the schema's {@code DEFAULT NULL}. Parameters: organizationId, protocol,
     * displayName, status, samlMetadataUri.
     */
    public static final String INSERT_SAML_IDP_QUERY =
            "INSERT INTO organizationidentityproviders " +
            "(organization_id, protocol, display_name, status, saml_metadata_uri) " +
            "VALUES (:organizationId, :protocol, :displayName, :status, :samlMetadataUri)";

    /**
     * Replaces an existing configuration with a SAML configuration, clearing every OIDC column
     * (issuer, client id, secret ciphertext) so switching this organization's row from OIDC to SAML
     * leaves no stale credential behind — the mirror image of what
     * {@link #UPDATE_IDP_WITH_SECRET_QUERY}/{@link #UPDATE_IDP_KEEP_SECRET_QUERY} do for the
     * opposite switch. Parameters: protocol, displayName, status, samlMetadataUri, organizationId.
     */
    public static final String UPDATE_SAML_IDP_QUERY =
            "UPDATE organizationidentityproviders SET protocol = :protocol, display_name = :displayName, " +
            "status = :status, saml_metadata_uri = :samlMetadataUri, oidc_issuer_uri = NULL, " +
            "oidc_client_id = NULL, oidc_client_secret_ciphertext = NULL WHERE organization_id = :organizationId";

    /**
     * Removes an organization's IdP configuration entirely (its members fall back to ordinary
     * password/consumer-OAuth login). Parameter: organizationId.
     */
    public static final String DELETE_IDP_QUERY =
            "DELETE FROM organizationidentityproviders WHERE organization_id = :organizationId";

    /**
     * Lists the email domains routed to one organization's SSO login. Parameter: organizationId.
     */
    public static final String SELECT_DOMAINS_BY_ORGANIZATION_QUERY =
            "SELECT * FROM organizationssodomains WHERE organization_id = :organizationId ORDER BY domain";

    /**
     * Claims a domain for an organization. {@code UQ_OrgSsoDomains_Domain} rejects a domain already
     * claimed by any organization (including this one) with
     * {@link org.springframework.dao.DuplicateKeyException} — the key safety property that keeps
     * the email-domain lookup always unambiguous. Parameters: organizationId, domain.
     */
    public static final String INSERT_DOMAIN_QUERY =
            "INSERT INTO organizationssodomains (organization_id, domain) VALUES (:organizationId, :domain)";

    /**
     * Removes a domain from an organization's SSO routing. Scoped by {@code organizationId} as well
     * as {@code id} so one organization's admin can never remove a domain row belonging to another
     * organization by guessing its numeric id. Parameters: id, organizationId.
     */
    public static final String DELETE_DOMAIN_QUERY =
            "DELETE FROM organizationssodomains WHERE id = :id AND organization_id = :organizationId";

    /**
     * The email-domain discovery lookup (public {@code GET /oauth2/org-sso-lookup}): given a
     * domain, finds the organization that claims it, if that organization's IdP configuration
     * exists and both the organization and its IdP are ACTIVE. Never selects the ciphertext column
     * — the lookup only ever needs to report that SSO is available, never the secret.
     * Parameter: domain.
     */
    public static final String SELECT_ACTIVE_PROVIDER_BY_DOMAIN_QUERY =
            "SELECT o.id AS organization_id, o.name AS organization_name, " +
            "p.display_name AS display_name, p.protocol AS protocol " +
            "FROM organizationssodomains d " +
            "JOIN organizationidentityproviders p ON p.organization_id = d.organization_id " +
            "JOIN organizations o ON o.id = d.organization_id " +
            "WHERE d.domain = :domain AND p.status = 'ACTIVE' AND o.status = 'ACTIVE'";

    /**
     * Resolves the live OIDC credentials — including the ciphertext — for one organization's
     * active configuration, used only by Stage 2's {@code OrgAwareClientRegistrationRepository} to
     * build a real {@code ClientRegistration} at login time. This is the single query in this class
     * that selects {@code oidc_client_secret_ciphertext}; every other query either omits it or
     * writes it. Filtered to {@code protocol = 'OIDC'} so a future SAML-configured row (Stage 3)
     * never resolves here by mistake. Parameter: organizationId.
     */
    public static final String SELECT_ACTIVE_OIDC_CREDENTIALS_QUERY =
            "SELECT display_name, oidc_issuer_uri, oidc_client_id, oidc_client_secret_ciphertext " +
            "FROM organizationidentityproviders " +
            "WHERE organization_id = :organizationId AND protocol = 'OIDC' AND status = 'ACTIVE'";

    /**
     * Resolves the live SAML metadata location for one organization's active configuration, used
     * only by Stage 3's {@code OrgAwareRelyingPartyRegistrationRepository} to build a real
     * {@code RelyingPartyRegistration} at login time. No secret to select — unlike OIDC, this MVP
     * stores nothing sensitive for SAML, only a metadata URL. Filtered to {@code protocol = 'SAML'}
     * so a future OIDC-configured row never resolves here by mistake. Parameter: organizationId.
     */
    public static final String SELECT_ACTIVE_SAML_METADATA_QUERY =
            "SELECT display_name, saml_metadata_uri FROM organizationidentityproviders " +
            "WHERE organization_id = :organizationId AND protocol = 'SAML' AND status = 'ACTIVE'";
}
