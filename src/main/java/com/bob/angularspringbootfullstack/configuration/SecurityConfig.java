package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.handler.CustomAccessDeniedHandler;
import com.bob.angularspringbootfullstack.handler.CustomAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;


/*
    QUICK FLOW CHECK: SecurityFilters (UsernamePasswordAuthFilter will be our filter, and it will route to the Security Flow, which would be ProviderManager (AuthManager), then goes over to the DoaAuthenticationProvider, which is our authentication provider, and then finally goes down to the InMemoryUserDetailsManager, which is our userDetailsService. The entire flow is basically: User making a request to authenticate, goes through the filter, then goes to the provider manager, then goes to the authentication provider, and then finally goes to the userDetailsService to check if the user exists and if the credentials are correct. If everything is correct, then the user is authenticated and can access the protected resources/endpoints.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
// this is to allow control and lets admins access certain methods. It is also necessary for method level security.
@EnableMethodSecurity()
class SecurityConfig {

    private static final Logger securityLogger = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String[] PUBLIC_URLS = {"/user/login/**"};
    //here we will inject some BEANS
    private final BCryptPasswordEncoder passwordEncoder;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    //private final JwtAuthFilter jwtAuthFilter;

    //this method is using the version 4 Spring Security config style. This is slightly different than our tutorial due to this. This file is used for disabling CSRF protection. This file is also being used to This is securing our application.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        securityLogger.debug("Configuring SecurityFilterChain: setting up CSRF, CORS, session management, and authorization rules.");
        http
                //we want to disable this because we don't need cross-site request forgery protection for the app. This is also using Lambda syntax/style!
                .csrf(AbstractHttpConfigurer::disable)
                //CORS is also not needed because we will be using our own configuration later.
                .cors(configure -> configure.configurationSource(corsConfigurationSource()))
                // we are disabling this because we won't be using basic authentication. We will be using JWT
                .httpBasic(AbstractHttpConfigurer::disable)
                // this is the non-lambda style but still works --------> .csrf(csrf -> csrf.disable())
                // we won't be tracking sessions via cookies because we are dealing with just one token.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(STATELESS))
                // here, we are passing in the requests we want to allow without authentication. This has moved from antMatchers to requestMatchers in versions 5.7 and higher of Spring Security.
                // hasAnyAuthority is going to be used here to check if user has proper auth.
                .authorizeHttpRequests(auth -> auth
                        // anybody can come to our public multiple URLs to try and authenticate.
                        .requestMatchers(POST, "/user/register").permitAll()
                        .requestMatchers(POST, "/user/login").permitAll()
                        // actuator is only for testing purposes to make sure endpoints are working
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        // these authorities, such as "DELETE:USER" etc. are being used in the UserServiceImpl class when we are creating the user and assigning them roles and authorities. We will be using these authorities to check if the user has the proper authority to access certain endpoints.
                        .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
                        .requestMatchers(DELETE, "/customer/delete/**").hasAnyAuthority("DELETE:CUSTOMER")
                        .requestMatchers(GET, "/**").hasAnyAuthority("READ:USER", "READ:CUSTOMER")
                        .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER")
                        .requestMatchers(PUT, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER", "UPDATE:ROLE")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler(customAccessDeniedHandler)
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                );
        // Add the JWT filter at the correct place in the filter chain
        // http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.setAllowedOrigins(List.of("http://localhost:4200", "http://localhost:3000", "http://angularsecureapp.org", "192.168.1.164"));
        corsConfiguration.setAllowedHeaders(Arrays.asList("Origin", "Access-Control-Allow-Origin", "Content-Type",
                "Accept", "Jwt-Token", "Authorization", "Origin", "Accept", "X-Requested-With",
                "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        corsConfiguration.setExposedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Jwt-Token", "Authorization",
                "Access-Control-Allow-Origin", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials", "File-Name"));
        corsConfiguration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return source;
    }

    // managing the authentication manager and provider below. This is needed to process the actual authentication request that users are making.
    /*
    Flow:
    create DaoAuthenticationProvider -> set the userDetailsService (which is the InMemoryUserDetailsManager) -> set the password encoder (which is the BCryptPasswordEncoder) -> create a ProviderManager and pass in the DaoAuthenticationProvider to it -> return the ProviderManager as the AuthenticationManager bean.
     * - DaoAuthenticationProvider requires a real UserDetailsService to load users from the database.
    * - setUserDetailsService(null) is only a placeholder and will not support real authentication.
     */
    // We need to return an authentication manager. At the same time we create the authprovider, we will give it our userdetails service and pwd encoder, and we return a new provider manager. Now the authprovider knows about our userdetails and the password encoder! Powerful stuff hapening in just a few lines of code.
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService) {
        // we are going to call this constructor and then pass in our authentication provider, which is the DaoAuthenticationProvider, and the parameters we will give it are: encoder - what we use to encode our password, and userDetailsService which is the users we have in our database. Spring Security 7 now requires that the DaoAuthenticatoinProvider gives one of the parameters which is our user details service.
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(daoAuthenticationProvider);
    }
}