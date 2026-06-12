package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.RefreshSession;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.RoleService;
import com.bob.angularspringbootfullstack.service.SessionService;
import com.bob.angularspringbootfullstack.service.UserService;
import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.bob.angularspringbootfullstack.constants.Constants.DATE_FORMAT;
import static com.bob.angularspringbootfullstack.constants.Constants.REFRESH_TOKEN_EXPIRE_TIME;
import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.toUser;
import static com.bob.angularspringbootfullstack.enumeration.EventType.TOKEN_REUSE_DETECTED;
import static com.bob.angularspringbootfullstack.query.SessionQuery.*;
import static com.bob.angularspringbootfullstack.utils.RequestUtils.getDevice;
import static com.bob.angularspringbootfullstack.utils.RequestUtils.getIpAddress;
import static java.lang.System.currentTimeMillis;
import static org.apache.commons.lang3.time.DateFormatUtils.format;

/**
 * JDBC-backed implementation of the refresh-session store (plan.md M5, SRS FR-JWT-5),
 * following the service-owns-the-logic convention of {@link FederatedIdentityServiceImpl}:
 * queries centralized in {@link com.bob.angularspringbootfullstack.query.SessionQuery},
 * {@code NamedParameterJdbcTemplate} for persistence.
 *
 * <p><b>Deliberate transaction posture.</b> {@link #rotate} is intentionally NOT
 * {@code @Transactional}: the reuse-detection path must COMMIT its family revocation and
 * then throw — inside a transaction, the throw would roll the revocation back, un-punishing
 * the very replay it just detected. The rotation write pair (supersede old row, insert new
 * row) runs in fail-closed order instead: if the insert crashes after the supersede, the
 * presented token is already retired and the user simply re-authenticates — an
 * inconvenience, never a security hole. (The reverse order could briefly leave two live
 * tokens in one family.)
 *
 * <p>Rotation grants a fresh 5-day expiry (sliding sessions): an actively used device
 * stays signed in indefinitely, while an idle one ages out after 5 days — matching how
 * the refresh JWT's own {@code exp} already behaved across refreshes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionServiceImpl implements SessionService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TokenProvider tokenProvider;
    private final UserService userService;
    private final RoleService roleService;
    private final ApplicationEventPublisher eventPublisher;

    /** Maps one {@code refreshsessions} row; nullable timestamps guarded like UserRowMapper. */
    private static final RowMapper<RefreshSession> SESSION_ROW_MAPPER = (rs, rowNum) -> RefreshSession.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .family(rs.getString("family"))
            .jti(rs.getString("jti"))
            .device(rs.getString("device"))
            .ipAddress(rs.getString("ip_address"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .lastUsedAt(rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toLocalDateTime() : null)
            .expiresAt(rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toLocalDateTime() : null)
            .revoked(rs.getBoolean("revoked"))
            .superseded(rs.getBoolean("superseded"))
            .build();

    /**
     * Opens a new family per the contract: mints the (family, jti) pair, records the
     * session with the request's device/IP, and returns tokens stamped with both ids.
     */
    @Override
    public TokenPair issueTokenPair(UserPrincipal userPrincipal, HttpServletRequest request) {
        UserDTO user = userPrincipal.getUser();
        String family = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        insertSessionRow(user.getId(), family, jti, request);
        log.info("Opened refresh session family {} for user id {}", family, user.getId());
        return new TokenPair(
                tokenProvider.createAccessToken(userPrincipal, family),
                tokenProvider.createRefreshToken(userPrincipal, jti, family),
                user);
    }

    /**
     * Rotates per the contract. Validation layers, in order: JWT signature/expiry and
     * the {@code passwordChangedAt} check (both via TokenProvider — a stolen token dies
     * here exactly as it would on any API call), then the session-store verdicts:
     * unknown jti (legacy or fabricated) and reuse (superseded/revoked row).
     */
    @Override
    public TokenPair rotate(String refreshToken, HttpServletRequest request) {
        Long userId = tokenProvider.getSubject(refreshToken, request);
        if (!tokenProvider.isTokenValid(userId, refreshToken)) {
            throw new ApiException("Your session has expired. Please log in again.");
        }
        String jti = tokenProvider.getTokenId(refreshToken);
        if (jti == null) {
            // Pre-M5 token with no rotation identity — honored never, replaced by one fresh login.
            throw new ApiException("Your session needs to be renewed. Please log in again.");
        }
        RefreshSession session = findByJti(jti);
        if (session == null) {
            throw new ApiException("Your session could not be found. Please log in again.");
        }
        if (session.isSuperseded() || session.isRevoked()) {
            handleReuse(session);
            throw new ApiException("This session has been revoked for your security. Please log in again.");
        }
        // Fail-closed write order — see class Javadoc.
        jdbcTemplate.update(SUPERSEDE_SESSION_QUERY, Map.of("id", session.getId()));
        String newJti = UUID.randomUUID().toString();
        insertSessionRow(userId, session.getFamily(), newJti, request);

        UserDTO user = userService.getUserById(userId);
        UserPrincipal principal = new UserPrincipal(toUser(user), roleService.getRoleByUserId(userId));
        return new TokenPair(
                tokenProvider.createAccessToken(principal, session.getFamily()),
                tokenProvider.createRefreshToken(principal, newJti, session.getFamily()),
                user);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RefreshSession> listSessions(Long userId) {
        return jdbcTemplate.query(SELECT_ACTIVE_SESSIONS_BY_USER_QUERY, Map.of("userId", userId), SESSION_ROW_MAPPER);
    }

    /**
     * Revokes one owned session per the contract; the SQL's {@code user_id} predicate
     * is the ownership check, so zero affected rows means "not yours or not found"
     * without distinguishing which.
     */
    @Override
    public void revokeSession(Long userId, String family) {
        int revoked = jdbcTemplate.update(REVOKE_FAMILY_FOR_USER_QUERY, Map.of("family", family, "userId", userId));
        if (revoked == 0) {
            throw new ApiException("Session not found.");
        }
        log.info("User id {} revoked session family {}", userId, family);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int revokeOtherSessions(Long userId, String currentFamily) {
        int revoked = jdbcTemplate.update(REVOKE_OTHER_SESSIONS_QUERY,
                Map.of("userId", userId, "family", currentFamily == null ? "" : currentFamily));
        log.info("User id {} revoked {} other session(s)", userId, revoked);
        return revoked;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revokeAllSessions(Long userId) {
        int revoked = jdbcTemplate.update(REVOKE_ALL_SESSIONS_QUERY, Map.of("userId", userId));
        log.info("Revoked all {} session(s) for user id {}", revoked, userId);
    }

    /**
     * The reuse-detection response: revoke the whole family (committed immediately —
     * the caller throws right after) and write the TOKEN_REUSE_DETECTED audit row so
     * the user sees the incident in their activity log (FR-AUDIT-1).
     */
    private void handleReuse(RefreshSession session) {
        log.warn("Refresh token reuse detected for user id {} (family {}): revoking entire family",
                session.getUserId(), session.getFamily());
        jdbcTemplate.update(REVOKE_FAMILY_QUERY, Map.of("family", session.getFamily()));
        try {
            eventPublisher.publishEvent(new NewUserEvent(
                    userService.getUserById(session.getUserId()).getEmail(), TOKEN_REUSE_DETECTED));
        } catch (Exception exception) {
            // Auditing must never block the security response itself.
            log.error("Failed to record TOKEN_REUSE_DETECTED for user id {}: {}",
                    session.getUserId(), exception.getMessage());
        }
    }

    /**
     * Persists one session row, capturing device + IP from the live request and a fresh
     * 5-day expiry mirroring {@code REFRESH_TOKEN_EXPIRE_TIME} so the row and its JWT
     * always share the same horizon.
     */
    private void insertSessionRow(Long userId, String family, String jti, HttpServletRequest request) {
        jdbcTemplate.update(INSERT_SESSION_QUERY, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("family", family)
                .addValue("jti", jti)
                .addValue("device", getDevice(request))
                .addValue("ipAddress", getIpAddress(request))
                .addValue("expiresAt", format(new Date(currentTimeMillis() + REFRESH_TOKEN_EXPIRE_TIME), DATE_FORMAT)));
    }

    /**
     * Loads the session row for a jti, or null when none exists; queryForList avoids
     * exception-driven control flow on the hot refresh path.
     */
    private RefreshSession findByJti(String jti) {
        List<RefreshSession> rows = jdbcTemplate.query(SELECT_SESSION_BY_JTI_QUERY, Map.of("jti", jti), SESSION_ROW_MAPPER);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
