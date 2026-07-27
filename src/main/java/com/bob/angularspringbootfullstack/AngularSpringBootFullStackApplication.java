package com.bob.angularspringbootfullstack;

import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * AngularSpringBootFullStackApplication is the main entry point for the Spring Boot application.
 * <p>
 * This class is annotated with @SpringBootApplication, which is a convenience annotation that
 * combines @Configuration, @EnableAutoConfiguration, and @ComponentScan. It enables Spring Boot
 * to autoconfigure the application context based on classpath dependencies and other settings.
 * <p>
 * Responsibilities:
 * - Starts the Spring Boot application context
 * - Defines application-wide beans (like password encoder)
 * - Triggers component scanning from this package downward
 * - Enables autoconfiguration for Spring Boot features
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class AngularSpringBootFullStackApplication {
    /**
     * BCrypt strength level for password hashing (higher = more secure but slower)
     */
    private static final int STRENGTH = 12;

    /**
     * Main entry point for the application.
     * Starts the Spring Boot application and loads the context.
     *
     * @param args command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(AngularSpringBootFullStackApplication.class, args);
    }

    /**
     * Creates and configures a BCryptPasswordEncoder bean.
     * <p>
     * This bean is used throughout the application for password encryption.
     * BCrypt is a deliberately slow hashing algorithm to resist brute-force attacks.
     * The STRENGTH constant (12) determines the computational cost - higher values
     * are more secure but slower (typical range 10-12).
     * <p>
     * The encoder is injected into UserRepoImpl and other components that need
     * to hash passwords during registration and authentication.
     *
     * @return a configured BCryptPasswordEncoder with STRENGTH level
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(STRENGTH);
    }


    /**
     * Origin patterns the browser is allowed to call this API from, comma-separated.
     * <p>
     * Sourced from configuration ({@code app.cors.allowed-origin-patterns}, env
     * {@code CORS_ALLOWED_ORIGINS}) rather than hardcoded, because the correct answer is
     * different in every environment and baking a list into the jar means a rebuild to
     * change it — and, worse, it means the prod jar ships whatever placeholder domain was
     * convenient at development time.
     * <p>
     * Per-profile values live in {@code application-*.yml}: dev allows the LAN so the app
     * can be opened from a phone, prod names the real origin(s) and nothing else.
     */
    @Value("${app.cors.allowed-origin-patterns}")
    private String allowedOriginPatterns;

    // CORS Filter configuration. Basic boilerplate that is used in almost all Spring Boot applications. It does the same thing as the @CrossOrigin annotation but applies globally to all endpoints. It allows the frontend (running on a different port) to make requests to the backend without being blocked by the browser's same-origin policy. The allowed origins are specified in the configuration, and you can adjust them as needed for your development and production environments.
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource urlBasedCorsConfigurationSource = new UrlBasedCorsConfigurationSource();
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        // setAllowedOriginPATTERNS, not setAllowedOrigins: with allowCredentials(true) the
        // CORS spec forbids the "*" wildcard, and Spring enforces that by REFUSING to start
        // if setAllowedOrigins contains one. Patterns are the supported way to express
        // "any host on my LAN" — Spring matches the request's Origin against them and
        // echoes back that exact origin, which is spec-compliant. Exact origins (the prod
        // case) are still written literally and still match exactly.
        corsConfiguration.setAllowedOriginPatterns(
                Arrays.stream(allowedOriginPatterns.split(","))
                        .map(String::trim)
                        .filter(pattern -> !pattern.isEmpty())
                        .toList());
        corsConfiguration.setAllowedHeaders(Arrays.asList("Origin", "Access-Control-Allow-Origin", "Content-Type",
                "Accept", "Jwt-Token", "Authorization", "Origin", "Accept", "X-Requested-With",
                "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        corsConfiguration.setExposedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Jwt-Token", "Authorization",
                "Access-Control-Allow-Origin", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials", "File-Name"));
        corsConfiguration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        urlBasedCorsConfigurationSource.registerCorsConfiguration("/**", corsConfiguration);
        return new CorsFilter(urlBasedCorsConfigurationSource);
    }
}
