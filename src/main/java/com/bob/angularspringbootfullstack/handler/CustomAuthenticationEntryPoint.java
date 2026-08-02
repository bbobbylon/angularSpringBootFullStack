package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.utils.BrowserErrorPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * CustomAuthenticationEntryPoint handles unauthenticated requests to protected resources.
 * <p>
 * This component implements Spring Security's AuthenticationEntryPoint interface,
 * which is invoked when an unauthenticated user attempts to access a protected resource.
 * Instead of returning the default 401 error page, it returns a customized JSON response
 * with our standardized HttpResponse format.
 * <p>
 * This is part of the Spring Security filter chain and provides better UX by
 * returning meaningful error messages as JSON.
 * <p>
 * <strong>Two representations, one status.</strong> Since the SPA is served from this same origin
 * in Docker/prod, this handler also answers plain browser navigations — someone typing a protected
 * URL, or following a stale link. Those callers were getting the JSON envelope rendered as raw text
 * in the viewport. {@link BrowserErrorPage} distinguishes the two cases on fetch metadata, and a
 * navigation now gets a styled page. The JSON path is unchanged and remains the default: the
 * frontend's token interceptor performs its silent refresh off this exact {@code 401}, so anything
 * that altered the status or reached the interceptor as HTML would disable automatic re-auth.
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
    /**
     * Handles authentication failures by returning a custom 401 response.
     * <p>
     * This method is called when Spring Security detects an unauthenticated request
     * to a protected resource. Instead of redirecting to a login page or returning HTML,
     * it returns a JSON response with our custom HttpResponse format.
     * <p>
     * Steps:
     * 1. Builds an HttpResponse with 401 UNAUTHORIZED statuses and friendly message
     * 2. Sets a response content type to application/json
     * 3. Sets HTTP status code to 401
     * 4. Serializes the HttpResponse to JSON using ObjectMapper
     * 5. Writes JSON to a response output stream
     * 6. Flushes the output to send a response to a client
     *
     * @param request       the HTTP request that triggered authentication failure
     * @param response      the HTTP response to write error details to
     * @param authException the authentication exception that was thrown
     * @throws IOException if writing to response fails
     *                     //@throws ServletException if the servlet operation fails
     */
    @Override
    public void commence(@NonNull HttpServletRequest request, HttpServletResponse response, @NonNull AuthenticationException authException) throws IOException {
        // A human navigated here rather than the SPA calling us. Same 401, readable presentation,
        // and a way back into the app instead of a dead end. Returns early so the JSON path below
        // stays byte-for-byte what the token interceptor already handles.
        if (BrowserErrorPage.isBrowserNavigation(request)) {
            BrowserErrorPage.write(response, UNAUTHORIZED.value(),
                    "401 · Unauthorized",
                    "You need to sign in",
                    "This page is only available to signed-in accounts. Your session may also have "
                            + "expired since you last used it.",
                    "/login", "Go to sign in");
            return;
        }

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
