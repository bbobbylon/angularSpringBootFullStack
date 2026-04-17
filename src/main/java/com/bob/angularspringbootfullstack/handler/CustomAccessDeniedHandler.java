package com.bob.angularspringbootfullstack.handler;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;

import static java.time.LocalTime.now;
import static org.springframework.http.HttpStatus.FORBIDDEN;

// Spring already has this via accessDeniedHandler, but we are making our own to better suit our application. See SecurityConfig.securityFilterChain() for the commented out exceptionHandlers for more details
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
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
        // this is so that we can just write the JSON value directly into the response, which is an HTTPServletResponse!
        OutputStream out = response.getOutputStream();
        // this is to write the JSON value, and the mapper will convert the httpResponse object into a JSON string and write it to the output stream, which will then be sent back to the client as the response body.
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(out, httpResponse);
        out.flush();
    }
}
