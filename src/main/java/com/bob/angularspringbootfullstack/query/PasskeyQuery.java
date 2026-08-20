package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for passkey (WebAuthn) credentials. Consumed by {@code PasskeyServiceImpl}
 * through {@code NamedParameterJdbcTemplate}, following the same centralized-query convention as
 * {@link TotpQuery}.
 *
 * <p>One table, one lifecycle: {@code passkeycredentials} — unlike {@code totpcredentials} (one
 * row per user), a user may register several passkeys, one per device/authenticator, so rows are
 * keyed by the globally-unique {@code credential_id} rather than a per-user unique constraint.
 */
public class PasskeyQuery {

    /**
     * Persists a newly registered credential. {@code attestationObject} is the base64-encoded,
     * CBOR-serialized WebAuthn attestation object (re-serialized via webauthn4j's own
     * {@code ObjectConverter} after verification succeeds) — it embeds the credential's public key,
     * so no separate public-key column is needed; {@code PasskeyServiceImpl} re-parses it at
     * authentication time. Parameters: userId, credentialId, attestationObject, aaguid, transports,
     * deviceName.
     */
    public static final String INSERT_PASSKEY_CREDENTIAL_QUERY =
            "INSERT INTO passkeycredentials (user_id, credential_id, attestation_object, aaguid, transports, device_name) " +
            "VALUES (:userId, :credentialId, :attestationObject, :aaguid, :transports, :deviceName)";

    /**
     * Lists a user's credentials for the Security Center display and for building the
     * registration ceremony's {@code excludeCredentials} list (so re-registering an
     * already-enrolled authenticator is refused client-side rather than after a round trip).
     * Parameter: userId.
     */
    public static final String SELECT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY =
            "SELECT id, user_id, credential_id, attestation_object, sign_count, aaguid, transports, device_name, created_at, last_used_at " +
            "FROM passkeycredentials WHERE user_id = :userId ORDER BY created_at DESC";

    /**
     * Resolves an assertion's credential id back to the stored public key, sign count, and owning
     * user during login — the WebAuthn counterpart of looking up a user by email, except here the
     * credential id is the only identifier the server has (usernameless/discoverable login).
     * Parameter: credentialId.
     */
    public static final String SELECT_PASSKEY_CREDENTIAL_BY_CREDENTIAL_ID_QUERY =
            "SELECT id, user_id, credential_id, attestation_object, sign_count, aaguid, transports, device_name, created_at, last_used_at " +
            "FROM passkeycredentials WHERE credential_id = :credentialId";

    /**
     * Records a successful assertion: advances the clone-detection counter and stamps
     * {@code last_used_at}. Parameters: id, signCount.
     */
    public static final String UPDATE_PASSKEY_SIGN_COUNT_QUERY =
            "UPDATE passkeycredentials SET sign_count = :signCount, last_used_at = NOW() WHERE id = :id";

    /**
     * Deletes exactly one credential, scoped to its owner. The same statement backs both
     * self-service deletion (userId taken from the JWT principal) and admin-initiated revocation
     * (userId taken from the trusted path {@code {id}}, per {@code AdminUserController}'s
     * "admin path trusts the URL, not the body" convention) — either caller can only ever remove a
     * credential that belongs to the userId they supply. Parameters: id, userId.
     */
    public static final String DELETE_PASSKEY_CREDENTIAL_BY_ID_AND_USER_ID_QUERY =
            "DELETE FROM passkeycredentials WHERE id = :id AND user_id = :userId";

    /**
     * Removes every credential a user holds — the admin "help reset" bulk action, or a full
     * self-service wipe. Parameter: userId.
     */
    public static final String DELETE_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY =
            "DELETE FROM passkeycredentials WHERE user_id = :userId";

    /**
     * Counts a user's remaining credentials after a deletion, so the caller can decide whether to
     * clear the denormalized {@code users.using_passkey} flag. Parameter: userId.
     */
    public static final String COUNT_PASSKEY_CREDENTIALS_BY_USER_ID_QUERY =
            "SELECT COUNT(*) FROM passkeycredentials WHERE user_id = :userId";

    /**
     * Mirrors credential existence onto the denormalized {@code users.using_passkey} flag so row
     * mappers and DTOs expose passkey status without a join. Parameters: usingPasskey, userId.
     */
    public static final String UPDATE_USER_USING_PASSKEY_QUERY =
            "UPDATE users SET using_passkey = :usingPasskey WHERE id = :userId";
}
