package com.bob.angularspringbootfullstack.exception;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards production error hygiene: internal exception detail must not reach clients from a
 * deployed process (NFR-SEC).
 *
 * <p>The leak this prevents is easy to under-rate. {@code HandleException} populates every error
 * body with the raw {@code exception.getMessage()}, and those messages name database tables and
 * columns, framework class names, and — on the authentication paths — the identifier that was
 * looked up. That last one matters most: it would quietly undo the anti-enumeration guarantee the
 * login flow is built around, since a response that echoes "No user found by email: x@y.com"
 * distinguishes a known address from an unknown one no matter how carefully the login handler
 * phrases its own message.
 *
 * <p>The suite asserts both directions. Scrubbing in production is only half the requirement —
 * {@link #developmentProfileKeepsFullDetail} pins the other half, because a fix that silently
 * degraded local debugging would be quietly abandoned the first time someone needed a stack
 * message.
 */
class ErrorDetailScrubberTest {

    /** Stands in for the kind of text a real exception carries into the envelope. */
    private static final String LEAKY_DETAIL = "No user found by email: victim@example.com";

    private static ErrorDetailScrubber scrubber(boolean exposeDetails) {
        ErrorDetailScrubber scrubber = new ErrorDetailScrubber();
        ReflectionTestUtils.setField(scrubber, "exposeDetails", exposeDetails);
        return scrubber;
    }

    private static HttpResponse errorBody() {
        return HttpResponse.builder()
                .message("Something went wrong.")
                .reason(LEAKY_DETAIL)
                .devMessage(LEAKY_DETAIL)
                .build();
    }

    /** Drives the advice exactly as Spring would, with a response carrying the given status. */
    private static Object write(ErrorDetailScrubber scrubber, HttpResponse body, int status) {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        servletResponse.setStatus(status);
        return scrubber.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON,
                (Class<? extends HttpMessageConverter<?>>) null,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(servletResponse));
    }

    @Test
    @DisplayName("in production, an error body carries neither devMessage nor the raw reason")
    void productionScrubsErrorDetail() {
        HttpResponse scrubbed = (HttpResponse) write(scrubber(false), errorBody(), 400);

        assertNull(scrubbed.getDevMessage(), "devMessage is developer-facing and must never ship");
        assertNotEquals(LEAKY_DETAIL, scrubbed.getReason());
        assertFalse(scrubbed.getReason().contains("victim@example.com"),
                "The looked-up identifier must not survive into the client's copy — echoing it "
                        + "would make the response an enumeration oracle regardless of the login "
                        + "handler's own wording.");
    }

    @Test
    @DisplayName("the deliberate, user-facing message survives scrubbing")
    void userFacingMessageIsPreserved() {
        HttpResponse scrubbed = (HttpResponse) write(scrubber(false), errorBody(), 500);

        // `message` is written by hand for the user; only the incidental exception text is removed.
        assertEquals("Something went wrong.", scrubbed.getMessage());
    }

    @Test
    @DisplayName("a successful response keeps its reason but still loses devMessage")
    void successfulResponseKeepsReason() {
        HttpResponse body = HttpResponse.builder().message("OK").reason("all good").devMessage(LEAKY_DETAIL).build();

        HttpResponse scrubbed = (HttpResponse) write(scrubber(false), body, 200);

        assertEquals("all good", scrubbed.getReason(), "2xx bodies carry no exception text to hide");
        assertNull(scrubbed.getDevMessage(), "devMessage is developer-facing on any status");
    }

    @Test
    @DisplayName("in development the advice does not run at all, so debugging keeps full detail")
    void developmentProfileKeepsFullDetail() {
        ErrorDetailScrubber devScrubber = scrubber(true);

        // supports() false means Spring skips beforeBodyWrite entirely — the body is untouched
        // and the advice costs nothing locally.
        assertFalse(devScrubber.supports(null, null));
    }

    @Test
    @DisplayName("production enables the advice")
    void productionEnablesTheAdvice() {
        assertTrue(scrubber(false).supports(null, null));
    }

    @Test
    @DisplayName("a non-envelope body is passed through untouched")
    void nonEnvelopeBodyIsUntouched() {
        // Byte streams (the XLSX export, profile images) must not be corrupted by the advice.
        Object raw = new byte[]{1, 2, 3};

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Object result = scrubber(false).beforeBodyWrite(raw, null, MediaType.APPLICATION_OCTET_STREAM,
                (Class<? extends HttpMessageConverter<?>>) null,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(servletResponse));

        assertEquals(raw, result);
    }
}
