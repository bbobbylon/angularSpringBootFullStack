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

@Repository
@RequiredArgsConstructor
@Slf4j
public class EventRepoImpl implements EventRepo {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Collection<UserEvent> getEventsByUserId(Long userId) {
        return jdbcTemplate.query(SELECT_EVENTS_BY_USER_ID_QUERY, of("id", userId), new UserEventRowMapper());
    }

    @Override
    public void addUserEvent(Long userId, EventType eventType, String device, String ipAddress) {
        jdbcTemplate.update(INSERT_EVENT_BY_USER_ID_QUERY, of("user_id", userId, "type", eventType.toString(), "device", device, "ipAddress", ipAddress));

    }

    @Override
    public void addUserEvent(String email, EventType eventType, String device, String ipAddress) {
        jdbcTemplate.update(INSERT_EVENT_BY_USER_ID_QUERY, of("email", email, "type", eventType.toString(), "device", device, "ipAddress", ipAddress));

    }
}