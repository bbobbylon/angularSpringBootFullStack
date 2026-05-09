package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;
import com.bob.angularspringbootfullstack.rowmapper.UserEventRowMapper;
import com.bob.angularspringbootfullstack.repo.EventRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;

import static com.bob.angularspringbootfullstack.query.EventQuery.INSERT_EVENT_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.EventQuery.SELECT_EVENTS_BY_USER_ID_QUERY;
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
     * {@inheritDoc}
     */
    @Override
    public Collection<UserEvent> getEventsByUserId(Long userId) {
        return jdbcTemplate.query(SELECT_EVENTS_BY_USER_ID_QUERY, of("id", userId), new UserEventRowMapper());
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
}