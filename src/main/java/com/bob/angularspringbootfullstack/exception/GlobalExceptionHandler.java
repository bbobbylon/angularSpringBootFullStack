package com.bob.angularspringbootfullstack.exception;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalTime;


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
 
    /**
     * Handles Bean Validation failures from @Valid-annotated controller parameters.
     * Collects all field-level constraint messages and joins them so the client
     * sees every problem in one response rather than just the first.
     *
     * @param ex the validation exception containing one FieldError per failed constraint
     * @return 400 BAD_REQUEST with all validation messages in the reason field
     * @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<HttpResponse> handleValidationException(MethodArgumentNotValidException ex) {
     * String message = ex.getBindingResult().getFieldErrors().stream()
     * .map(error -> error.getField() + ": " + error.getDefaultMessage())
     * .collect(java.util.stream.Collectors.joining(", "));
     * HttpResponse response = HttpResponse.builder()
     * .timeStamp(LocalTime.now().toString())
     * .reason(message)
     * .devMessage(message)
     * .status(HttpStatus.BAD_REQUEST)
     * .statusCode(HttpStatus.BAD_REQUEST.value())
     * .build();
     * return new ResponseEntity <>(response, HttpStatus.BAD_REQUEST);
     * }
     */
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

