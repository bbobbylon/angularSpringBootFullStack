package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.model.UserEvent;
import com.bob.angularspringbootfullstack.repo.EventRepo;
import com.bob.angularspringbootfullstack.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {
    private final EventRepo eventRepo;

    @Override
    public Collection<UserEvent> getEventsByUserId(Long userId) {
        return eventRepo.getEventsByUserId(userId);
    }

    @Override
    public void addUserEvent(Long userId, EventType eventType, String device, String ipAddress) {
        eventRepo.addUserEvent(userId, eventType, ipAddress, device);

    }

    @Override
    public void addUserEvent(String email, EventType eventType, String device, String ipAddress) {
        eventRepo.addUserEvent(email, eventType, ipAddress, device);

    }
}
