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

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
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
        // this is so that we can just write the JSON value directly into the response, which is an HTTPServletResponse!
        OutputStream out = response.getOutputStream();
        // this is to write the JSON value, and the mapper will convert the httpResponse object into a JSON string and write it to the output stream, which will then be sent back to the client as the response body.
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(out, httpResponse);
        out.flush();
    }
}
