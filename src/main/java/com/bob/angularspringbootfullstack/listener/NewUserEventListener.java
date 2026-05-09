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

/**
 * Listens for {@link NewUserEvent}s published anywhere in the application and
 * writes an audit row to the {@code userevents} table.
 *
 * <p>Spring's {@code @EventListener} routes every published {@link NewUserEvent}
 * to {@link #onNewUserEvent} automatically — no manual wiring is needed.  The
 * {@link HttpServletRequest} is injected so the listener can capture the
 * originating IP address and device info at the moment the event fires, without
 * every caller having to pass those details explicitly.
 */
@Data
@Component
@RequiredArgsConstructor
@Slf4j
public class NewUserEventListener {
    private final EventService eventService;
    private final HttpServletRequest request;

    /**
     * Persists an audit entry for the given event.
     *
     * <p>Extracts the device and IP address from the live HTTP request so the
     * log captures exactly where and how the action was triggered.  Called
     * automatically by Spring whenever a {@link NewUserEvent} is published.
     *
     * @param event the published event carrying the user's email and event type
     */
    @EventListener
    public void onNewUserEvent(NewUserEvent event) {
        log.info("NewUserEvent received for email: {}", event.getEmail());
        eventService.addUserEvent(event.getEmail(), event.getEventType(), getDevice(request), getIpAddress(request));
    }
}
