package com.bob.angularspringbootfullstack.service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Passkey (WebAuthn) lifecycle: registering a new credential from the Account Security Center,
 * listing/deleting a user's credentials (self-service and admin-assisted), and completing a
 * usernameless sign-in ceremony.
 *
 * <p>Mirrors {@link TotpService}'s split between authenticated lifecycle operations and the
 * public login-completion operation, and its "return a plain data carrier, let the controller
 * shape the HTTP envelope" convention.
 */
public interface PasskeyService {

    /**
     * The registration or authentication "options" payload, already shaped as the exact JSON the
     * browser's {@code PublicKeyCredential.parseCreationOptionsFromJSON}/
     * {@code parseRequestOptionsFromJSON} expect (WebAuthn Level 3's JSON serialization of
     * {@code PublicKeyCredentialCreationOptions}/{@code PublicKeyCredentialRequestOptions}).
     * Returned as a raw {@code Map} rather than a typed DTO because its shape IS the contract —
     * a browser API, not this application's own response envelope.
     */
    record CeremonyOptions(Map<String, Object> publicKey) {
    }

    /** One registered passkey, as shown on the Security Center and the admin user-detail page. */
    record PasskeyCredentialSummary(Long id, String deviceName, String transports, Date createdAt, Date lastUsedAt) {
    }

    /** The account a completed authentication ceremony resolved to. */
    record AuthenticationResult(Long userId) {
    }

    /**
     * Begins registering a new passkey for an already-authenticated user: mints a challenge and
     * returns creation options that exclude the user's existing credentials (so re-registering an
     * already-enrolled authenticator is refused client-side).
     *
     * @param userId the signed-in account, from the JWT principal
     * @param email  the account email, shown to the user inside their platform's passkey UI
     * @return the creation options to hand to {@code navigator.credentials.create()}
     */
    CeremonyOptions beginRegistration(Long userId, String email);

    /**
     * Completes registration: verifies the browser's attestation response against the challenge
     * minted by {@link #beginRegistration}, persists the credential, and flips the denormalized
     * {@code users.using_passkey} flag on.
     *
     * @param userId        the signed-in account — must match the user the challenge was minted for
     * @param deviceName    the nickname the user gave this passkey (e.g. "MacBook Touch ID")
     * @param credentialJson the browser's {@code PublicKeyCredential.toJSON()} registration response, verbatim
     * @return the newly stored credential's summary
     */
    PasskeyCredentialSummary finishRegistration(Long userId, String deviceName, String credentialJson);

    /**
     * Lists a user's registered passkeys, newest first. Used by both the self-service Security
     * Center and the admin user-detail page.
     *
     * @param userId the account whose credentials to list
     * @return the credential summaries, never null
     */
    List<PasskeyCredentialSummary> listCredentials(Long userId);

    /**
     * Deletes exactly one credential, scoped to its owner — the same statement backs self-service
     * deletion and admin-initiated revocation (see {@code PasskeyQuery}'s doc comment). Silently
     * no-ops when the id does not belong to this user, the same fail-quiet posture this app uses
     * for other user-facing deletes, rather than distinguishing "not found" from "not yours".
     *
     * @param userId       the owning account
     * @param credentialId the credential's primary key (never the WebAuthn credential id itself —
     *                     that value is never exposed to a client)
     */
    void deleteCredential(Long userId, Long credentialId);

    /**
     * Removes every credential a user holds — the admin "help reset" bulk action (there is no
     * "regenerate a passkey": the private key never leaves the authenticator, so revocation is the
     * only lever anyone has), or a full self-service wipe.
     *
     * @param userId the account to clear
     */
    void deleteAllCredentials(Long userId);

    /**
     * Begins a usernameless (discoverable-credential) authentication ceremony: mints a challenge
     * not bound to any account, since the server does not know who is signing in until the
     * browser's assertion names a credential id.
     *
     * @return the request options to hand to {@code navigator.credentials.get()}
     */
    CeremonyOptions beginAuthentication();

    /**
     * Completes a passkey sign-in: resolves the account from the assertion's credential id,
     * verifies the signature against the stored public key, and advances the clone-detection sign
     * counter. Every failure path (unknown challenge, unknown credential, bad signature) throws the
     * same generic message — unlike TOTP's challenge (which is scoped to one known user), an
     * unresolved passkey assertion carries no account context to leak in the first place.
     *
     * @param credentialJson the browser's {@code PublicKeyCredential.toJSON()} authentication response, verbatim
     * @return the resolved user id
     */
    AuthenticationResult finishAuthentication(String credentialJson);
}
