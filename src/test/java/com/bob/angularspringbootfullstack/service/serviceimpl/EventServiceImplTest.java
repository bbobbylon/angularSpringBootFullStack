package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.repo.EventRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.bob.angularspringbootfullstack.enumeration.EventType.FEDERATED_LOGIN;
import static com.bob.angularspringbootfullstack.enumeration.EventType.LOGIN_ATTEMPT_SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the FR-FED-5 audit-detail thread: the {@code detail} value (used to record which
 * federated provider authenticated a user) must travel intact from the published event down to the
 * repository insert. No Spring context and no database — the repo is mocked.
 * <p>
 * Two links are locked in:
 * <ol>
 *   <li>{@link NewUserEvent} carries {@code detail}: the 3-arg constructor stores it, and the legacy
 *       2-arg constructor defaults it to {@code null} so every existing publisher is unaffected.</li>
 *   <li>{@link EventServiceImpl#addUserEvent(String, com.bob.angularspringbootfullstack.enumeration.EventType, String, String, String)}
 *       forwards the {@code detail} to {@link EventRepo} rather than dropping it.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class EventServiceImplTest {

    @Mock
    private EventRepo eventRepo;

    @InjectMocks
    private EventServiceImpl eventService;

    @Test
    @DisplayName("NewUserEvent: 3-arg constructor stores detail; 2-arg defaults it to null")
    void newUserEventCarriesDetail() {
        assertThat(new NewUserEvent("u@example.com", FEDERATED_LOGIN, "microsoft").getDetail())
                .isEqualTo("microsoft");
        assertThat(new NewUserEvent("u@example.com", LOGIN_ATTEMPT_SUCCESS).getDetail())
                .as("legacy 2-arg publishers must keep working with a null detail")
                .isNull();
    }

    @Test
    @DisplayName("addUserEvent(..., detail) forwards the provider detail to the repository")
    void serviceForwardsDetailToRepo() {
        eventService.addUserEvent("u@example.com", FEDERATED_LOGIN, "Windows - Chrome - Desktop", "127.0.0.1", "github");

        verify(eventRepo).addUserEvent("u@example.com", FEDERATED_LOGIN, "Windows - Chrome - Desktop", "127.0.0.1", "github");
    }
}
