package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;
import com.bob.angularspringbootfullstack.repo.EventRepo;
import com.bob.angularspringbootfullstack.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * Default implementation of {@link EventService}.
 *
 * <p>Delegates all persistence to {@link EventRepo}, keeping this class thin.
 * Adding caching, validation, or metrics in a future iteration only requires
 * changes here — the controller and repo layers stay untouched.
 */
@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {
    private final EventRepo eventRepo;

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<UserEvent> getEventsByUserId(Long userId) {
        return eventRepo.getEventsByUserId(userId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addUserEvent(Long userId, EventType eventType, String device, String ipAddress) {
        eventRepo.addUserEvent(userId, eventType, device, ipAddress);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addUserEvent(String email, EventType eventType, String device, String ipAddress) {
        eventRepo.addUserEvent(email, eventType, device, ipAddress);
    }
}
