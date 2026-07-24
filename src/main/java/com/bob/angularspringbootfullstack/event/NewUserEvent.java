package com.bob.angularspringbootfullstack.event;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.context.ApplicationEvent;

/**
 * A Spring application event that signals something noteworthy just happened
 * on a user account. Added another Spring annotation @EqualsAndHashCode to remove a warning
 *
 * <p>Controllers publish this via {@link org.springframework.context.ApplicationEventPublisher}
 * after every meaningful user action (login, password change, etc.).
 * {@link com.bob.angularspringbootfullstack.listener.NewUserEventListener} picks it up
 * and writes the corresponding audit row to the {@code userevents} table.
 *
 * <p>Extending {@link org.springframework.context.ApplicationEvent} is what tells
 * Spring to route it through the application event bus — without that, the
 * listener's {@code @EventListener} method would never fire.
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class NewUserEvent extends ApplicationEvent {
    private final EventType eventType;
    private final String email;
    /**
     * Optional free-form context carried into the audit row's {@code detail} column (FR-FED-5).
     * <p>
     * Currently populated only for {@code FEDERATED_LOGIN}, where it holds the provider name
     * ({@code google} | {@code github} | {@code microsoft}) so the audit trail records <em>which</em>
     * provider authenticated the user, not just that federation was used. {@code null} for every
     * other event type, which the listener/repo persist as a NULL {@code detail}.
     */
    private final String detail;

    /**
     * Creates a new event for the given user and action type, with no extra detail.
     *
     * @param email the email of the user who triggered the action
     * @param type  the category of action that occurred
     */
    public NewUserEvent(String email, EventType type) {
        this(email, type, null);
    }

    /**
     * Creates a new event carrying an extra {@code detail} value for the audit row (FR-FED-5).
     *
     * @param email  the email of the user who triggered the action
     * @param type   the category of action that occurred
     * @param detail free-form context (e.g. the federated provider name); may be {@code null}
     */
    public NewUserEvent(String email, EventType type, String detail) {
        super(email);
        this.email = email;
        this.eventType = type;
        this.detail = detail;
    }
}