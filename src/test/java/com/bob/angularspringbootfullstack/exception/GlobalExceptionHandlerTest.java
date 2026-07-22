package com.bob.angularspringbootfullstack.exception;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for {@link GlobalExceptionHandler} — the single place every controller's
 * errors are turned into the standard {@code HttpResponse} envelope.
 * <p>
 * Uses {@link MockMvcBuilders#standaloneSetup} with a throwaway controller and the real handler
 * registered as controller advice, so the test exercises the genuine exception-to-envelope
 * mapping without booting the Spring context, the JWT security filter chain, or a datasource —
 * it runs in milliseconds in any environment, including CI with no MySQL. Standalone setup wires
 * the validator, so {@code @Valid} on the test body produces a real
 * {@link org.springframework.web.bind.MethodArgumentNotValidException}, the exact exception the
 * handler claims to catch.
 * <p>
 * These cases lock in the contract the Angular client and {@code CustomerService.handleError}
 * depend on: bad input yields a 400 in the standard shape with the field messages in
 * {@code reason}, and an unexpected failure yields a 500 whose {@code reason} is a generic,
 * client-safe string — never the raw exception text (NFR-SEC, see {@code handleGeneric}).
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("@Valid body failure → 400 envelope listing every field message")
    void invalidBody_returnsStandard400WithFieldMessages() throws Exception {
        // name is blank and age is below the @Min — both constraints should surface.
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"age\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode", is(400)))
                // HttpStatus serializes via its enum toString() — "400 BAD_REQUEST", not just the name.
                .andExpect(jsonPath("$.status", is("400 BAD_REQUEST")))
                .andExpect(jsonPath("$.reason", containsString("name")))
                .andExpect(jsonPath("$.reason", containsString("age")));
    }

    @Test
    @DisplayName("Malformed JSON → 400 with a generic 'malformed' reason (no parser internals)")
    void malformedJson_returnsGeneric400() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode", is(400)))
                .andExpect(jsonPath("$.reason", is("The request body is missing or malformed.")));
    }

    @Test
    @DisplayName("ApiException → 400 with the exception's own message as the reason")
    void apiException_returns400WithMessage() throws Exception {
        mockMvc.perform(post("/probe/api-error"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode", is(400)))
                .andExpect(jsonPath("$.reason", is("Customer not found")));
    }

    @Test
    @DisplayName("Unhandled exception → 500 with a generic reason that never leaks the cause")
    void unhandledException_returnsSafeGeneric500() throws Exception {
        mockMvc.perform(post("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.statusCode", is(500)))
                .andExpect(jsonPath("$.reason", is("An unexpected error occurred. Please try again.")))
                // The sensitive internal detail must NOT appear in the client-facing reason.
                .andExpect(jsonPath("$.reason", not(containsString("SECRET_DB_DETAIL"))));
    }

    /**
     * Throwaway controller whose only purpose is to raise each exception the handler maps.
     * It is registered solely with this test's standalone MockMvc, never component-scanned.
     */
    @RestController
    @Validated
    static class ProbeController {

        @PostMapping("/probe/body")
        public String body(@Valid @RequestBody Payload payload) {
            return "ok";
        }

        @PostMapping("/probe/api-error")
        public String apiError() {
            throw new ApiException("Customer not found");
        }

        @PostMapping("/probe/boom")
        public String boom() {
            throw new RuntimeException("SECRET_DB_DETAIL: connection string leaked");
        }
    }

    /** Minimal request body exercising two bean-validation constraints. */
    record Payload(@NotBlank String name, @Min(1) int age) {
    }
}
