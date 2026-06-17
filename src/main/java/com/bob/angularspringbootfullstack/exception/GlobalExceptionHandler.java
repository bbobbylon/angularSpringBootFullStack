package com.bob.angularspringbootfullstack.exception;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalTime;
import java.util.stream.Collectors;


/**
 * GlobalExceptionHandler is a centralized exception handling component for all REST controllers.
 * <p>
 * This class uses Spring's @RestControllerAdvice annotation to intercept exceptions
 * thrown by controller methods and provide standardized error responses to clients.
 * <p>
 * Benefits:
 * - Centralized exception handling (DRY principle)
 * - Consistent API error response format across all endpoints
 * - Custom error messages instead of generic Spring defaults
 * - Better user experience with meaningful error descriptions
 * <p>
 * How it works:
 * 1. Spring scans for classes annotated with @RestControllerAdvice at startup
 * 2. When an exception is thrown during request handling, Spring checks for matching @ExceptionHandler methods
 * 3. If a match is found, the exception handler method is invoked
 * 4. The handler returns a customized HttpResponse with the appropriate status and message
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles Bean Validation failures from {@code @Valid}-annotated request bodies.
     * <p>
     * Collects every field-level constraint message and joins them so the client sees
     * all problems in one response rather than only the first. The joined text goes in
     * {@code reason} — the field the Angular {@code CustomerService.handleError} reads —
     * so validation failures surface in the UI's error alert exactly like every other
     * structured error.
     *
     * @param ex the validation exception carrying one {@code FieldError} per failed constraint
     * @return 400 BAD_REQUEST with all validation messages in the standard envelope
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<HttpResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return badRequest(message, message);
    }

    /**
     * Handles constraint violations on {@code @RequestParam}/{@code @PathVariable} arguments
     * of {@code @Validated} controllers (the method-parameter counterpart to the body-level
     * {@link MethodArgumentNotValidException}).
     *
     * @param ex the violation set raised by the bean-validation provider
     * @return 400 BAD_REQUEST with each violation message in the standard envelope
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<HttpResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        return badRequest(message, message);
    }

    /**
     * Handles a syntactically invalid or unparseable request body (malformed JSON, wrong
     * types). Returns a client-safe message and keeps the parser detail in {@code devMessage}
     * only, so internal type names are never leaked to end users.
     *
     * @param ex the deserialization failure raised by the message converter
     * @return 400 BAD_REQUEST with a generic "malformed request" message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<HttpResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest("The request body is missing or malformed.", ex.getMostSpecificCause().getMessage());
    }

    /**
     * Last-resort handler for any exception not matched by a more specific handler.
     * <p>
     * Logs the full stack trace server-side for diagnosis but returns ONLY a generic
     * message to the client — never the exception text — so stack traces, SQL, and class
     * names never reach the browser (NFR-SEC). The exact message is preserved in
     * {@code devMessage} for non-production troubleshooting.
     *
     * @param ex any otherwise-unhandled exception thrown during request processing
     * @return 500 INTERNAL_SERVER_ERROR with a safe, generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HttpResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        HttpResponse response = HttpResponse.builder()
                .timeStamp(LocalTime.now().toString())
                .reason("An unexpected error occurred. Please try again.")
                .devMessage(ex.getMessage())
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .build();
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Builds the standard 400 envelope shared by the validation handlers above.
     *
     * @param reason     client-facing reason (read by the Angular error handler)
     * @param devMessage developer-facing detail for logs/non-prod diagnostics
     * @return a 400 BAD_REQUEST response in the standard {@link HttpResponse} shape
     */
    private ResponseEntity<HttpResponse> badRequest(String reason, String devMessage) {
        HttpResponse response = HttpResponse.builder()
                .timeStamp(LocalTime.now().toString())
                .reason(reason)
                .devMessage(devMessage)
                .status(HttpStatus.BAD_REQUEST)
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .build();
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HttpResponse> handleApiException(ApiException ex) {
        HttpResponse response = HttpResponse.builder()
                .timeStamp(LocalTime.now().toString())
                .reason(ex.getMessage())
                .devMessage(ex.getMessage())
                .status(HttpStatus.BAD_REQUEST)
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .build();
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Maps authorization denials raised INSIDE controller methods to {@code 403 FORBIDDEN}
     * in the standard envelope (FR-RBAC-3, FR-ORG-2).
     * <p>
     * Two sources reach this handler: {@code @PreAuthorize} failures on admin endpoints
     * (method-level security throws {@code AuthorizationDeniedException}, a subclass) and
     * the explicit organization-scope check in {@code AdminUserController}, which throws
     * {@code AccessDeniedException} when an organization administrator targets a user
     * outside their organization. URL-level denials never get here — they are handled
     * earlier in the filter chain by {@code CustomAccessDeniedHandler}; this handler
     * keeps the response shape identical for denials that occur after dispatch.
     *
     * @param ex the denial, whose message is safe to surface (it names no account data)
     * @return 403 FORBIDDEN with the denial reason in the standard response envelope
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<HttpResponse> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException ex) {
        HttpResponse response = HttpResponse.builder()
                .timeStamp(LocalTime.now().toString())
                .reason("You do not have permission to perform this action.")
                .devMessage(ex.getMessage())
                .status(HttpStatus.FORBIDDEN)
                .statusCode(HttpStatus.FORBIDDEN.value())
                .build();
        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }

}

