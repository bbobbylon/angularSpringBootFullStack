package com.bob.angularspringbootfullstack.event;

import com.bob.angularspringbootfullstack.enumeration.EventType;
import lombok.Data;
import org.springframework.context.ApplicationEvent;

@Data
public class NewUserEvent extends ApplicationEvent {
    private final EventType eventType;
    private final String email;

    public NewUserEvent(String email, EventType type) {
        super(email);
        this.email = email;
        this.eventType = type;
    }
}