package com.bob.angularspringbootfullstack.exception;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalTime;


/*
this global handler is used to prevent the ApiException from being thrown and instead return a custom HttpResponse.
This is done so that we can return a custom error message to the user instead of the generic "401 Unauthorized" message from appearing to the end user.
This will help with giving extra context as to why an api endpoint is failing and provide a better end-user experience.
The GlobalExceptionHandler class uses the @RestControllerAdvice annotation, which is a Spring Boot mechanism for global exception handling.
When a controller method throws an exception (like ApiException), Spring automatically detects any class annotated with @RestControllerAdvice and invokes its @ExceptionHandler methods for matching exception types.
Spring scans for @RestControllerAdvice beans at startup and registers them as global exception handlers. When an exception is thrown during a REST request, Spring checks for a matching @ExceptionHandler method in these beans and calls it, allowing you to customize the HTTP response.
*/
@RestControllerAdvice
public class GlobalExceptionHandler {
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

