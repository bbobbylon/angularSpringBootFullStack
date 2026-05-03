package com.bob.angularspringbootfullstack.utils;

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
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * ExceptionUtils is a utility class for centralized exception handling and error response formatting.
 * <p>
 * <b>Purpose:</b> Converts application exceptions into standardized JSON error responses that are
 * consistent with the application's HttpResponse format, ensuring uniform error communication to clients.
 * <p>
 * <b>How it works:</b>
 * <ul>
 *   <li>Catches specific security and custom exceptions (ApiException, TokenExpiredException, etc.)</li>
 *   <li>Maps them to appropriate HTTP status codes (BAD_REQUEST for known exceptions, INTERNAL_SERVER_ERROR for others)</li>
 *   <li>Creates an HttpResponse object containing timestamp, status, error message, and request path for debugging</li>
 *   <li>Serializes the response to JSON using ObjectMapper and writes it to the HTTP response stream</li>
 *   <li>Logs all exceptions for server-side debugging and monitoring</li>
 * </ul>
 * <p>
 * <b>Integration with other components:</b>
 * <ul>
 *   <li><b>CustomAuthenticationEntryPoint:</b> Calls processError() when authentication fails (no valid JWT token)</li>
 *   <li><b>CustomAccessDeniedHandler:</b> Calls processError() when authorization fails (insufficient permissions)</li>
 *   <li><b>GlobalExceptionHandler:</b> Can use similar logic for handling controller exceptions</li>
 *   <li><b>CustomAuthFilter:</b> May throw TokenExpiredException or InvalidClaimException that gets caught here</li>
 *   <li><b>HttpResponse model:</b> Provides standardized response structure with fields: timeStamp, statusCode, status, reason, message, path</li>
 *   <li><b>FilterChain:</b> Invoked by security filters when exceptions occur during request processing</li>
 * </ul>
 * <p>
 * <b>Exception handling strategy:</b>
 * <ul>
 *   <li><b>Known exceptions (BAD_REQUEST):</b> ApiException, DisabledException, LockedException, InvalidClaimException, TokenExpiredException, BadCredentialsException</li>
 *   <li><b>Unknown exceptions (INTERNAL_SERVER_ERROR):</b> Generic "An error has occurred" message for security</li>
 * </ul>
 */
@Slf4j
public class ExceptionUtils {
    /**
     * Processes exceptions and sends a standardized error response to the client.
     * <p>
     * <b>Flow:</b>
     * <ol>
     *   <li>Catches the exception and determines if it's a known security/custom exception</li>
     *   <li>Builds an HttpResponse object with appropriate status code and error message</li>
     *   <li>Includes request path (request URI) for debugging purposes</li>
     *   <li>Serializes response to JSON and writes to HTTP response stream</li>
     *   <li>Logs the exception on the server side for monitoring</li>
     * </ol>
     * <p>
     * <b>Called by:</b> CustomAuthenticationEntryPoint, CustomAccessDeniedHandler, Security filters, CustomAuthFilter
     * <p>
     * <b>Exception categorization (BAD_REQUEST 400):</b>
     * <ul>
     *   <li><b>ApiException:</b> Custom business logic exception (email exists, link expired, etc.)</li>
     *   <li><b>DisabledException:</b> User account is disabled</li>
     *   <li><b>LockedException:</b> User account is locked (too many login attempts)</li>
     *   <li><b>BadCredentialsException:</b> Invalid login credentials OR malformed token (NEW - May 2026)</li>
     * </ul>
     * <p>
     * <b>Exception categorization (Other status codes):</b>
     * <ul>
     *   <li><b>InvalidClaimException:</b> JWT claims validation failed (issuer/audience mismatch) → throws 401</li>
     *   <li><b>TokenExpiredException:</b> JWT token has expired → throws 401</li>
     *   <li><b>Unknown exceptions:</b> Maps to INTERNAL_SERVER_ERROR 500 for security (no details leaked)</li>
     * </ul>
     * <p>
     * <b>New Behavior (May 2026 - Refresh Token Enhancement):</b>
     * <p>
     * BadCredentialsException now handles two scenarios:
     * <ol>
     *   <li><b>Invalid credentials:</b> User provided wrong email/password at login (original behavior)</li>
     *   <li><b>Malformed token:</b> Token cannot be decoded as Base64 JWT (new behavior from TokenProvider.getSubject())</li>
     * </ol>
     * <p>
     * When TokenProvider.getSubject() encounters a JWTDecodeException (malformed token), it wraps it as:
     * <pre>
     * throw new BadCredentialsException("Could not decode the token. The input is not a valid Base64-encoded JWT.");
     * </pre>
     * This method then maps it to BAD_REQUEST (400) with the clear message, preventing client from seeing raw JWT library errors.
     * <p>
     * <b>Response Examples:</b>
     * <pre>
     * Invalid credentials: {"reason": "Invalid email or password", "statusCode": 400}
     * Malformed token: {"reason": "Could not decode the token...", "statusCode": 400}
     * Expired token: {"reason": "Token has expired", "statusCode": 401}
     * Generic error: {"reason": "An error has occurred, please try again", "statusCode": 500}
     * </pre>
     *
     * @param request   the HTTP servlet request containing request details (URI, method, etc.)
     * @param response  the HTTP servlet response where the error response will be written
     * @param exception the exception that occurred during request processing
     */
    public static void processError(HttpServletRequest request, HttpServletResponse response, Exception exception) {
        if (exception instanceof ApiException || exception instanceof DisabledException || exception instanceof LockedException || exception instanceof BadCredentialsException) {
            HttpResponse httpResponse = getHttpResponse(request, response, exception.getMessage(), BAD_REQUEST);
            writeResponse(response, httpResponse);
        } else {
            HttpResponse httpResponse = getHttpResponse(request, response, "An error has occurred, please try again", INTERNAL_SERVER_ERROR);
            writeResponse(response, httpResponse);
        }
        log.error(exception.getMessage(), exception);
    }

    /**
     * Builds an HttpResponse object with error details and request context.
     * <p>
     * <b>Response structure:</b>
     * <ul>
     *   <li>timeStamp: When the error occurred (ISO 8601 format via LocalTime.now())</li>
     *   <li>reason: The error message from the exception or generic message for unknown errors</li>
     *   <li>status: Spring HttpStatus enum (BAD_REQUEST, INTERNAL_SERVER_ERROR, etc.)</li>
     *   <li>statusCode: HTTP status code integer (400, 500, etc.)</li>
     *   <li>path: Request URI path for debugging (e.g., "/user/profile", "/user/login")</li>
     * </ul>
     * <p>
     * The response uses @JsonInclude(NON_DEFAULT) so only non-null fields are serialized.
     * <p>
     * <b>HTTP Response Configuration:</b>
     * <ul>
     *   <li>Sets Content-Type to "application/json"</li>
     *   <li>Sets HTTP status code via response.setStatus()</li>
     * </ul>
     *
     * @param request    the HTTP servlet request (used to capture request URI via request.getRequestURI())
     * @param response   the HTTP servlet response (configured with status code and content type)
     * @param message    the error message to include in the response (from exception or generic message)
     * @param httpStatus the HTTP status to send back to client (BAD_REQUEST for known exceptions, INTERNAL_SERVER_ERROR for others)
     * @return HttpResponse object ready to be serialized to JSON and sent to client
     */
    // this method builds an HttpResponse object based on the provided message and HTTP status and returns it
    private static HttpResponse getHttpResponse(HttpServletRequest request, HttpServletResponse response, String message, HttpStatus httpStatus) {
        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(now().toString())
                .reason(message)
                .status(httpStatus)
                .statusCode(httpStatus.value())
                .path(request.getRequestURI())
                .build();
        response.setContentType("application/json");
        response.setStatus(httpStatus.value());
        return httpResponse;
    }

    /**
     * Serializes the HttpResponse object to JSON and writes it to the HTTP response stream.
     * <p>
     * <b>Process:</b>
     * <ol>
     *   <li>Gets the OutputStream from the HTTP response</li>
     *   <li>Creates an ObjectMapper (Jackson) to serialize the HttpResponse to JSON</li>
     *   <li>Writes the JSON-serialized response to the output stream</li>
     *   <li>Flushes the stream to ensure data is sent to client</li>
     *   <li>Catches and logs any I/O exceptions that occur during serialization</li>
     * </ol>
     * <p>
     * <b>Jackson ObjectMapper:</b> Automatically serializes the HttpResponse object to JSON,
     * respecting the @JsonInclude(NON_DEFAULT) annotation to exclude null/empty fields.
     * <p>
     * <b>Called by:</b> processError() after building the HttpResponse
     *
     * @param response     the HTTP servlet response where the JSON will be written
     * @param httpResponse the HttpResponse object to serialize and send to client
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
            e.printStackTrace();
        }

    }


}
