package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for the federated-identity link table ({@code oauthproviderlinks},
 * SRS DB-6), consumed by
 * {@link com.bob.angularspringbootfullstack.service.serviceimpl.FederatedIdentityServiceImpl}
 * through {@code NamedParameterJdbcTemplate}, following the same centralized-query
 * convention as {@link UserQuery} and {@link RoleQuery}.
 *
 * <p>The link table stores only (provider, provider_subject) per FR-FED-6 — the minimum
 * needed to re-associate a returning federated identity with its local user. The
 * composite unique key on those two columns is what makes the find-or-create flow
 * idempotent across repeated logins.
 */
public class OAuthQuery {

    /**
     * Resolves a federated identity to its local user id. Returns zero or one row
     * thanks to UQ_OAuthProviderLinks_Provider_Subject. Parameters: provider, subject.
     */
    public static final String SELECT_USER_ID_BY_PROVIDER_SUBJECT_QUERY =
            "SELECT user_id FROM oauthproviderlinks WHERE provider = :provider AND provider_subject = :subject";

    /**
     * Records a new federated identity link after find-or-create resolves (or creates)
     * the local user. Parameters: userId, provider, subject.
     */
    public static final String INSERT_PROVIDER_LINK_QUERY =
            "INSERT INTO oauthproviderlinks (user_id, provider, provider_subject) VALUES (:userId, :provider, :subject)";

    /**
     * Creates the local account for a first-time federated user (FR-FED-3).
     * <p>
     * Differs deliberately from {@link UserQuery#INSERT_USER_QUERY}: there is no password
     * (the column is nullable; this account authenticates only through its provider until
     * a reset flow sets one), the account is created enabled (the provider already
     * verified the email, so the in-house email-verification step is redundant), and the
     * provider's avatar is used when present ({@code COALESCE} falls back to the column
     * default for providers that do not supply one).
     * Parameters: firstName, lastName, email, imageUrl (nullable).
     */
    public static final String INSERT_FEDERATED_USER_QUERY =
            "INSERT INTO users (first_name, last_name, email, enabled, image_url) " +
            "VALUES (:firstName, :lastName, :email, TRUE, COALESCE(:imageUrl, DEFAULT(image_url)))";
}
