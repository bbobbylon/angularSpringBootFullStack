package com.bob.angularspringbootfullstack.query;

public class EventQuery {
    public static final String SELECT_EVENTS_BY_USER_ID_QUERY = "SELECT " +
            "uev.id, uev.device, uev.ip_address, ev.type, ev.description, uev.created_at " +
            "FROM events ev JOIN userevents uev ON ev.id = uev.event_id " +
            "JOIN users u ON u.id = uev.user_id WHERE u.id = :id " +
            "ORDER BY uev.created_at DESC"; //LIMIT 10 -- ADD LATER
    public static final String INSERT_EVENT_BY_USER_ID_QUERY = "INSERT INTO userevents (user_id, event_id, device, ip_address) " +
            "VALUES ((SELECT id FROM users WHERE email = :email), (SELECT id FROM events WHERE type = :type), :device, :ipAddress)";
}
