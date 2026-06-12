package com.bob.angularspringbootfullstack.service;

import java.util.List;

/**
 * Business contract for authenticator-app multi-factor authentication
 * (SRS §4.5 FR-MFA-4, plan.md M4) — the in-house replacement for the stubbed
 * SMS second factor.
 *
 * <p>Two distinct lifecycles meet here:
 * <ul>
 *   <li><b>Enrollment</b> (authenticated user, from the Account Security Center):
 *       {@link #beginEnrollment} mints a pending secret and provisioning QR;
 *       {@link #confirmEnrollment} proves the user actually scanned it and only then
 *       activates TOTP and issues recovery codes; {@link #disableTotp} requires a live
 *       code so a stolen browser session alone cannot strip the second factor.</li>
 *   <li><b>Login verification</b> (pre-token, public endpoint):
 *       {@link #createLoginChallenge} is called by the password and federated login
 *       paths AFTER the first factor succeeds, recording a short-lived server-side
 *       challenge; {@link #verifyLoginChallenge} exchanges challenge + code for the
 *       user, closing the bypass a bare "verify TOTP by email" endpoint would open
 *       (a TOTP code always exists on the phone — unlike an SMS code, its existence
 *       proves nothing about the password step).</li>
 * </ul>
 */
public interface TotpService {

    /**
     * Everything the enrollment wizard needs to render: the Base32 secret for manual
     * entry, the {@code otpauth://} URI, and that URI rendered as a QR PNG data URI.
     *
     * @param secret     the Base32 shared secret (also shown for manual entry)
     * @param otpauthUri the Key-Uri-Format provisioning string
     * @param qrCode     {@code data:image/png;base64,...} rendering of {@code otpauthUri}
     */
    record TotpEnrollment(String secret, String otpauthUri, String qrCode) {
    }

    /**
     * Outcome of a successful login-time verification.
     *
     * @param userId           the verified user's id
     * @param usedRecoveryCode true when a single-use recovery code (not a live TOTP
     *                         code) satisfied the challenge — callers audit this
     *                         distinctly (RECOVERY_CODE_USED)
     */
    record TotpVerification(Long userId, boolean usedRecoveryCode) {
    }

    /**
     * Starts (or restarts) enrollment by generating a fresh pending secret for the user.
     * The secret is inert until {@link #confirmEnrollment} succeeds.
     *
     * @param userId the authenticated user's id
     * @param email  the user's email, used as the account label inside the authenticator app
     * @return the secret, provisioning URI, and QR code for the wizard
     */
    TotpEnrollment beginEnrollment(Long userId, String email);

    /**
     * Activates TOTP after the user proves possession of the authenticator by echoing a
     * valid code for the pending secret. Issues a fresh batch of single-use recovery
     * codes whose plaintext is returned exactly once and stored only as hashes.
     *
     * @param userId the authenticated user's id
     * @param code   the 6-digit code from the authenticator app
     * @return the plaintext recovery codes for one-time display
     */
    List<String> confirmEnrollment(Long userId, String code);

    /**
     * Disables TOTP. Requires a currently valid TOTP code or an unused recovery code —
     * possession of an authenticated browser session alone is deliberately insufficient,
     * so a hijacked session cannot quietly remove the second factor.
     *
     * @param userId the authenticated user's id
     * @param code   a live TOTP code or an unused recovery code
     */
    void disableTotp(Long userId, String code);

    /**
     * Records that the first authentication factor (password or federated) just
     * succeeded for this user, returning the opaque challenge the SPA must present
     * together with the TOTP code. Short-lived and single-active per user.
     *
     * @param userId the user who passed the first factor
     * @return the challenge token to hand to the SPA
     */
    String createLoginChallenge(Long userId);

    /**
     * Completes login MFA: resolves a live challenge to its user and validates the
     * accompanying code (live TOTP first, then recovery-code fallback). The challenge
     * is consumed on success and left intact on a wrong code so the user may retry
     * until it expires.
     *
     * @param challenge the opaque token issued by {@link #createLoginChallenge}
     * @param code      a TOTP code or recovery code
     * @return the verified user id and whether a recovery code was burned
     */
    TotpVerification verifyLoginChallenge(String challenge, String code);

    /**
     * Counts the user's remaining unused recovery codes so the Account Security Center
     * can prompt regeneration-by-re-enrollment when the supply runs low.
     *
     * @param userId the authenticated user's id
     * @return how many recovery codes remain usable
     */
    long countUnusedRecoveryCodes(Long userId);
}
