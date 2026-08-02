package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.model.DailyEventCount;
import com.bob.angularspringbootfullstack.model.MfaAdoption;
import com.bob.angularspringbootfullstack.model.RestrictedAccount;
import com.bob.angularspringbootfullstack.model.SessionActivity;
import com.bob.angularspringbootfullstack.model.SuspiciousLoginEntry;
import com.bob.angularspringbootfullstack.repo.SecurityDashboardRepo;
import com.bob.angularspringbootfullstack.rowmapper.RestrictedAccountRowMapper;
import com.bob.angularspringbootfullstack.rowmapper.SuspiciousLoginEntryRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.COUNT_ACTIVE_SESSIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.COUNT_RECENT_SUSPICIOUS_LOGINS_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.COUNT_RESTRICTED_ACCOUNTS_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.COUNT_SECURITY_EVENTS_SINCE_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.SCOPE_MARKER;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.SCOPE_PREDICATE;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.SELECT_DAILY_LOGIN_OUTCOMES_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.SELECT_MFA_ADOPTION_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.SELECT_RECENT_SUSPICIOUS_LOGINS_QUERY;
import static com.bob.angularspringbootfullstack.query.SecurityDashboardQuery.SELECT_RESTRICTED_ACCOUNTS_QUERY;

/**
 * JDBC implementation of {@link SecurityDashboardRepo} (SRS FR-TPF-2).
 *
 * <p>Follows the project's standard shape — {@link NamedParameterJdbcTemplate}, named binds,
 * static-imported query constants, dedicated row mappers — with two additions specific to this
 * dashboard.
 *
 * <h3>1. Scope splicing</h3>
 * {@link #scoped} replaces the {@code SCOPE_MARKER} token in each query with either the
 * organization predicate or nothing. The spliced fragment is a compile-time constant; the
 * organization ids themselves are bound as a named parameter, so no request data ever reaches the
 * SQL text. The alternative of one always-present predicate (e.g. {@code OR :unscoped = TRUE})
 * would keep the SQL static but make the optimiser unable to use the {@code userorganizations}
 * index, turning every panel into a full scan for the common unscoped case.
 *
 * <h3>2. Non-fatal reads</h3>
 * Every method swallows failures to an empty result and logs at WARN, the same posture as
 * {@link LoginRiskRepoImpl} and {@code NewUserEventListener}. The reasoning here is different from
 * theirs, though, and worth stating: those two sit on the login path, where a throw would break
 * authentication. This is a read-only reporting screen, where a throw would break *five working
 * panels* to report that a sixth could not be read. Degrading one panel to empty and logging is
 * strictly more useful to the administrator looking at it. The cost — that an empty panel is
 * ambiguous between "nothing happened" and "could not read" — is accepted because the log
 * disambiguates it for the person who can act, and because "nothing happened" is by far the more
 * common cause on this screen.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class SecurityDashboardRepoImpl implements SecurityDashboardRepo {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Long> countSecurityEvents(LocalDateTime since, Collection<Long> organizationIds) {
        return read("event counts", Map.<String, Long>of(), () -> {
            // Collected into a LinkedHashMap rather than via Collectors.toMap because the latter
            // throws on a null key — and a NULL event type, while it should be impossible given
            // the events table's CHECK constraint, is not worth turning a reporting panel into an
            // exception over.
            Map<String, Long> counts = new LinkedHashMap<>();
            jdbcTemplate.query(
                    scoped(COUNT_SECURITY_EVENTS_SINCE_QUERY, organizationIds),
                    parameters(organizationIds).addValue("since", since),
                    (resultSet, rowNum) -> counts.put(resultSet.getString("event_type"), resultSet.getLong("total")));
            return counts;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SuspiciousLoginEntry> findRecentSuspiciousLogins(LocalDateTime since, int page, int size,
                                                                 Collection<Long> organizationIds) {
        return read("suspicious logins", List.of(), () -> jdbcTemplate.query(
                scoped(SELECT_RECENT_SUSPICIOUS_LOGINS_QUERY, organizationIds),
                parameters(organizationIds)
                        .addValue("since", since)
                        .addValue("size", size)
                        // Computed here rather than taken from the caller so the offset can never
                        // disagree with the page/size the count is derived from.
                        .addValue("offset", (long) page * size),
                new SuspiciousLoginEntryRowMapper()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countRecentSuspiciousLogins(LocalDateTime since, Collection<Long> organizationIds) {
        // Falls back to 0 on failure like every other read here. A zero total renders as a single
        // page, which degrades the pager rather than the table it belongs to — consistent with the
        // class's "one broken panel must not break the other five" posture.
        return read("suspicious login count", 0L, () -> jdbcTemplate.queryForObject(
                scoped(COUNT_RECENT_SUSPICIOUS_LOGINS_QUERY, organizationIds),
                parameters(organizationIds).addValue("since", since),
                Long.class));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<DailyEventCount> findDailyLoginOutcomes(LocalDateTime since, Collection<Long> organizationIds) {
        return read("login trend", List.of(), () -> jdbcTemplate.query(
                scoped(SELECT_DAILY_LOGIN_OUTCOMES_QUERY, organizationIds),
                parameters(organizationIds).addValue("since", since),
                (resultSet, rowNum) -> {
                    // DATE(...) comes back as a java.sql.Date; toLocalDate() is the only conversion
                    // that ignores the JVM's time zone, which matters because a UTC-stored event
                    // near midnight would otherwise land on the wrong bar of the chart.
                    Date day = resultSet.getDate("day");
                    return new DailyEventCount(
                            day == null ? null : day.toLocalDate(),
                            resultSet.getString("event_type"),
                            resultSet.getLong("total"));
                }));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RestrictedAccount> findRestrictedAccounts(int page, int size, Collection<Long> organizationIds) {
        return read("restricted accounts", List.of(), () -> jdbcTemplate.query(
                scoped(SELECT_RESTRICTED_ACCOUNTS_QUERY, organizationIds),
                parameters(organizationIds)
                        .addValue("size", size)
                        .addValue("offset", (long) page * size),
                new RestrictedAccountRowMapper()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countRestrictedAccounts(Collection<Long> organizationIds) {
        return read("restricted account count", 0L, () -> jdbcTemplate.queryForObject(
                scoped(COUNT_RESTRICTED_ACCOUNTS_QUERY, organizationIds),
                parameters(organizationIds),
                Long.class));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MfaAdoption findMfaAdoption(Collection<Long> organizationIds) {
        MfaAdoption zeroes = new MfaAdoption(0, 0, 0, 0);
        return read("MFA adoption", zeroes, () -> jdbcTemplate.queryForObject(
                scoped(SELECT_MFA_ADOPTION_QUERY, organizationIds),
                parameters(organizationIds),
                (resultSet, rowNum) -> new MfaAdoption(
                        resultSet.getLong("total_users"),
                        resultSet.getLong("totp_users"),
                        resultSet.getLong("sms_users"),
                        resultSet.getLong("single_factor_users"))));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SessionActivity findSessionActivity(Collection<Long> organizationIds) {
        SessionActivity zeroes = new SessionActivity(0, 0);
        return read("session activity", zeroes, () -> jdbcTemplate.queryForObject(
                scoped(COUNT_ACTIVE_SESSIONS_QUERY, organizationIds),
                parameters(organizationIds),
                (resultSet, rowNum) -> new SessionActivity(
                        resultSet.getLong("active_sessions"),
                        resultSet.getLong("accounts_with_sessions"))));
    }

    /**
     * Substitutes the scope marker in a dashboard query.
     *
     * @param query           a query containing {@code SCOPE_MARKER}
     * @param organizationIds null for an unscoped caller, otherwise the organizations to restrict to
     * @return the query with the organization predicate spliced in, or removed
     */
    private static String scoped(String query, Collection<Long> organizationIds) {
        return query.replace(SCOPE_MARKER, organizationIds == null ? "" : SCOPE_PREDICATE);
    }

    /**
     * Builds the parameter source, binding {@code organizationIds} only when the query will
     * actually reference it.
     *
     * <p>Binding it unconditionally would be harmless with most drivers but is exactly the kind of
     * "works today" detail that breaks on a version bump; binding only what the spliced SQL
     * contains keeps the two in step by construction.
     *
     * @param organizationIds null for an unscoped caller, otherwise the organizations to restrict to
     * @return a parameter source ready for the query's remaining binds
     */
    private static MapSqlParameterSource parameters(Collection<Long> organizationIds) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (organizationIds != null) {
            parameters.addValue("organizationIds", organizationIds);
        }
        return parameters;
    }

    /**
     * Runs one dashboard read, degrading to a neutral value if it fails.
     *
     * @param panel    a human name for the panel, used in the warning
     * @param fallback what to return when the read fails — always an empty/zero value, never
     *                 anything that could be mistaken for real data
     * @param read     the query to run
     * @param <T>      the panel's result type
     * @return the query result, or {@code fallback} when it threw
     */
    private <T> T read(String panel, T fallback, Supplier<T> read) {
        try {
            T result = read.get();
            return result == null ? fallback : result;
        } catch (Exception exception) {
            log.warn("[SECURITY-DASHBOARD] Could not load the {} panel — rendering it empty. Cause: {}",
                    panel, exception.getMessage());
            return fallback;
        }
    }
}
