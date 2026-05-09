package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;

import java.util.Collection;

/**
 * Data-access contract for reading and writing user audit events.
 *
 * <p>Implementations write to the {@code userevents} table (the dynamic log)
 * and read back joined rows that include the human-readable type and description
 * from the {@code events} reference table.
 */
public interface EventRepo {

    /**
     * Returns every audit entry for the given user, ordered newest first.
     *
     * @param userId the primary key of the user whose history to fetch
     * @return a collection of fully-resolved {@link UserEvent} objects
     */
    Collection<UserEvent> getEventsByUserId(Long userId);

    /**
     * Records a new audit entry for the user identified by their email address.
     *
     * <p>The email is resolved to a user ID inside the SQL query, so no
     * separate lookup is required by the caller.
     *
     * @param email     the email of the user who triggered the event
     * @param eventType the category of action that occurred
     * @param device    the OS/browser/device string parsed from the User-Agent header
     * @param ipAddress the originating IP address
     */
    void addUserEvent(String email, EventType eventType, String device, String ipAddress);

    /**
     * Records a new audit entry for the user identified by their primary key.
     *
     * <p>Prefer this overload when the user ID is already available (e.g. from a
     * JWT claim) to avoid the extra database subquery that the email-based variant
     * performs.
     *
     * @param userId    the primary key of the user who triggered the event
     * @param eventType the category of action that occurred
     * @param device    the OS/browser/device string parsed from the User-Agent header
     * @param ipAddress the originating IP address
     */
    void addUserEvent(Long userId, EventType eventType, String device, String ipAddress);
}
