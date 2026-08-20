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
     * Lists the providers currently linked to one account, for the Security Center's
     * connected-accounts panel (ROADMAP §1.4).
     *
     * <p>Returns only the provider name and when it was linked — never the
     * {@code provider_subject}. That subject is the provider's stable identifier for the person,
     * and it is the one value in this table that would be useful to an attacker who had obtained
     * a read of the response: it is exactly what the find-or-create lookup matches on. The UI has
     * no use for it, so it does not leave the database.
     *
     * <p>Parameters: userId.
     */
    public static final String SELECT_PROVIDER_LINKS_BY_USER_ID_QUERY =
            "SELECT provider, created_at FROM oauthproviderlinks WHERE user_id = :userId ORDER BY created_at";

    /**
     * Counts how many providers are linked to an account.
     *
     * <p>Used by the unlink guard: an account whose only way in is a federated provider must not
     * be allowed to remove it, because doing so would lock the user out of their own account with
     * no path back other than an administrator. Parameters: userId.
     */
    public static final String COUNT_PROVIDER_LINKS_BY_USER_ID_QUERY =
            "SELECT COUNT(*) FROM oauthproviderlinks WHERE user_id = :userId";

    /**
     * Removes one provider link from an account.
     *
     * <p>Scoped by {@code user_id} as well as {@code provider} — never by link id alone. The id
     * would be sufficient to identify the row, and that is precisely the problem: a request that
     * names only a row id can be pointed at somebody else's row. Binding the caller's own id into
     * the predicate makes cross-account unlinking unrepresentable rather than merely refused.
     *
     * <p>Parameters: userId, provider.
     */
    public static final String DELETE_PROVIDER_LINK_QUERY =
            "DELETE FROM oauthproviderlinks WHERE user_id = :userId AND provider = :provider";

    /**
     * Whether the account has a usable password.
     *
     * <p>The other half of the unlink guard. A federated-only account created through
     * {@link #INSERT_FEDERATED_USER_QUERY} has {@code password IS NULL}, so removing its last
     * provider would leave it with no credential of any kind. An account that has since set a
     * password may safely unlink everything. Parameters: userId.
     */
    public static final String COUNT_PASSWORD_BY_USER_ID_QUERY =
            "SELECT COUNT(*) FROM users WHERE id = :userId AND password IS NOT NULL AND password <> ''";

    /**
     * Creates the local account for a first-time federated user (FR-FED-3).
     * <p>
     * Differs deliberately from {@link UserQuery#INSERT_USER_QUERY}: there is no password
     * (the column is nullable; this account authenticates only through its provider until
     * a reset flow sets one), the account is created enabled (the provider already
     * verified the email, so the in-house email-verification step is redundant), and the
     * provider's avatar is used when present ({@code COALESCE} falls back to the column
     * default for providers that do not supply one).
     * Parameters: firstName, lastName, email, imageUrl (nullable), origin (e.g. "FEDERATED_GOOGLE").
     */
    public static final String INSERT_FEDERATED_USER_QUERY =
            "INSERT INTO users (first_name, last_name, email, enabled, image_url, origin) " +
            "VALUES (:firstName, :lastName, :email, TRUE, COALESCE(:imageUrl, DEFAULT(image_url)), :origin)";
}
