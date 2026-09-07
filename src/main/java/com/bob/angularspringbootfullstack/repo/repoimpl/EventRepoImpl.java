package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;
import com.bob.angularspringbootfullstack.rowmapper.UserEventRowMapper;
import com.bob.angularspringbootfullstack.repo.EventRepo;
import com.bob.angularspringbootfullstack.utils.AuditHashChain;
import com.bob.angularspringbootfullstack.utils.AuditHashChainWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;

import static com.bob.angularspringbootfullstack.query.EventQuery.*;
import static java.util.Map.entry;
import static java.util.Map.of;

/**
 * JDBC implementation of {@link EventRepo}.
 *
 * <p>Uses {@link NamedParameterJdbcTemplate} for all queries so SQL parameters
 * are matched by name (e.g. {@code :id}, {@code :email}) rather than position —
 * this prevents ordering mistakes when a query has several parameters.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class EventRepoImpl implements EventRepo {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Serializes read-last-hash-then-insert so two concurrent audit writes can never both chain
     * from the same previous hash (which would fork the chain rather than extend it). Only
     * guarantees this within a single JVM — an accepted, documented limitation identical to the
     * rate limiter's in-process bucket stores (FUTURE-ENHANCEMENTS §2.4): this application already
     * runs as exactly one instance in production (Cloud Run {@code max-instances: 1}), so there is
     * no second instance today that could race this one. Revisit if that ever changes.
     */
    private static final Object USEREVENTS_HASH_LOCK = new Object();

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<UserEvent> getEventsByUserId(Long userId) {
        return jdbcTemplate.query(SELECT_EVENTS_BY_USER_ID_QUERY, of("id", userId), new UserEventRowMapper());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<UserEvent> getEventsByUserId(Long userId, int page, int size) {
        return jdbcTemplate.query(
                SELECT_EVENTS_BY_USER_ID_PAGINATED_QUERY,
                of("id", userId, "size", size, "offset", page * size),
                new UserEventRowMapper());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countEventsByUserId(Long userId) {
        Long count = jdbcTemplate.queryForObject(COUNT_EVENTS_BY_USER_ID_QUERY, of("id", userId), Long.class);
        return count != null ? count : 0L;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countRecentFailuresByEmail(String email, LocalDateTime since) {
        Long count = jdbcTemplate.queryForObject(
                COUNT_RECENT_FAILURES_BY_EMAIL_QUERY,
                of("email", email, "since", since),
                Long.class);
        return count != null ? count : 0L;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note: {@code eventType.toString()} produces the enum constant name
     * (e.g. {@code "LOGIN_ATTEMPT_SUCCESS"}), which must match a {@code type}
     * value in the {@code events} reference table or the insert will fail.
     */
    @Override
    public void addUserEvent(Long userId, EventType eventType, String device, String ipAddress) {
        jdbcTemplate.update(INSERT_EVENT_BY_USER_ID_QUERY, of("user_id", userId, "type", eventType.toString(), "device", device, "ipAddress", ipAddress));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addUserEvent(String email, EventType eventType, String device, String ipAddress) {
        jdbcTemplate.update(INSERT_EVENT_BY_USER_ID_QUERY, of("email", email, "type", eventType.toString(), "device", device, "ipAddress", ipAddress));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses a {@link MapSqlParameterSource} rather than {@code Map.of} because {@code detail} may
     * be {@code null} (most event types carry none), and {@code Map.of} throws on null values.
     *
     * <p>Also computes this row's link in the {@code userevents} tamper-evidence hash chain
     * (FUTURE-ENHANCEMENTS §3.1) via {@link AuditHashChainWriter} — see
     * {@link #USEREVENTS_HASH_LOCK} for why the read-then-insert it performs is synchronized, and
     * {@link AuditHashChain} for the digest itself. The {@code userId} lookup happens before that
     * critical section starts: it doesn't depend on the chain's state, so there's no reason to
     * hold {@link #USEREVENTS_HASH_LOCK} — a JVM-wide lock this method's only caller,
     * {@code NewUserEventListener}, acquires on every audit event the application raises,
     * including every login attempt — for a round trip that doesn't need it.
     */
    @Override
    public void addUserEvent(String email, EventType eventType, String device, String ipAddress, String detail) {
        Long userId = jdbcTemplate.queryForObject(SELECT_USER_ID_BY_EMAIL_QUERY, of("email", email), Long.class);
        LocalDateTime createdAt = LocalDateTime.now().withNano(0);
        AuditHashChainWriter.writeChainedRow(jdbcTemplate, USEREVENTS_HASH_LOCK, SELECT_LATEST_USEREVENT_HASH_QUERY,
                new Object[]{userId, eventType.toString(), device, ipAddress, detail, createdAt},
                hash -> {
                    MapSqlParameterSource params = new MapSqlParameterSource()
                            .addValue("email", email)
                            .addValue("type", eventType.toString())
                            .addValue("device", device)
                            .addValue("ipAddress", ipAddress)
                            .addValue("detail", detail)
                            .addValue("createdAt", createdAt)
                            .addValue("hash", hash);
                    jdbcTemplate.update(INSERT_EVENT_WITH_DETAIL_BY_EMAIL_QUERY, params);
                });
    }
}