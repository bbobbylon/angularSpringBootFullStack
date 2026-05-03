package com.bob.angularspringbootfullstack.exception;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.*;

// TODO remove the bad dev practices specifically for .reason, .devMessage, and related for PRODUCTION builds. We need to replace this with something more secure.
@RestControllerAdvice
@Slf4j
public class HandleException extends ResponseEntityExceptionHandler implements ErrorController {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        log.error(exception.getMessage());
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
        String fieldMessage = fieldErrors.stream().map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return new ResponseEntity<>(HttpResponse.builder()
                .timeStamp(now().toString())
                .reason(fieldMessage)
                //again, we don't want to pass the whole exception message to the client, but for now we just pass it
                .devMessage(exception.getMessage())
                .status(resolve(statusCode.value()))
                .statusCode(statusCode.value())
                .build(), statusCode);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(HttpResponse.builder()
                .timeStamp(now().toString())
                //we want to pass a string not .getMessage() for security purposes but for now we just pass .getMessage()
                .reason(exception.getMessage())
                .devMessage(exception.getMessage())
                .status(resolve(statusCode.value()))
                .statusCode(statusCode.value())
                .build(), statusCode);
    }

    @ExceptionHandler(SQLIntegrityConstraintViolationException.class)
    public ResponseEntity<HttpResponse> sQLIntegrityConstraintViolationException(SQLIntegrityConstraintViolationException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(HttpResponse.builder()
                .timeStamp(now().toString())
                // again, don't do this in production, but for now we just pass the exception message to the client, but we want to pass a string not .getMessage() for security purposes
                .reason(exception.getMessage().contains("Duplicate entry") ? "Duplicate entry" : exception.getMessage())
                //again, we don't want to pass the whole exception message to the client, but for now we just pass it
                .devMessage(exception.getMessage())
                .status(BAD_REQUEST)
                .statusCode(BAD_REQUEST.value())
                .build(), BAD_REQUEST);
    }


    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<HttpResponse> badCredentialsException(BadCredentialsException exception) {
        log.error(exception.getMessage());
        // NEW (May 2026): BadCredentialsException now represents two scenarios:
        // 1. Login failure: User provided wrong email/password
        // 2. Malformed token: Token cannot be decoded as Base64 JWT (from TokenProvider.getSubject())
        // The exception message from TokenProvider will contain "Could not decode the token..."
        String reason = exception.getMessage();
        if (reason != null && reason.contains("Could not decode")) {
            // Malformed token scenario - use the clear message from TokenProvider
            reason = "The input is not a valid base 64 encoded string.";
        } else {
            // Login credentials scenario - append default message
            reason = reason + ", Incorrect email or password";
        }
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason(reason)
                        .devMessage(exception.getMessage())
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value())
                        .build(), BAD_REQUEST);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HttpResponse> apiException(ApiException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason(exception.getMessage())
                        .devMessage(exception.getMessage())
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value())
                        .build(), BAD_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<HttpResponse> accessDeniedException(AccessDeniedException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason("Access denied. You don't have access")
                        .devMessage(exception.getMessage())
                        .status(FORBIDDEN)
                        .statusCode(FORBIDDEN.value())
                        .build(), FORBIDDEN);
    }

/*
    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<HttpResponse> usernameNotFoundException(UsernameNotFoundException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason(exception.getMessage())
                        .devMessage(exception.getMessage())
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value())
                        .build(), BAD_REQUEST);
    }
*/

    @ExceptionHandler(Exception.class)
    public ResponseEntity<HttpResponse> exception(Exception exception) {
        log.error(exception.getMessage());
        log.error("e: ", exception);
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason(exception.getMessage() != null ?
                                (exception.getMessage().contains("expected 1, actual 0") ? "Record not found" : exception.getMessage())
                                : "Some error occurred")
                        .devMessage(exception.getMessage())
                        .status(INTERNAL_SERVER_ERROR)
                        .statusCode(INTERNAL_SERVER_ERROR.value())
                        .build(), INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(JWTDecodeException.class)
    public ResponseEntity<HttpResponse> exception(JWTDecodeException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason("Could not decode the token")
                        .devMessage(exception.getMessage())
                        .status(INTERNAL_SERVER_ERROR)
                        .statusCode(INTERNAL_SERVER_ERROR.value())
                        .build(), INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<HttpResponse> emptyResultDataAccessException(EmptyResultDataAccessException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason(exception.getMessage().contains("expected 1, actual 0") ? "Record not found" : exception.getMessage())
                        .devMessage(exception.getMessage())
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value())
                        .build(), BAD_REQUEST);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<HttpResponse> disabledException(DisabledException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .devMessage(exception.getMessage())
                        //.reason(exception.getMessage() + ". Please check your email and verify your account.")
                        .reason("User account is currently disabled")
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value()).build()
                , BAD_REQUEST);
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<HttpResponse> lockedException(LockedException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .devMessage(exception.getMessage())
                        //.reason(exception.getMessage() + ", too many failed attempts.")
                        .reason("User account is currently locked")
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value()).build()
                , BAD_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<HttpResponse> dataAccessException(DataAccessException exception) {
        log.error(exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason(processErrorMessage(exception.getMessage()))
                        .devMessage(processErrorMessage(exception.getMessage()))
                        .status(BAD_REQUEST)
                        .statusCode(BAD_REQUEST.value()).build()
                , BAD_REQUEST);
    }

    private String processErrorMessage(String errorMessage) {
        if (errorMessage != null) {
            if (errorMessage.contains("Duplicate entry") && errorMessage.contains("AccountVerifications")) {
                return "You already verified your account.";
            }
            if (errorMessage.contains("Duplicate entry") && errorMessage.contains("ResetPasswordVerifications")) {
                return "We already sent you an email to reset your password.";
            }
            if (errorMessage.contains("Duplicate entry")) {
                return "Duplicate entry. Please try again.";
            }
        }
        return "Some error occurred";
    }

    private ResponseEntity<HttpResponse> createErrorHttpResponse(HttpStatus httpStatus, String reason, Exception exception) {
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .devMessage(exception.getMessage())
                        .reason(reason)
                        .status(httpStatus)
                        .statusCode(httpStatus.value()).build()
                , httpStatus);
    }

}
