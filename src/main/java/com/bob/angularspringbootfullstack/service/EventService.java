package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;

import java.util.Collection;

public interface EventService {
    Collection<UserEvent> getEventsByUserId(Long userId);


    void addUserEvent(Long userId, EventType eventType, String device, String ipAddress);

    void addUserEvent(String email, EventType eventType, String device, String ipAddress);
}
