package com.bob.angularspringbootfullstack.listener;

import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static com.bob.angularspringbootfullstack.utils.RequestUtils.getDevice;
import static com.bob.angularspringbootfullstack.utils.RequestUtils.getIpAddress;

@Data
@Component
@RequiredArgsConstructor
@Slf4j
public class NewUserEventListener {
    private final EventService eventService;
    private final HttpServletRequest request;

    @EventListener
    public void onNewUserEvent(NewUserEvent event) {
        log.info("NewUserEvent received for email: {}", event.getEmail());
        eventService.addUserEvent(event.getEmail(), event.getEventType(), getDevice(request), getIpAddress(request));
    }
}
