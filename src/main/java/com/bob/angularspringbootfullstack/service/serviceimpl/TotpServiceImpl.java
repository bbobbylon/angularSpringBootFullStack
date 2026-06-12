package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.TotpService;
import com.bob.angularspringbootfullstack.utils.TotpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.bob.angularspringbootfullstack.constants.Constants.DATE_FORMAT;
import static com.bob.angularspringbootfullstack.query.TotpQuery.*;
import static org.apache.commons.lang3.time.DateFormatUtils.format;
import static org.apache.commons.lang3.time.DateUtils.addMinutes;

/**
 * JDBC-backed implementation of the authenticator-app MFA lifecycle (SRS FR-MFA-4),
 * following the same service-owns-the-logic convention as
 * {@link FederatedIdentityServiceImpl}: queries are centralized in
 * {@link com.bob.angularspringbootfullstack.query.TotpQuery} and multi-write operations
 * run inside one transaction so a partial failure can never strand an account between
 * MFA states (NFR-REL-3).
 *
 * <p>Policy decisions encoded here rather than in callers:
 * <ul>
 *   <li>An unconfirmed secret never satisfies anything — enrollment is only real after
 *       the user echoes a valid code back.</li>
 *   <li>Disabling requires a live code (TOTP or recovery), so a hijacked browser session
 *       cannot quietly strip the second factor.</li>
 *   <li>A wrong code at login does NOT consume the challenge — the user can retry until
 *       the challenge expires ({@value #CHALLENGE_EXPIRY_MINUTES} minutes), at which
 *       point the first factor must be repeated.</li>
 *   <li>Error messages never reveal whether a challenge maps to a real account
 *       (NFR-SEC-7) — an expired and a forged challenge read identically.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TotpServiceImpl implements TotpService {

    /** Issuer label rendered by authenticator apps next to the user's email. */
    private static final String TOTP_ISSUER = "SecureCapita";
    /** Pixel size of the enrollment QR code. */
    private static final int QR_SIZE = 240;
    /** How many single-use recovery codes a confirmation issues. */
    private static final int RECOVERY_CODE_COUNT = 10;
    /**
     * Lifetime of a login challenge. Long enough to open the authenticator app and type
     * (or dig out a recovery code), short enough that an abandoned half-login goes stale
     * before it becomes a liability.
     */
    private static final int CHALLENGE_EXPIRY_MINUTES = 5;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Starts (or restarts) enrollment per the contract: refuses if TOTP is already
     * active, replaces any abandoned pending secret, and returns the wizard payload.
     * Delete-then-insert mirrors the SMS code pattern — exactly one pending secret
     * can exist per user.
     */
    @Override
    @Transactional
    public TotpEnrollment beginEnrollment(Long userId, String email) {
        TotpCredential existing = findCredential(userId);
        if (existing != null && existing.confirmed()) {
            throw new ApiException("An authenticator app is already enabled on this account. Disable it before enrolling a new one.");
        }
        String secret = TotpUtils.generateSecret();
        jdbcTemplate.update(DELETE_TOTP_CREDENTIAL_BY_USER_ID_QUERY, Map.of("userId", userId));
        jdbcTemplate.update(INSERT_TOTP_CREDENTIAL_QUERY, Map.of("userId", userId, "secret", secret));
        String otpauthUri = TotpUtils.buildOtpAuthUri(TOTP_ISSUER, email, secret);
        log.info("TOTP enrollment started for user id {}", userId);
        return new TotpEnrollment(secret, otpauthUri, TotpUtils.qrCodeDataUri(otpauthUri, QR_SIZE));
    }

    /**
     * Confirms enrollment per the contract: validates the code against the PENDING
     * secret, then — in one transaction — confirms the credential, flips the
     * denormalized {@code users.using_totp} flag, and issues a fresh recovery-code
     * batch (hashes persisted, plaintext returned exactly once).
     */
    @Override
    @Transactional
    public List<String> confirmEnrollment(Long userId, String code) {
        TotpCredential credential = findCredential(userId);
        if (credential == null) {
            throw new ApiException("No enrollment in progress. Start authenticator setup first.");
        }
        if (credential.confirmed()) {
            throw new ApiException("An authenticator app is already enabled on this account.");
        }
        if (!TotpUtils.verifyCode(credential.secret(), code)) {
            throw new ApiException("That code didn't match. Check your authenticator app and try again.");
        }
        jdbcTemplate.update(CONFIRM_TOTP_CREDENTIAL_QUERY, Map.of("userId", userId));
        jdbcTemplate.update(UPDATE_USER_USING_TOTP_QUERY, Map.of("usingTotp", true, "userId", userId));
        List<String> recoveryCodes = issueRecoveryCodes(userId);
        log.info("TOTP confirmed and enabled for user id {}", userId);
        return recoveryCodes;
    }

    /**
     * Disables TOTP per the contract: the supplied code must be a live TOTP code or an
     * unused recovery code. Removes the credential and ALL recovery codes and clears the
     * denormalized flag in one transaction, returning the account to single-factor
     * (or SMS MFA, if {@code using_mfa} is still set).
     */
    @Override
    @Transactional
    public void disableTotp(Long userId, String code) {
        TotpCredential credential = findCredential(userId);
        if (credential == null || !credential.confirmed()) {
            throw new ApiException("An authenticator app is not enabled on this account.");
        }
        if (!TotpUtils.verifyCode(credential.secret(), code) && !consumeRecoveryCode(userId, code)) {
            throw new ApiException("That code didn't match. Enter a current authenticator code or an unused recovery code.");
        }
        jdbcTemplate.update(DELETE_TOTP_CREDENTIAL_BY_USER_ID_QUERY, Map.of("userId", userId));
        jdbcTemplate.update(DELETE_RECOVERY_CODES_BY_USER_ID_QUERY, Map.of("userId", userId));
        jdbcTemplate.update(UPDATE_USER_USING_TOTP_QUERY, Map.of("usingTotp", false, "userId", userId));
        log.info("TOTP disabled for user id {}", userId);
    }

    /**
     * Records first-factor success per the contract. Delete-then-insert keeps a single
     * active challenge per user; the UUID is the only thing the SPA ever holds, so a
     * leaked challenge without the authenticator remains useless.
     */
    @Override
    @Transactional
    public String createLoginChallenge(Long userId) {
        String challenge = UUID.randomUUID().toString();
        String expirationDate = format(addMinutes(new Date(), CHALLENGE_EXPIRY_MINUTES), DATE_FORMAT);
        jdbcTemplate.update(DELETE_MFA_CHALLENGE_BY_USER_ID_QUERY, Map.of("userId", userId));
        jdbcTemplate.update(INSERT_MFA_CHALLENGE_QUERY,
                Map.of("userId", userId, "challenge", challenge, "expirationDate", expirationDate));
        return challenge;
    }

    /**
     * Completes login MFA per the contract. The expiry check lives in the SQL
     * ({@code expiration_date > NOW()}), so expired, consumed, and forged challenges are
     * indistinguishable to the caller — one neutral message for all three (NFR-SEC-7).
     */
    @Override
    @Transactional
    public TotpVerification verifyLoginChallenge(String challenge, String code) {
        List<Long> userIds = jdbcTemplate.queryForList(SELECT_USER_ID_BY_LIVE_CHALLENGE_QUERY,
                Map.of("challenge", challenge == null ? "" : challenge), Long.class);
        if (userIds.isEmpty()) {
            throw new ApiException("This sign-in attempt has expired. Please log in again.");
        }
        Long userId = userIds.getFirst();
        TotpCredential credential = findCredential(userId);
        if (credential == null || !credential.confirmed()) {
            // Should not happen (challenges are only minted for TOTP users), but fail closed.
            throw new ApiException("This sign-in attempt has expired. Please log in again.");
        }
        boolean usedRecoveryCode = false;
        if (!TotpUtils.verifyCode(credential.secret(), code)) {
            if (!consumeRecoveryCode(userId, code)) {
                // Wrong code: keep the challenge alive so the user can retry until expiry.
                throw new ApiException("That code didn't match. Check your authenticator app and try again.");
            }
            usedRecoveryCode = true;
        }
        jdbcTemplate.update(DELETE_MFA_CHALLENGE_BY_CHALLENGE_QUERY, Map.of("challenge", challenge));
        return new TotpVerification(userId, usedRecoveryCode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countUnusedRecoveryCodes(Long userId) {
        Long count = jdbcTemplate.queryForObject(COUNT_UNUSED_RECOVERY_CODES_QUERY, Map.of("userId", userId), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Replaces the user's recovery codes with {@value #RECOVERY_CODE_COUNT} fresh ones,
     * persisting only SHA-256 digests and returning the plaintext for one-time display.
     */
    private List<String> issueRecoveryCodes(Long userId) {
        jdbcTemplate.update(DELETE_RECOVERY_CODES_BY_USER_ID_QUERY, Map.of("userId", userId));
        List<String> plaintextCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String recoveryCode = TotpUtils.generateRecoveryCode();
            plaintextCodes.add(recoveryCode);
            jdbcTemplate.update(INSERT_RECOVERY_CODE_QUERY,
                    Map.of("userId", userId, "codeHash", TotpUtils.sha256Hex(normalizeRecoveryCode(recoveryCode))));
        }
        return plaintextCodes;
    }

    /**
     * Attempts to burn one unused recovery code; the UPDATE's affected-row count is the
     * verification verdict, so the check-and-consume is a single atomic statement that
     * cannot double-spend a code.
     */
    private boolean consumeRecoveryCode(Long userId, String code) {
        if (code == null || code.isBlank()) return false;
        int consumed = jdbcTemplate.update(CONSUME_RECOVERY_CODE_QUERY,
                Map.of("userId", userId, "codeHash", TotpUtils.sha256Hex(normalizeRecoveryCode(code))));
        return consumed > 0;
    }

    /**
     * Normalizes recovery-code input the way users actually type it: case-insensitive
     * and tolerant of the display hyphen/whitespace. Applied identically at issuance
     * and at verification so the hashes line up.
     */
    private static String normalizeRecoveryCode(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase();
    }

    /**
     * Loads the user's credential row, or null when none exists. queryForList avoids
     * the exception-per-miss cost of queryForObject on a path hit during every
     * TOTP login.
     */
    private TotpCredential findCredential(Long userId) {
        List<TotpCredential> rows = jdbcTemplate.query(SELECT_TOTP_CREDENTIAL_BY_USER_ID_QUERY,
                Map.of("userId", userId),
                (rs, rowNum) -> new TotpCredential(rs.getString("secret"), rs.getBoolean("confirmed")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Internal projection of one {@code totpcredentials} row. */
    private record TotpCredential(String secret, boolean confirmed) {
    }
}
