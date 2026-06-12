package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.RefreshSession;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Business contract for the stateful half of the hybrid token model (plan.md M5,
 * SRS FR-JWT-5): server-tracked refresh sessions with rotation, reuse detection, and
 * user-visible device management.
 *
 * <p>This is the single token-issuance seam for the whole application. Every flow that
 * ends in "hand the user a token pair" — password login, SMS and TOTP verification,
 * federated login, password change, refresh — calls {@link #issueTokenPair} or
 * {@link #rotate} so that no JWT can exist without a corresponding session row. That
 * invariant is what makes the Security Center's device list complete and "revoke"
 * actually mean something.
 *
 * <p>Division of labor with {@code TokenProvider}: the provider signs and verifies JWTs
 * (stateless); this service owns the {@code refreshsessions} table and the rotation
 * policy (stateful). Access tokens are never checked against the store — they stay
 * DB-free on every request (NFR-PERF-2) and simply age out within 30 minutes after a
 * revocation.
 */
public interface SessionService {

    /**
     * One issued token pair plus the user it belongs to, so callers can build their
     * response without re-fetching.
     *
     * @param accessToken  the 30-minute stateless access JWT (carries the session family as {@code sid})
     * @param refreshToken the 5-day refresh JWT (carries {@code jti} + family)
     * @param user         the authenticated user the pair was minted for
     */
    record TokenPair(String accessToken, String refreshToken, UserDTO user) {
    }

    /**
     * Opens a NEW session (a fresh family) for a fully authenticated principal and
     * returns its first token pair. Captures device and IP from the live request so
     * the Security Center can show where the session was opened.
     *
     * @param userPrincipal the fully authenticated user (all factors satisfied)
     * @param request       the live request, for device/IP capture
     * @return the new session's token pair
     */
    TokenPair issueTokenPair(UserPrincipal userPrincipal, HttpServletRequest request);

    /**
     * Exchanges a presented refresh token for a rotated pair: the old token's session
     * row is retired ({@code superseded}) and a new row in the SAME family is created,
     * so the device list shows one continuously-updated session rather than a new entry
     * per refresh.
     *
     * <p>Reuse detection (FR-JWT-5): a refresh token whose row is already superseded or
     * revoked is evidence of theft — either an attacker or the legitimate user is
     * replaying a retired token, and the server cannot tell which. The entire family is
     * revoked, a TOKEN_REUSE_DETECTED audit event is recorded, and the caller is forced
     * back through a first factor.
     *
     * @param refreshToken the raw refresh JWT presented by the client
     * @param request      the live request, for device/IP refresh and audit context
     * @return the rotated token pair (new access AND new refresh token)
     */
    TokenPair rotate(String refreshToken, HttpServletRequest request);

    /**
     * Lists the user's live sessions (one per family, newest activity first) for the
     * Security Center's device list.
     *
     * @param userId the authenticated user's id
     * @return active, unexpired, unrevoked sessions
     */
    List<RefreshSession> listSessions(Long userId);

    /**
     * Revokes one of the caller's own sessions. Ownership is enforced in the SQL
     * predicate — a family id belonging to another user updates nothing and fails
     * identically to a nonexistent one.
     *
     * @param userId the authenticated user's id
     * @param family the session (family) to revoke
     */
    void revokeSession(Long userId, String family);

    /**
     * "Log out everywhere else": revokes every session except the caller's current one.
     *
     * @param userId        the authenticated user's id
     * @param currentFamily the family from the caller's own access token (kept alive)
     * @return how many sessions were revoked
     */
    int revokeOtherSessions(Long userId, String currentFamily);

    /**
     * Revokes ALL of a user's sessions — used on password change so the session store
     * agrees with the {@code passwordChangedAt} check that already invalidates every
     * outstanding JWT (FR-JWT-6); the caller then opens a fresh session for the user.
     *
     * @param userId the user whose sessions are cleared
     */
    void revokeAllSessions(Long userId);
}
