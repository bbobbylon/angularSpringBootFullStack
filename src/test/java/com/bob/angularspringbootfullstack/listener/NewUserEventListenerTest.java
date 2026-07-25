package com.bob.angularspringbootfullstack.listener;

import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;

import static com.bob.angularspringbootfullstack.enumeration.EventType.FEDERATED_LOGIN;
import static com.bob.angularspringbootfullstack.enumeration.EventType.LOGIN_ATTEMPT_SUCCESS;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NewUserEventListener} — the single seam through which every audit write in
 * the application flows (every {@code eventPublisher.publishEvent(new NewUserEvent(...))} call).
 *
 * <p>These lock in the behaviour added after the 2026-07-24 login-outage incident: because Spring's
 * default event multicaster invokes listeners <em>synchronously on the publishing thread</em>, an
 * exception thrown while persisting the audit row propagated back into the caller of
 * {@code publishEvent(...)} — {@code UserController.authenticate}/{@code recordLoginFailure} and
 * {@code OAuth2LoginSuccessHandler} — turning a failed {@code userevents} insert into an HTTP 500 for
 * <em>every</em> login (including the code that records failed logins). The listener now swallows and
 * logs any persistence failure so audit logging can never break authentication. No Spring context and
 * no database — the audit service and the HTTP request are mocked.
 */
@ExtendWith(MockitoExtension.class)
class NewUserEventListenerTest {

    @Mock
    private EventService eventService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private NewUserEventListener listener;

    @BeforeEach
    void stubRequestHeaders() {
        // getDevice()/getIpAddress() read headers off the live request; give them a real User-Agent so
        // argument-building succeeds and any simulated failure originates at the audit write itself.
        // lenient(): a future test might not reach the header reads, and that must not fail the suite.
        lenient().when(request.getHeader(anyString())).thenReturn("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    }

    @Test
    @DisplayName("A failing audit write is swallowed — onNewUserEvent must not propagate (login stays up)")
    void auditWriteFailureIsNonFatal() {
        // Reproduce the exact production failure: the userevents insert blows up on a missing column.
        doThrow(new BadSqlGrammarException("StatementCallback", "INSERT INTO userevents (...) VALUES (...)",
                new SQLException("Unknown column 'detail' in 'field list'")))
                .when(eventService).addUserEvent(anyString(), any(), any(), any(), any());

        NewUserEvent event = new NewUserEvent("u@example.com", FEDERATED_LOGIN, "microsoft");

        assertThatCode(() -> listener.onNewUserEvent(event))
                .as("a failed audit insert must never break the authentication flow that published the event")
                .doesNotThrowAnyException();

        // Prove the failure was swallowed AT the audit write (i.e. we actually reached it), not skipped.
        verify(eventService).addUserEvent(eq("u@example.com"), eq(FEDERATED_LOGIN), any(), any(), eq("microsoft"));
    }

    @Test
    @DisplayName("On success the event's email, type and detail are forwarded to the audit service")
    void forwardsEventToAuditService() {
        NewUserEvent event = new NewUserEvent("u@example.com", LOGIN_ATTEMPT_SUCCESS);

        listener.onNewUserEvent(event);

        // A legacy 2-arg event carries a null detail; it must still be forwarded (persists as NULL).
        verify(eventService).addUserEvent(eq("u@example.com"), eq(LOGIN_ATTEMPT_SUCCESS), any(), any(), isNull());
    }
}
