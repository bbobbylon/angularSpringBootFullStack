package com.bob.angularspringbootfullstack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.cors.CorsConfigurationSource;

import org.springframework.web.filter.CorsFilter;


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
     * Servlet-level CORS filter, built from the application's single CORS definition.
     *
     * <p>It used to construct its own {@link org.springframework.web.cors.CorsConfiguration} from
     * {@code app.cors.allowed-origin-patterns} while {@code SecurityConfig} hardcoded a different
     * list — two policies, silently disagreeing. Because the security filter chain is what answers
     * preflights, the hardcoded one won and this configurable one never took effect. The divergence
     * stayed invisible only because the deployed shape serves the SPA and API from a single origin,
     * so almost nothing is genuinely cross-origin; it would have surfaced the moment a second
     * client, a mobile app, or a split-origin staging deploy appeared.
     *
     * <p>Both now read the one bean, so they cannot drift apart again. The parameter is injected
     * rather than the property, which is what makes that structural rather than a convention
     * somebody has to remember.
     *
     * @param corsConfigurationSource the application's CORS policy, defined in {@code SecurityConfig}
     * @return a servlet filter applying that same policy
     */
    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
