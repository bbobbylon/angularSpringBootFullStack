package com.bob.angularspringbootfullstack.configuration;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;


/*
    QUICK FLOW CHECK: SecurityFilters (UsernamePasswordAuthFilter will be our filter, and it will route to the Security Flow, which would be ProviderManager (AuthManager), then goes over to the DoaAuthenticationProvider, which is our authentication provider, and then finally goes down to the InMemoryUserDetailsManager, which is our userDetailsService. The entire flow is basically: User making a request to authenticate, goes through the filter, then goes to the provider manager, then goes to the authentication provider, and then finally goes to the userDetailsService to check if the user exists and if the credentials are correct. If everything is correct, then the user is authenticated and can access the protected resources/endpoints.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
class SecurityConfig {

    private static final String[] PUBLIC_URLS = {};
    private final BCryptPasswordEncoder passwordEncoder;

    //this method is using the version 4 Spring Security config style. This is slightly different than our tutorial due to this. This file is used for disabling CSRF protection. This file is also being used to This is securing our application.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {

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
                        .requestMatchers(HttpMethod.POST, "/user/login").permitAll()
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
                        .accessDeniedHandler((request, response, exception2) -> response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access is very much denied!"))
                        .authenticationEntryPoint((request, response, exception3) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "You are not authorized to be accessing these endpoints! Check your tokens and come again!")));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // Temporary in-memory user for Boot 4 wiring; replace with DB-backed UserDetailsService later.
        return new InMemoryUserDetailsManager(
                User.withUsername("user@example.com")
                        .password(passwordEncoder.encode("password"))
                        .authorities("READ:USER", "READ:CUSTOMER")
                        .build()
        );
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