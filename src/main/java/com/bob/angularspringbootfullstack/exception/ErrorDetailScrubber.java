package com.bob.angularspringbootfullstack.exception;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Strips internal diagnostic detail out of error responses before they leave the application
 * (NFR-SEC — prod error hygiene).
 *
 * <h3>What was leaking</h3>
 * {@link HandleException} builds every error body with {@code .devMessage(exception.getMessage())}
 * and frequently {@code .reason(exception.getMessage())} as well. Raw exception text is a rich
 * source of internal information: SQL constraint violations name tables and columns, JDBC errors
 * expose the schema, {@code UsernameNotFoundException} embeds the address that was looked up, and
 * stack-adjacent messages disclose framework versions and class names. Individually minor;
 * collectively they hand an attacker a map of the system, and the enumeration case is worse than
 * informational — it undermines the anti-enumeration guarantee the login path works hard to keep.
 *
 * <h3>Why this is an advice rather than edits to the handler</h3>
 * {@code HandleException} sets those fields in roughly ten places, and every future handler would
 * have to remember the rule. A cross-cutting concern enforced by convention is one that eventually
 * lapses — the interesting failure is not the ten call sites that exist today but the eleventh
 * someone adds next month. Applying it once at the serialization boundary makes the guarantee
 * structural: an error body cannot carry internal detail out of a production process regardless of
 * which handler produced it.
 *
 * <h3>Nothing is lost, only relocated</h3>
 * Every handler already logs the full exception server-side before building its response. This
 * removes the detail from the client's copy, not from the operator's: a support engineer reads it
 * in the application log, correlated by timestamp, exactly where such information belongs. That is
 * also why {@code message} is left untouched — it is the deliberately-written, user-facing half of
 * the envelope, distinct from the incidental text a stack trace happens to carry.
 *
 * <h3>Development is unaffected</h3>
 * Gated on {@code app.error.expose-details}, which stays {@code true} in the base profile and is
 * pinned {@code false} in {@code application-prod.yml}. Debugging locally therefore keeps the
 * verbose bodies; only deployed environments are quiet. The scrubber is skipped entirely — not
 * just made a no-op — when details are exposed, so it costs nothing in development.
 */
@RestControllerAdvice
@Slf4j
public class ErrorDetailScrubber implements ResponseBodyAdvice<Object> {

    /**
     * Whether internal error detail may reach clients. True in the base/dev profile, false in
     * production (see {@code application-prod.yml}).
     */
    @Value("${app.error.expose-details:true}")
    private boolean exposeDetails;

    /**
     * What replaces a leaked {@code reason}. Deliberately uniform: a message that varied by
     * exception type would re-introduce, in coarser form, the very signal being removed.
     */
    private static final String GENERIC_REASON = "The request could not be completed.";

    /**
     * Runs only when detail exposure is disabled and the payload is one of this application's
     * standard envelopes. Returning false in development skips the advice for every response.
     *
     * @param returnType    the controller method's return type
     * @param converterType the message converter selected for the response
     * @return true when this advice should inspect the body
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return !exposeDetails;
    }

    /**
     * Blanks {@code devMessage} and genericises {@code reason} on error responses.
     *
     * <p>Scoped to 4xx/5xx by status rather than applied to every envelope: successful responses
     * do not carry exception text, and rewriting them would risk clobbering a legitimately
     * populated field for no benefit. {@code devMessage} is cleared on any status it appears on,
     * since it is by definition developer-facing.
     *
     * <p>{@code @JsonInclude(NON_DEFAULT)} on {@link HttpResponse} means a nulled field is omitted
     * from the JSON entirely rather than serialised as {@code null} — the client sees a body that
     * simply never had those keys.
     *
     * @param body       the response payload about to be serialised
     * @param returnType the controller method's return type
     * @param selectedContentType the negotiated content type
     * @param selectedConverterType the chosen message converter
     * @param request    the current request
     * @param response   the response being written, source of the resolved status code
     * @return the body, scrubbed when it is an error envelope
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof HttpResponse httpResponse)) {
            return body;
        }
        httpResponse.setDevMessage(null);
        if (isError(response) && httpResponse.getReason() != null) {
            httpResponse.setReason(GENERIC_REASON);
        }
        return httpResponse;
    }

    /**
     * Whether the response being written carries a 4xx or 5xx status.
     *
     * @param response the response under construction
     * @return true for client- and server-error statuses
     */
    private static boolean isError(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            return servletResponse.getServletResponse().getStatus() >= 400;
        }
        // Unknown response type: treat as an error so the conservative branch wins. Scrubbing a
        // successful body costs a field nobody reads; leaking an error body costs information.
        return true;
    }
}
