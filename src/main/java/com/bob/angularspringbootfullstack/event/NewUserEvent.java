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
     * Creates a new event for the given user and action type.
     *
     * @param email the email of the user who triggered the action
     * @param type  the category of action that occurred
     */
    public NewUserEvent(String email, EventType type) {
        super(email);
        this.email = email;
        this.eventType = type;
    }
}