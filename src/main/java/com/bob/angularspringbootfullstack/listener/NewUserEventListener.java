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
     * <p><b>Audit writes are best-effort and must never break the action that triggered them.</b>
     * Spring's default event multicaster invokes listeners <em>synchronously on the publishing
     * thread</em>, so an exception thrown here propagates straight back into the caller of
     * {@code publishEvent(...)} — e.g. {@code UserController.authenticate} / {@code recordLoginFailure}
     * or {@link com.bob.angularspringbootfullstack.handler.OAuth2LoginSuccessHandler}. Every audit
     * write in the application funnels through this one listener, so a single failing insert (for
     * instance a schema drift where {@code userevents.detail} is missing on a live DB that
     * {@code schema.sql} never had run against it) would otherwise turn <em>every</em> login into an
     * HTTP 500 — including the code path that records failed logins. We therefore swallow and log any
     * failure: the user-facing action (sign-in, federated callback, profile update…) still succeeds,
     * and the cost of a persistence hiccup degrades to a missing audit row rather than a broken app.
     *
     * <p>This is intentionally <em>not</em> {@code @Async}: the listener reads the request-scoped
     * {@link HttpServletRequest} to capture the originating IP/device, and that scope does not exist
     * on a background thread.
     *
     * @param event the published event carrying the user's email and event type
     */
    @EventListener
    public void onNewUserEvent(NewUserEvent event) {
        log.info("NewUserEvent received for email: {}", event.getEmail());
        try {
            // event.getDetail() is non-null only for events that carry extra context (FR-FED-5: the
            // federated provider name); for every other event it is null and persists as a NULL column.
            eventService.addUserEvent(event.getEmail(), event.getEventType(), getDevice(request), getIpAddress(request), event.getDetail());
        } catch (Exception ex) {
            // Never rethrow: a failed audit write must not fail the user-facing action (see Javadoc).
            log.error("Failed to persist audit event {} for {} — continuing without it. Cause: {}",
                    event.getEventType(), event.getEmail(), ex.getMessage(), ex);
        }
    }
}
