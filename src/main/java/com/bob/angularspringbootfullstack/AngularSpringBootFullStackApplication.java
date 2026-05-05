package com.bob.angularspringbootfullstack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * AngularSpringBootFullStackApplication is the main entry point for the Spring Boot application.
 * <p>
 * This class is annotated with @SpringBootApplication, which is a convenience annotation that
 * combines @Configuration, @EnableAutoConfiguration, and @ComponentScan. It enables Spring Boot
 * to auto-configure the application context based on classpath dependencies and other settings.
 * <p>
 * Responsibilities:
 * - Starts the Spring Boot application context
 * - Defines application-wide beans (like password encoder)
 * - Triggers component scanning from this package downward
 * - Enables auto-configuration for Spring Boot features
 */
@SpringBootApplication
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


    // CORS Filter configuration. Basic boilerplate that is used in almost all Spring Boot applications. It does the same thing as the @CrossOrigin annotation but applies globally to all endpoints. It allows the frontend (running on a different port) to make requests to the backend without being blocked by the browser's same-origin policy. The allowed origins are specified in the configuration, and you can adjust them as needed for your development and production environments.
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource urlBasedCorsConfigurationSource = new UrlBasedCorsConfigurationSource();
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.setAllowedOrigins(List.of("http://localhost:4200", "http://localhost:3000", "http://securecapita.org"));
        //corsConfiguration.setAllowedOrigins(Arrays.asList("*"));
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
