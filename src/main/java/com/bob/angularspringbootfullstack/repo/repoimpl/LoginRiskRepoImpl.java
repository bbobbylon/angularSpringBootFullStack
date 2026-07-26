package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.model.LoginContext;
import com.bob.angularspringbootfullstack.repo.LoginRiskRepo;
import com.bob.angularspringbootfullstack.rowmapper.LoginContextRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.bob.angularspringbootfullstack.query.LoginRiskQuery.SELECT_RECENT_LOGIN_CONTEXTS_BY_USER_ID_QUERY;
import static java.util.Map.of;

/**
 * JDBC implementation of {@link LoginRiskRepo}.
 *
 * <p>Follows the same {@link NamedParameterJdbcTemplate} pattern as every other repository here
 * (named binds, static-imported query constants, a dedicated {@code RowMapper}).
 *
 * <p><b>Fail-open on read errors, by design.</b> A failure to read the history is swallowed to an
 * empty list rather than propagated. This method sits directly on the login path, and the account
 * has <em>already passed its first factor</em> by the time it runs; letting a transient query
 * failure throw would convert a healthy authentication into a 500 — precisely the class of bug
 * that took logins down when the {@code userevents.detail} column drifted (see
 * {@link com.bob.angularspringbootfullstack.listener.NewUserEventListener}, hardened for the same
 * reason). An empty history reads as "no baseline", which the service treats as not-risky, so the
 * degraded behaviour is "sign-in proceeds without the extra check" and never "nobody can log in".
 * The failure is logged at WARN so the degradation is visible rather than silent.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class LoginRiskRepoImpl implements LoginRiskRepo {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<LoginContext> findRecentLoginContexts(Long userId, int limit) {
        try {
            return jdbcTemplate.query(
                    SELECT_RECENT_LOGIN_CONTEXTS_BY_USER_ID_QUERY,
                    of("userId", userId, "limit", limit),
                    new LoginContextRowMapper());
        } catch (Exception exception) {
            log.warn("[LOGIN-RISK] Could not read sign-in history for userId={} — treating this login as " +
                            "having no baseline (anomaly check skipped, login proceeds). Cause: {}",
                    userId, exception.getMessage());
            return List.of();
        }
    }
}
