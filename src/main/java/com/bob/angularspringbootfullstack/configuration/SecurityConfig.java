package com.bob.angularspringbootfullstack.configuration;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;


/*
    QUICK FLOW CHECK: SecurityFilters (UsernamePasswordAuthFilter will be our filter, and it will route to the Security Flow, which would be ProviderManager (AuthManager), then goes over to the DoaAuthenticationProvider which is our authentication provider, and then finally goes down to the InMemoryUserDetailsManager which is our userDetailsService. The entire flow is basically: User makes request to authenticate, goes through the filter, then goes to the provider manager, then goes to the authentication provider, and then finally goes to the userDetailsService to check if the user exists and if the credentials are correct. If everything is correct, then the user is authenticated and can access the protected resources/endpoints.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String[] PUBLIC_URLS = {};

    //this method is using the version 4 Spring Security config style. This is slightly different than our tutorial due to this. This file is used for disabling CSRF protection. This file is also being used to This is securing our application.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                //we want to disable this because we don't need cross-site request forgery protection for the app. This is also using Lambda syntax/style!
                .csrf(AbstractHttpConfigurer::disable)
                //CORS is also not needed because we will be using our own configuration later.
                .cors(AbstractHttpConfigurer::disable)
                // this is the non-lambda style but still works --------> .csrf(csrf -> csrf.disable())
                // we won't be tracking sessions via cookies because we are dealing with just one token.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // here, we are passing in the requests we want to allow without authentication. This has moved from antMatchers to requestMatchers in versions 5.7 and higher of Spring Security.
                // hasAnyAuthority is going to be used here to check if user has proper auth.
                .authorizeHttpRequests(auth -> auth
                        // anybody can come to our public multiple URLs to try and authenticate.
                        .requestMatchers(HttpMethod.POST, "/user/register").permitAll()
                        // actuator is only for testing purposes to make sure endpoints are working
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        // these authorities, such as "DELETE:USER" etc. are being used in the UserServiceImpl class when we are creating the user and assigning them roles and authorities. We will be using these authorities to check if the user has the proper authority to access certain endpoints.
                        .requestMatchers(HttpMethod.DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
                        .requestMatchers(HttpMethod.DELETE, "/customer/delete/**").hasAnyAuthority("DELETE:CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/**").hasAnyAuthority("READ:USER", "READ:CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER")
                        .requestMatchers(HttpMethod.PUT, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER", "UPDATE:ROLE")
                        .anyRequest().authenticated()
                )
                // we may need this later since we are using JWT TOKENS -- > .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // this is for handling some exceptions when users are trying to access these endpoints/resources without the correct tokens or authentication. We are using Lambda for this entire method.
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler((request, response, exception2) -> {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access is very much denied!");
                        })
                        .authenticationEntryPoint((request, response, exception3) -> {
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "You are not authorized to be accessing these endpoints! Check your tokens and come again!");
                        }));
        return http.build();
    }

    // managing the authentication manager and provider below. This is needed in order to process the actual authentication request that users are making.
}