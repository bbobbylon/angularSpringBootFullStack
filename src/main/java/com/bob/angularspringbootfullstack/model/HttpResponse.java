package com.bob.angularspringbootfullstack.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * HttpResponse is a custom HTTP response wrapper class.
 * <p>
 * This standardized response object is used for all API endpoints to provide
 * consistent response structure to the client. It includes HTTP status information,
 * timestamp, messages, and optional data payload.
 * <p>
 * The class uses @JsonInclude(NON_DEFAULT) to exclude null/empty fields from JSON serialization,
 * keeping responses clean and minimal.
 * <p>
 * Fields:
 * - timeStamp: ISO timestamp of when the response was generated
 * - statusCode: HTTP status code (e.g., 200, 400, 401)
 * - status: Spring's HttpStatus enum value
 * - reason: Brief reason for the status (e.g., "Unauthorized")
 * - message: User-friendly message about the response
 * - devMessage: Developer-facing message with technical details
 * - data: Map containing response data payload (can be nested objects)
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
     * User-friendly message to display on client side
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

}
