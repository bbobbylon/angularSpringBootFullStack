package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * CustomAuthenticationEntryPoint handles unauthenticated requests to protected resources.
 *
 * This component implements Spring Security's AuthenticationEntryPoint interface,
 * which is invoked when an unauthenticated user attempts to access a protected resource.
 * Instead of returning the default 401 error page, it returns a customized JSON response
 * with our standardized HttpResponse format.
 *
 * This is part of the Spring Security filter chain and provides better UX by
 * returning meaningful error messages as JSON.
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
    /**
     * Handles authentication failures by returning a custom 401 response.
     *
     * This method is called when Spring Security detects an unauthenticated request
     * to a protected resource. Instead of redirecting to a login page or returning HTML,
     * it returns a JSON response with our custom HttpResponse format.
     *
     * Steps:
     * 1. Builds an HttpResponse with 401 UNAUTHORIZED status and friendly message
     * 2. Sets response content type to application/json
     * 3. Sets HTTP status code to 401
     * 4. Serializes the HttpResponse to JSON using ObjectMapper
     * 5. Writes JSON to response output stream
     * 6. Flushes the output to send response to client
     *
     * @param request the HTTP request that triggered authentication failure
     * @param response the HTTP response to write error details to
     * @param authException the authentication exception that was thrown
     * @throws IOException if writing to response fails
     * @throws ServletException if servlet operation fails
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        HttpResponse httpResponse = HttpResponse.builder()
                .timeStamp(now().toString())
                .reason("I don't think you are logged in :(  Please login to access this resource!")
                .status(UNAUTHORIZED)
                .statusCode(UNAUTHORIZED.value())
                .build();
        response.setContentType("application/json");
        response.setStatus(UNAUTHORIZED.value());

        OutputStream out = response.getOutputStream();
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(out, httpResponse);
        out.flush();
    }
}
