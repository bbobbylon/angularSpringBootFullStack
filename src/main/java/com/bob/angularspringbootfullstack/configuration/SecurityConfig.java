package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.filter.CustomAuthFilter;
import com.bob.angularspringbootfullstack.handler.CustomAccessDeniedHandler;
import com.bob.angularspringbootfullstack.handler.CustomAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;


/**
 * Spring Security configuration for the application.
 * <p>
 * Defines the SecurityFilterChain, CORS settings, and the AuthenticationManager
 * (DaoAuthenticationProvider + BCryptPasswordEncoder) used to authenticate users
 * loaded by UserRepoImpl (UserDetailsService). Sessions are stateless; JWT tokens
 * are validated by CustomAuthFilter, which is registered before
 * UsernamePasswordAuthenticationFilter. Authorization is permission-based: the
 * permission string on the user's Role is split into SimpleGrantedAuthority
 * instances and matched against hasAnyAuthority(...) rules.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
@Slf4j
class SecurityConfig {
    /**
     * Logger for security configuration events and filter chain diagnostics.
     * Scoped to this class so log output is identifiable in multi-module deployments.
     */
    private static final Logger securityLogger = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * URL patterns that bypass JWT authentication entirely.
     * <p>
     * Includes registration, login, MFA verification, password reset, token refresh,
     * profile images, and Actuator endpoints. Any path not listed here requires a
     * valid JWT and the appropriate authority.
     */
    private static final String[] PUBLIC_URLS =
            {"/user/login/**",
                    "/user/verify/code/**", "/user/register/**", "/actuator/**",
                    "/user/resetpassword/**", "/user/verify/password/**",
                    "/user/verify/account/**", "/user/refresh/token/**",
                    "/user/profile/image/**", "/user/image/**",
                    // application.yml maps server.error.path to /user/error so the global
                    // exception handler can render a clean JSON envelope. That path must
                    // be reachable without auth — otherwise an error dispatched on a public
                    // endpoint (e.g. failed login for an unknown email) would itself be
                    // blocked by the security filter, surface as "response already
                    // committed", and turn into an opaque 500.
                    "/user/error",
            };

    /**
     * GET-only patterns that match Angular's static build output served from
     * {@code src/main/resources/static/} when the SPA is embedded in the JAR.
     * <p>
     * Angular's production build emits hashed bundles at the root (for example
     * {@code main-AB12CD.js}, {@code styles-AB12CD.css}), and supporting assets
     * under {@code /assets/**}. These need to load before the user is
     * authenticated — otherwise the login page itself can't render.
     */
    private static final String[] SPA_STATIC_RESOURCES = {
            "/",
            "/index.html",
            "/favicon.ico",
            "/*.js", "/*.css", "/*.map",
            "/*.svg", "/*.png", "/*.jpg", "/*.jpeg", "/*.gif", "/*.webp", "/*.avif", "/*.ico",
            "/*.woff", "/*.woff2", "/*.ttf", "/*.eot",
            "/assets/**", "/media/**"
    };

    /**
     * JWT validation filter inserted before {@link UsernamePasswordAuthenticationFilter}.
     * Parses and validates the Bearer token on every request and populates the
     * {@code SecurityContext} with the authenticated principal on success.
     */
    private final CustomAuthFilter customAuthFilter;

    /**
     * BCrypt password encoder used by the {@link DaoAuthenticationProvider} to
     * verify stored password hashes during login.
     */
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * Handles {@code 403 Forbidden} responses when an authenticated user lacks
     * the required authority for the requested resource.
     */
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    /**
     * Handles {@code 401 Unauthorized} responses when a request arrives without
     * a valid JWT or with an expired token.
     */
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

    /**
     * Builds the application's SecurityFilterChain.
     * <p>
     * Disables CSRF (stateless JWT API doesn't need it) and HTTP Basic, enables
     * CORS using {@link #corsConfigurationSource()}, sets the session policy to
     * STATELESS, declares which URLs are public vs. authority-gated, and wires
     * in the custom 401 (entry point) and 403 (access denied) handlers.
     * CustomAuthFilter is registered before UsernamePasswordAuthenticationFilter
     * so JWTs are validated and an Authentication is placed in the
     * SecurityContext before authorization rules run.
     *
     * @param http HttpSecurity builder provided by Spring Security
     * @return the configured SecurityFilterChain bean
     * @throws Exception if an error occurs during configuration
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        securityLogger.debug("Configuring SecurityFilterChain: CSRF, CORS, session management, authorization rules");

        try {
            http
                    //this section is for customizing our HTTP security headers.
                    .headers(headers -> headers
                            .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                            .contentTypeOptions(Customizer.withDefaults())
                            .httpStrictTransportSecurity(hsts -> hsts
                                    .includeSubDomains(true)
                                    .maxAgeInSeconds(31536000))
                    )
                    .csrf(AbstractHttpConfigurer::disable)
                    .cors(configure -> configure.configurationSource(corsConfigurationSource()))
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(POST, "/user/register").permitAll()
                            .requestMatchers(POST, "/user/login").permitAll()
                            .requestMatchers("/actuator/**").permitAll()
                            .requestMatchers(PUBLIC_URLS).permitAll()
                            // SPA bootstrap files must be reachable without auth so login can render.
                            .requestMatchers(GET, SPA_STATIC_RESOURCES).permitAll()
                            .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
                            .requestMatchers(DELETE, "/customer/delete/**").hasAnyAuthority("DELETE:CUSTOMER")
                            .requestMatchers(GET, "/**").hasAnyAuthority("READ:USER", "READ:CUSTOMER")
                            .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER")
                            .requestMatchers(PUT, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER", "UPDATE:ROLE")
                            .anyRequest().authenticated()
                    )
                    .addFilterBefore(customAuthFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex
                            .accessDeniedHandler(customAccessDeniedHandler)
                            .authenticationEntryPoint(customAuthenticationEntryPoint)
                    );

            return http.build();
        } catch (Exception e) {
            log.info("Error configuring SecurityFilterChain: {}", e.getMessage());
            throw new Exception(e);
        }
    }

    /**
     * Builds the CORS policy applied to every path.
     * <p>
     * Whitelists the development and production frontend origins, the request
     * headers the frontend may send (including Authorization), the response
     * headers the frontend may read (so it can pick up new JWTs), and the
     * permitted HTTP methods. Credentials are allowed because the frontend
     * sends an Authorization header.
     *
     * @return a CorsConfigurationSource registered for "/**"
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.setAllowedOrigins(List.of(
                "http://localhost:4200",
                "http://localhost:3000",
                "https://angularsecureapp.org"
        ));
        corsConfiguration.setAllowedHeaders(Arrays.asList(
                "Origin",
                "Access-Control-Allow-Origin",
                "Content-Type",
                "Accept",
                "Jwt-Token",
                "Authorization",
                "X-Requested-With",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));
        corsConfiguration.setExposedHeaders(Arrays.asList(
                "Origin",
                "Content-Type",
                "Accept",
                "Jwt-Token",
                "Authorization",
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials",
                "File-Name"
        ));
        corsConfiguration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return source;
    }

    /**
     * Builds the AuthenticationManager used to authenticate login requests.
     * <p>
     * Wraps a single DaoAuthenticationProvider in a ProviderManager. The
     * provider loads users via the supplied UserDetailsService (UserRepoImpl)
     * and verifies passwords with the BCryptPasswordEncoder bean.
     * setHideUserNotFoundExceptions(false) lets UsernameNotFoundException
     * propagate so the global handler can map it explicitly.
     *
     * @param userDetailsService the UserDetailsService bean (UserRepoImpl) used to load users
     * @return the configured AuthenticationManager bean
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService) {
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        daoAuthenticationProvider.setHideUserNotFoundExceptions(false);
        return new ProviderManager(daoAuthenticationProvider);
    }
}
