package com.bob.angularspringbootfullstack.exception;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalTime;


/**
 * GlobalExceptionHandler is a centralized exception handling component for all REST controllers.
 *
 * This class uses Spring's @RestControllerAdvice annotation to intercept exceptions
 * thrown by controller methods and provide standardized error responses to clients.
 *
 * Benefits:
 * - Centralized exception handling (DRY principle)
 * - Consistent API error response format across all endpoints
 * - Custom error messages instead of generic Spring defaults
 * - Better user experience with meaningful error descriptions
 *
 * How it works:
 * 1. Spring scans for classes annotated with @RestControllerAdvice at startup
 * 2. When an exception is thrown during request handling, Spring checks for matching @ExceptionHandler methods
 * 3. If a match is found, the exception handler method is invoked
 * 4. The handler returns a customized HttpResponse with appropriate status and message
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * Handles custom ApiException thrown throughout the application.
     * Converts the exception to a standardized HTTP 400 Bad Request response.
     *
     * This allows business logic to throw descriptive ApiExceptions
     * which are then automatically converted to proper HTTP responses
     * without letting raw exceptions leak to the client.
     *
     * @param ex the ApiException thrown by application logic
     * @return ResponseEntity with HttpResponse containing error details and 400 status
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HttpResponse> handleApiException(ApiException ex) {
        HttpResponse response = HttpResponse.builder()
                .timeStamp(LocalTime.now().toString())
                .reason(ex.getMessage())
                .status(HttpStatus.BAD_REQUEST)
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .build();
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
}

