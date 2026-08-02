package com.bob.angularspringbootfullstack.exception;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
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
// NOTE: this class still populates .devMessage/.reason with raw exception text, and that is
// intentional — ErrorDetailScrubber (a ResponseBodyAdvice) strips both from 4xx/5xx bodies
// whenever app.error.expose-details is false, which application-prod.yml pins. Scrubbing at
// the serialization boundary rather than at each of the ~10 builders here is what makes the
// guarantee structural: a new handler added later cannot forget to apply it.

/**
 * Central {@code @RestControllerAdvice} for translating framework and application exceptions into a
 * consistent {@link HttpResponse} JSON payload.
 *
 * <p><b>Security note:</b> this handler currently returns {@code devMessage} and (in some cases)
 * exception messages to the client. That is useful during development but should be reduced/removed
 * for production deployments to avoid leaking internal details.
 */
@RestControllerAdvice
@Slf4j
public class HandleException extends ResponseEntityExceptionHandler implements ErrorController {

    /**
     * Translates @Valid bean-validation failures into an HttpResponse whose
     * reason field is the comma-joined list of field-error messages.
     *
     * @param exception  the validation failure thrown by Spring MVC
     * @param headers    response headers chosen by the framework
     * @param statusCode the HTTP status of the framework selected
     * @param request    the current request
     * @return an HttpResponse-bodied ResponseEntity with the validation messages
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception, @NonNull HttpHeaders headers, @NonNull HttpStatusCode statusCode, @NonNull WebRequest request) {
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

    /**
     * Hook used by ResponseEntityExceptionHandler for the framework-internal
     * exceptions it already maps to a status; rewraps the body so it conforms
     * to the application's HttpResponse shape.
     *
     * @param exception  the framework exception
     * @param body       the body Spring would otherwise return (ignored)
     * @param headers    response headers
     * @param statusCode the framework-selected status
     * @param request    the current request
     * @return a ResponseEntity carrying an HttpResponse
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body, @NonNull HttpHeaders headers, @NonNull HttpStatusCode statusCode, @NonNull WebRequest request) {
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

    /**
     * Returns 400 for SQL integrity-constraint violations. When the underlying
     * message is a "Duplicate entry," the reason is collapsed to a friendlier
     * string; otherwise the raw message is passed through.
     *
     * @param exception the SQL violation
     * @return 400 BAD_REQUEST with the duplicate-friendly message
     */
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


    /**
     * Returns 400 for BadCredentialsException, which covers both wrong
     * email/password and malformed JWTs. When the message indicates a decoded
     * failure (raised by TokenProvider#getSubject), the reason is replaced
     * with a clean client-facing string; otherwise the standard "Incorrect
     * email or password" suffix is appended.
     *
     * @param exception the credentials/decode failure
     * @return 400 BAD_REQUEST with a sanitized reason
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<HttpResponse> badCredentialsException(BadCredentialsException exception) {
        log.error(exception.getMessage());
        // NEW (May 2026): BadCredentialsException now represents two scenarios:
        // 1. Login failure: User provided the wrong email /password
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

    /**
     * Maps an application-thrown ApiException to a 400 with the exception's
     * message as the reason.
     *
     * @param exception the business-logic failure
     * @return 400 BAD_REQUEST
     */
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

    /**
     * Returns 403 when an authenticated user is missing the authority required
     * for an endpoint.
     *
     * @param exception the access-denied failure
     * @return 403 FORBIDDEN with a generic reason
     */
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


    /**
     * Catch-all for any exception not handled more specifically. Maps to 500
     * with the exception message; "expected 1, actual 0" (from
     * NamedParameterJdbcTemplate#queryForObject) is rewritten to
     * "Record not found".
     *
     * @param exception any unhandled exception
     * @return 500 INTERNAL_SERVER_ERROR
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

    /**
     * Returns 500 with a generic "Could not decode the token" message when
     * the JWT library fails to parse a token. The original message is logged
     * but not returned to the client.
     *
     * @param exception the decoded failure
     * @return 500 INTERNAL_SERVER_ERROR
     */
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

    /**
     * Returns <b>401</b> for any JWT verification failure that reaches a controller — an expired,
     * malformed, or badly-signed token (ROADMAP §2.4(a)).
     *
     * <h3>Why this handler has to exist</h3>
     * {@code TokenProvider#getSubject} keeps every token failure inside the
     * {@link JWTVerificationException} family, because {@code ExceptionUtils#processError} decides
     * 401-vs-400 on exactly that type. That covers the <em>filter</em> path. But the refresh flow
     * calls {@code getSubject} from {@code SessionServiceImpl} — inside a controller — so its
     * exceptions arrive here instead. Without this method they would fall through to the catch-all
     * {@code @ExceptionHandler(Exception.class)} and surface as a <b>500</b>, turning a routine
     * expired-token refresh into an apparent server fault.
     *
     * <p>The two paths must agree: a client cannot be expected to treat the same failure as 401 on
     * one endpoint and 500 on another, and {@code token.interceptor.ts} retries only on 401.
     *
     * <h3>Why the reason is canned</h3>
     * The underlying message is auth0's raw text ("The Token's Signature resulted invalid when
     * verified using the Algorithm: HmacSHA512"), which names the signing algorithm and the failure
     * mode. That is a hint to an attacker probing for token weaknesses and of no use to a legitimate
     * client, whose only available action is to log in again. The detail is logged server-side.
     *
     * <p>Note {@link JWTDecodeException} is a <em>subclass</em> of this type and keeps its own
     * handler above; Spring dispatches to the most specific match. That handler stays for tokens
     * decoded outside {@code getSubject}, which never enter this family.
     *
     * @param exception the token verification failure
     * @return 401 UNAUTHORIZED with a generic, non-diagnostic reason
     */
    @ExceptionHandler(JWTVerificationException.class)
    public ResponseEntity<HttpResponse> jwtVerificationException(JWTVerificationException exception) {
        log.error("JWT verification failed: {}", exception.getMessage());
        return new ResponseEntity<>(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .reason("Invalid token. Please log in again.")
                        .devMessage(exception.getMessage())
                        .status(UNAUTHORIZED)
                        .statusCode(UNAUTHORIZED.value())
                        .build(), UNAUTHORIZED);
    }

    /**
     * Returns 400 when a JdbcTemplate "queryForObject" finds no row.
     * "Expected 1, actual 0" messages are rewritten to "Record not found".
     *
     * @param exception the empty-result failure
     * @return 400 BAD_REQUEST
     */
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

    /**
     * Returns 400 when DaoAuthenticationProvider rejects login because the
     * user account is disabled (typically: email not yet verified).
     *
     * @param exception the disabled-account failure
     * @return 400 BAD_REQUEST
     */
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

    /**
     * Returns 400 when DaoAuthenticationProvider rejects login because the
     * user account is locked.
     *
     * @param exception the locked-account failure
     * @return 400 BAD_REQUEST
     */
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

    /**
     * Returns 400 for any Spring DataAccessException, with the message
     * cleaned up by {@link #processErrorMessage(String)} (e.g., duplicate
     * account/password verification rows get friendly messages).
     *
     * @param exception the data-access failure
     * @return 400 BAD_REQUEST
     */
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

    /**
     * Maps low-level "Duplicate entry" SQL errors to user-facing strings,
     * recognizing the AccountVerifications and ResetPasswordVerifications
     * tables specifically.
     *
     * @param errorMessage the raw exception message
     * @return a friendlier reason, or "Some error occurred" when null
     */
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

}
