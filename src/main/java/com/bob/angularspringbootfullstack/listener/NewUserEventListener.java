package com.bob.angularspringbootfullstack.listener;

import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.service.EventService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static com.bob.angularspringbootfullstack.utils.RequestUtils.getDevice;
import static com.bob.angularspringbootfullstack.utils.RequestUtils.getIpAddress;

/**
 * Listens for {@link NewUserEvent}s published anywhere in the application, writes an audit row to
 * the {@code userevents} table, and increments a live Micrometer counter for the same event.
 *
 * <p>Spring's {@code @EventListener} routes every published {@link NewUserEvent}
 * to {@link #onNewUserEvent} automatically — no manual wiring is needed.  The
 * {@link HttpServletRequest} is injected so the listener can capture the
 * originating IP address and device info at the moment the event fires, without
 * every caller having to pass those details explicitly.
 *
 * <p><b>Why the counter lives here.</b> Every {@code EventType} the application raises — login
 * success/failure, suspicious-login step-up, MFA/TOTP/passkey enrollment, token reuse detection,
 * federated login, and every other entry in {@code EventType} — already funnels through this one
 * {@code @EventListener} exactly once. Tagging a single counter by event type here, rather than
 * instrumenting each call site individually, means a new {@code EventType} is automatically
 * counted the moment something publishes it — there is no second place that can be forgotten.
 * The counter is exposed live at {@code /actuator/metrics/user.events.total} (admin-authenticated,
 * see {@code SecurityConfig}'s {@code /actuator/**} matcher), complementing the security
 * dashboard's DB-query-driven historical view with a real-time one Actuator/Micrometer can also
 * export to Prometheus or CloudWatch without a schema or a query.
 */
@Data
@Component
@RequiredArgsConstructor
@Slf4j
public class NewUserEventListener {
    private final EventService eventService;
    private final HttpServletRequest request;
    private final MeterRegistry meterRegistry;

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
     * <p>The counter increments <em>before</em> the audit-write attempt and outside its try/catch: an
     * in-memory Micrometer counter cannot fail the way a database insert can, and incrementing it
     * first means the metric still reflects that the event genuinely occurred even on the rare run
     * where the audit write itself throws — the two concerns are independent by design (see this
     * class's own Javadoc for why the counter lives on this listener at all).
     *
     * @param event the published event carrying the user's email and event type
     */
    @EventListener
    public void onNewUserEvent(NewUserEvent event) {
        log.info("NewUserEvent received for email: {}", event.getEmail());
        meterRegistry.counter("user.events.total", "type", event.getEventType().name()).increment();
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
