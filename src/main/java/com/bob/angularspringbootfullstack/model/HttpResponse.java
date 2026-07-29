package com.bob.angularspringbootfullstack.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * HttpResponse is a custom HTTP response wrapper class.
 * <p>
 * This standardized response object is used for all API endpoints to provide
 * a consistent response structure to the client. It includes HTTP status information,
 * timestamp, messages, and optional data payload.
 * <p>
 * The class uses @JsonInclude(NON_DEFAULT) to exclude null/empty fields from JSON serialization,
 * keeping responses clean and minimal.
 * <p>
 * Fields:
 * - timeStamp: ISO timestamp of when the response was generated
 * - statusCode: HTTP status code (e.g., 200, 400, 401)
 * - Status: Spring's HttpStatus enum value
 * - Reason: Brief reason for the status (e.g., "Unauthorized")
 * - Message: User-friendly message about the response
 * - devMessage: Developer-facing message with technical details
 * - Data: Map containing response data payload (can be nested objects)
 */
@Data
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class HttpResponse {
    /**
     * Timestamp of when the response was generated (ISO 8601 format)
     */
    protected String timeStamp;
    /**
     * HTTP status code (200, 400, 401, 404, 500, etc.)
     */
    protected int statusCode;
    /**
     * Spring's HttpStatus enum value (e.g., OK, BAD_REQUEST, UNAUTHORIZED)
     */
    protected HttpStatus status;
    /**
     * Brief reason for the status response
     */
    protected String reason;
    /**
     * User-friendly message to display on the client side
     */
    protected String message;
    /**
     * Developer-facing technical message for debugging
     */
    protected String devMessage;
    /**
     * Map containing response data payload (can hold user objects, lists, etc.)
     */
    protected Map<?, ?> data;
    /**
     * Request URI path (e.g., "/user/profile", "/user/login")
     */
    protected String path;
    /**
     * Whether {@link #reason} was written by this application <i>for the end user</i>, as opposed to
     * being lifted off an exception's {@code getMessage()}.
     * <p>
     * This exists because {@code reason} carries two very different kinds of text. A handler may set
     * it to a sentence composed for a human ("Too many failed login attempts. Please wait 15 minutes
     * before trying again.") or to whatever a framework exception happened to say, which for a JDBC
     * failure names tables and columns and for a lookup failure can embed the identifier that was
     * searched for. {@link com.bob.angularspringbootfullstack.exception.ErrorDetailScrubber} must
     * suppress the second kind in production and keep the first, and it runs at the serialization
     * boundary where the originating exception is long out of scope — so the distinction has to
     * travel with the response rather than being re-derived from the string itself. Inspecting the
     * text was the alternative and it is not a real one: no pattern reliably separates a sentence we
     * wrote from a sentence a library wrote, and a wrong guess either leaks internals or blanks a
     * message the user needed.
     * <p>
     * Set it only for text that is safe for an unauthenticated stranger to read. In particular it
     * must never distinguish "no such account" from "wrong password" — the login path funnels both
     * into one message on purpose, and marking a more specific variant authored would reopen the
     * enumeration hole the generic wording exists to close.
     * <p>
     * {@code @JsonIgnore} keeps it out of the JSON entirely: it is a routing hint between two server
     * components, not part of the API contract clients code against.
     */
    @JsonIgnore
    protected boolean authoredReason;

}
