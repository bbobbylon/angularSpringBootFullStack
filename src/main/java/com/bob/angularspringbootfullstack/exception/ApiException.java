package com.bob.angularspringbootfullstack.exception;

/**
 * ApiException is a custom unchecked exception used throughout the application.
 *
 * This exception is thrown when business logic encounters an error condition
 * that should be communicated back to the API client with a meaningful error message.
 *
 * By extending RuntimeException, it's an unchecked exception that doesn't require
 * explicit catch/throw declarations, making code cleaner. The GlobalExceptionHandler
 * intercepts these exceptions and converts them to proper HTTP error responses.
 *
 * Usage:
 *   throw new ApiException("User with email already exists");
 *   throw new ApiException("Invalid verification code");
 */
public class ApiException extends RuntimeException {
    /**
     * Constructs a new ApiException with a descriptive error message.
     *
     * @param message the error message to display to the API client
     */
    public ApiException(String message) {
        super(message);
    }
}
