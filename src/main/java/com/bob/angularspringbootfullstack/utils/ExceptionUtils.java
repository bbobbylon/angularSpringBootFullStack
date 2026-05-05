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
 * Helpers shared by the security filter chain for serializing exceptions into
 * the application's HttpResponse JSON shape.
 * <p>
 * Used by CustomAuthFilter and the controller's authenticate() helper to turn
 * known auth/security exceptions (ApiException, DisabledException,
 * LockedException, BadCredentialsException) into a 400 response, and anything
 * else into a generic 500 so internal details are not leaked to the client.
 */
@Slf4j
public class ExceptionUtils {
    /**
     * Writes a JSON error response derived from the given exception.
     * <p>
     * Known auth-related exceptions (ApiException, DisabledException,
     * LockedException, BadCredentialsException) become a 400 carrying the
     * exception message; anything else becomes a 500 with a generic message
     * so internals aren't exposed. The exception is also logged for the
     * server-side trail.
     *
     * @param request   the current request, used for the path field on the response
     * @param response  the response stream the JSON is written to
     * @param exception the exception to translate
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
     * Builds the HttpResponse payload and stamps the response with status
     * code and JSON content type. The request URI is included as the path
     * field for debugging.
     *
     * @param request    the current request (read for getRequestURI)
     * @param response   the response to mutate (status + content type)
     * @param message    the reason field placed on the body
     * @param httpStatus the HTTP status to set
     * @return the HttpResponse ready to serialize
     */
    private static HttpResponse getHttpResponse(HttpServletRequest request, HttpServletResponse response, String message, HttpStatus httpStatus) {
        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(now().toString())
                .reason(message)
                .devMessage(message)
                .status(httpStatus)
                .statusCode(httpStatus.value())
                .path(request.getRequestURI())
                .build();
        response.setContentType("application/json");
        response.setStatus(httpStatus.value());
        return httpResponse;
    }

    /**
     * Serializes the HttpResponse to JSON and flushes it to the response
     * output stream. I/O failures are logged rather than rethrown so the
     * caller can finish handling the original exception.
     *
     * @param response     the response stream to write to
     * @param httpResponse the HttpResponse to serialize
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
