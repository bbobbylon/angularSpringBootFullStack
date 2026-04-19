package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * CustomAccessDeniedHandler handles authorization failures for authenticated users.
 *
 * This component implements Spring Security's AccessDeniedHandler interface,
 * which is invoked when an authenticated user lacks the required permissions/roles
 * to access a protected resource.
 *
 * Unlike authentication failures (401 Unauthorized), access denied is a 403 Forbidden
 * status which means the user is authenticated but not authorized.
 *
 * This custom implementation returns a JSON response instead of the default error page,
 * providing consistency with our API's response format.
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
    /**
     * Handles authorization failures by returning a custom 403 response.
     *
     * This method is called when Spring Security's authorization checks fail
     * (e.g., when a user lacks the required role for an endpoint).
     * It returns a JSON response with our custom HttpResponse format.
     *
     * Steps:
     * 1. Builds an HttpResponse with 403 FORBIDDEN status and permission denied message
     * 2. Sets response content type to application/json
     * 3. Sets HTTP status code to 403
     * 4. Serializes the HttpResponse to JSON using ObjectMapper
     * 5. Writes JSON to response output stream
     * 6. Flushes the output to send response to client
     *
     * @param request the HTTP request that triggered access denied
     * @param response the HTTP response to write error details to
     * @param accessDeniedException the access denied exception that was thrown
     * @throws IOException if writing to response fails
     * @throws ServletException if servlet operation fails
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(now().toString())
                .reason("You don't have enough permission to access this resource!")
                .status(FORBIDDEN)
                .statusCode(FORBIDDEN.value())
                .build();
        response.setContentType("application/json");
        response.setStatus(FORBIDDEN.value());

        OutputStream out = response.getOutputStream();
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(out, httpResponse);
        out.flush();
    }
}
