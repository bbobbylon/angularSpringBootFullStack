package com.bob.angularspringbootfullstack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * AngularSpringBootFullStackApplication is the main entry point for the Spring Boot application.
 * 
 * This class is annotated with @SpringBootApplication, which is a convenience annotation that
 * combines @Configuration, @EnableAutoConfiguration, and @ComponentScan. It enables Spring Boot
 * to auto-configure the application context based on classpath dependencies and other settings.
 *
 * Responsibilities:
 * - Starts the Spring Boot application context
 * - Defines application-wide beans (like password encoder)
 * - Triggers component scanning from this package downward
 * - Enables auto-configuration for Spring Boot features
 */
@SpringBootApplication
public class AngularSpringBootFullStackApplication {
    /** BCrypt strength level for password hashing (higher = more secure but slower) */
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
     * 
     * This bean is used throughout the application for password encryption.
     * BCrypt is a deliberately slow hashing algorithm to resist brute-force attacks.
     * The STRENGTH constant (12) determines the computational cost - higher values
     * are more secure but slower (typical range 10-12).
     *
     * The encoder is injected into UserRepoImpl and other components that need
     * to hash passwords during registration and authentication.
     *
     * @return a configured BCryptPasswordEncoder with STRENGTH level
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(STRENGTH);
    }

}
