package com.bob.angularspringbootfullstack.event;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.context.ApplicationEvent;

/**
 * A Spring application event that signals something noteworthy just happened on an
 * organization — the organization-scoped counterpart to {@link NewUserEvent}.
 *
 * <p>{@link com.bob.angularspringbootfullstack.controller.OrganizationController} and
 * {@link com.bob.angularspringbootfullstack.controller.OrganizationInviteController} publish this
 * after every mutation (create/rename/status/profile/membership/invite).
 * {@link com.bob.angularspringbootfullstack.listener.NewOrganizationEventListener} picks it up and
 * writes the corresponding audit row to the {@code organizationevents} table.
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class NewOrganizationEvent extends ApplicationEvent {
    private final Long organizationId;
    /**
     * The acting administrator's user id, or {@code null} when the action has no human actor
     * (there is none today, but the field stays nullable to match
     * {@code organizationevents.actor_user_id}'s {@code ON DELETE SET NULL} shape).
     */
    private final Long actorUserId;
    private final EventType eventType;
    /** Optional free-form context, e.g. the affected member's email on a membership event. */
    private final String detail;

    /**
     * Creates a new event for the given organization and action type, with no extra detail.
     *
     * @param organizationId the organization the action occurred on
     * @param actorUserId    the acting administrator's user id
     * @param type           the category of action that occurred
     */
    public NewOrganizationEvent(Long organizationId, Long actorUserId, EventType type) {
        this(organizationId, actorUserId, type, null);
    }

    /**
     * Creates a new event carrying an extra {@code detail} value for the audit row.
     *
     * @param organizationId the organization the action occurred on
     * @param actorUserId    the acting administrator's user id
     * @param type           the category of action that occurred
     * @param detail         free-form context; may be {@code null}
     */
    public NewOrganizationEvent(Long organizationId, Long actorUserId, EventType type, String detail) {
        super(organizationId);
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.eventType = type;
        this.detail = detail;
    }
}
