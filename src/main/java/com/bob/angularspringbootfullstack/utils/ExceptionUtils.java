package com.bob.angularspringbootfullstack.utils;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.*;

/**
 * Helpers shared by the security filter chain for serializing exceptions into
 * the application's HttpResponse JSON shape.
 * <p>
 * Used by CustomAuthFilter and the controller's authenticate() helper to turn
 * known auth/security exceptions into structured HTTP responses:
 * JWTVerificationException (including expired tokens) → 401,
 * ApiException/DisabledException/LockedException/BadCredentialsException → 400,
 * anything else → generic 500, so internal details are not leaked to the client.
 */
@Slf4j
public class ExceptionUtils {

    /**
     * What replaces raw exception text once detail exposure is off. Deliberately identical to
     * {@code ErrorDetailScrubber.GENERIC_REASON}: a message that varied by exception type would
     * re-introduce, in coarser form, the very signal being removed.
     */
    private static final String GENERIC_REASON = "The request could not be completed.";

    /**
     * Mirror of {@code app.error.expose-details} — true in dev, pinned false in
     * {@code application-prod.yml}. Defaults to {@code true} so a non-Spring context (a unit test
     * constructing responses directly) behaves like development rather than silently blanking
     * fields the test is asserting on.
     *
     * <p>Held statically because {@code processError} is a static entry point called from
     * {@link com.bob.angularspringbootfullstack.filter.CustomAuthFilter} via static import;
     * populated once at startup by {@link ErrorExposurePolicy} below.
     */
    private static volatile boolean exposeDetails = true;

    /**
     * Copies {@code app.error.expose-details} into {@link ExceptionUtils}'s static field at startup.
     *
     * <h3>Why this bridge is necessary</h3>
     * {@code ErrorDetailScrubber} is a {@code ResponseBodyAdvice}, so it only ever sees bodies
     * serialized through a controller's message converter. {@link ExceptionUtils#writeResponse}
     * writes directly to the servlet output stream from inside the security filter chain — before
     * any controller is selected — so the advice is <em>structurally unable</em> to reach it. That
     * is why {@code devMessage} and the raw {@code reason} were still leaving production on the
     * filter path despite {@code expose-details: false} being correctly set and the profile
     * confirmed {@code prod} (ROADMAP §2.4(b); reproduced against the live ALB 2026-08-02, where
     * {@code Bearer bad.token} returned auth0's raw decode text plus {@code path}).
     *
     * <p>The fix is applied at the point of writing rather than by widening the advice, because no
     * advice can intercept a stream the framework never mediates.
     */
    @org.springframework.stereotype.Component
    public static class ErrorExposurePolicy {
        /**
         * @param expose the resolved value of {@code app.error.expose-details}
         */
        public ErrorExposurePolicy(@org.springframework.beans.factory.annotation.Value("${app.error.expose-details:true}") boolean expose) {
            ExceptionUtils.exposeDetails = expose;
            log.info("[ERR] error-detail exposure = {} — filter-path error bodies {} carry devMessage/path",
                    expose, expose ? "WILL" : "will NOT");
        }
    }
    /**
     * Writes a JSON error response derived from the given exception.
     * <p>
     * JWTVerificationException (covers all token failures including expiry) becomes a 401.
     * Known auth-related exceptions (ApiException, DisabledException,
     * LockedException, BadCredentialsException) become a 400 carrying the
     * exception message; anything else becomes a 500 with a generic message
     * so internals aren't exposed. The exception is also logged for the
     * server-side trail.
     *
     * @param request   the current request, used for the path field on the response
     * @param response  the response stream the JSON is written to
     * @param exception the exception to translate
     */
    public static void processError(HttpServletRequest request, HttpServletResponse response, Exception exception) {
        if (exception instanceof JWTVerificationException) {
            // Canned, deliberately-written message — safe to show, and the only actionable thing a
            // client can do. Not derived from exception text, so it is never scrubbed.
            writeResponse(response, getHttpResponse(request, response, "Invalid token. Please log in again.", UNAUTHORIZED, false));
        } else if (exception instanceof ApiException || exception instanceof DisabledException || exception instanceof LockedException || exception instanceof BadCredentialsException) {
            // RAW exception text — flagged so it is genericised when detail exposure is off.
            writeResponse(response, getHttpResponse(request, response, exception.getMessage(), BAD_REQUEST, true));
        } else {
            writeResponse(response, getHttpResponse(request, response, "An error has occurred, please try again", INTERNAL_SERVER_ERROR, false));
        }
        log.error(exception.getMessage(), exception);
    }

    /**
     * Builds the HttpResponse payload and stamps the response with status
     * code and JSON content type. The request URI is included as the path
     * field for debugging.
     *
     * @param request    the current request (read for getRequestURI)
     * @param response   the response to mutate (status and content type)
     * @param message    the reason field placed on the body
     * @param httpStatus the HTTP status to set
     * @return the HttpResponse ready to serialize
     */
    private static HttpResponse getHttpResponse(HttpServletRequest request, HttpServletResponse response,
                                                String message, HttpStatus httpStatus, boolean messageIsRawExceptionText) {
        boolean expose = exposeDetails;
        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(now().toString())
                // devMessage is developer-facing by definition, so it is dropped outright in
                // production regardless of where the text came from.
                .devMessage(expose ? message : null)
                // reason is only genericised when it carries RAW exception text. The canned
                // messages ("Invalid token. Please log in again.") are deliberately written for the
                // client and disclose nothing, so blanking them would be a pure UX regression.
                .reason(expose || !messageIsRawExceptionText ? message : GENERIC_REASON)
                .status(httpStatus)
                .statusCode(httpStatus.value())
                // path echoes the request URI, which the caller already knows. Harmless, but it is
                // absent from production bodies elsewhere, so keep the shape consistent.
                .path(expose ? request.getRequestURI() : null)
                .build();
        response.setContentType("application/json");
        response.setStatus(httpStatus.value());
        return httpResponse;
    }

    /**
     * Serializes the HttpResponse to JSON and flushes it to the response
     * output stream. I/O failures are logged rather than rethrown so the
     * caller can finish handling the original exception.
     *
     * @param response     the response stream to write to
     * @param httpResponse the HttpResponse to serialize
     */
    private static void writeResponse(HttpServletResponse response, HttpResponse httpResponse) {
        OutputStream out;
        try {
            out = response.getOutputStream();
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(out, httpResponse);
            out.flush();
        } catch (Exception e) {
            log.error("Error writing response", e);
        }

    }


}
