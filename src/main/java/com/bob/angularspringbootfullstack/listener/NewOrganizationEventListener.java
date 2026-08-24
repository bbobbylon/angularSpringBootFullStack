package com.bob.angularspringbootfullstack.listener;

import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link NewOrganizationEvent}s published anywhere in the application and writes an
 * audit row to the {@code organizationevents} table — the organization-scoped counterpart to
 * {@link NewUserEventListener}.
 *
 * <p><b>Audit writes are best-effort and must never break the mutation that triggered them</b>,
 * for the identical reason {@link NewUserEventListener} swallows its own failures: Spring's
 * default event multicaster invokes listeners synchronously on the publishing thread, so an
 * exception here would propagate back into the controller action (creating an organization,
 * adding a member, redeeming an invite…) and turn a persistence hiccup into a user-facing 500.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NewOrganizationEventListener {
    private final OrganizationService organizationService;

    /**
     * Persists an audit entry for the given organization event.
     *
     * <p>Never rethrows — see this class's Javadoc for why. The organization mutation that
     * triggered the event still succeeds; the cost of a persistence hiccup degrades to a missing
     * audit row rather than a broken app.
     *
     * @param event the published event carrying the organization id, actor, type, and detail
     */
    @EventListener
    public void onNewOrganizationEvent(NewOrganizationEvent event) {
        log.info("NewOrganizationEvent received for organization {}: {}", event.getOrganizationId(), event.getEventType());
        try {
            organizationService.recordOrganizationEvent(event.getOrganizationId(), event.getActorUserId(), event.getEventType(), event.getDetail());
        } catch (Exception ex) {
            log.error("Failed to persist audit event {} for organization {} — continuing without it. Cause: {}",
                    event.getEventType(), event.getOrganizationId(), ex.getMessage(), ex);
        }
    }
}
