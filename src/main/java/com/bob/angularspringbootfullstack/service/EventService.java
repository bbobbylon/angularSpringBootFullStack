package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;

import java.util.Collection;

/**
 * Service contract for reading and recording user audit events.
 *
 * <p>Acts as the boundary between the web layer and the data layer — controllers
 * call methods here rather than touching
 * {@link com.bob.angularspringbootfullstack.repo.EventRepo} directly, which
 * keeps the HTTP layer decoupled from SQL details and makes the service easier
 * to test in isolation.
 */
public interface EventService {

    /**
     * Returns every recorded audit entry for the given user, newest first.
     *
     * @param userId the primary key of the user whose activity to retrieve
     * @return a collection of fully-resolved {@link UserEvent} objects
     */
    Collection<UserEvent> getEventsByUserId(Long userId);

    /**
     * Records an audit entry for the user identified by their primary key.
     *
     * @param userId    the primary key of the user who triggered the action
     * @param eventType the category of action that occurred
     * @param device    the OS/browser/device string parsed from the User-Agent header
     * @param ipAddress the originating IP address
     */
    void addUserEvent(Long userId, EventType eventType, String device, String ipAddress);

    /**
     * Records an audit entry for the user identified by their email address.
     *
     * <p>Prefer the ID-based overload when the user ID is already available —
     * the email-based SQL variant performs an extra subquery to resolve the ID.
     *
     * @param email     the email of the user who triggered the action
     * @param eventType the category of action that occurred
     * @param device    the OS/browser/device string parsed from the User-Agent header
     * @param ipAddress the originating IP address
     */
    void addUserEvent(String email, EventType eventType, String device, String ipAddress);
}
