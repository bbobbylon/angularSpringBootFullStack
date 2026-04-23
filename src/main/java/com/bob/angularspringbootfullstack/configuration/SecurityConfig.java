package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.filter.CustomAuthFilter;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.springframework.http.HttpMethod.*;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;


/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * SPRING SECURITY COMPLETE FLOW DOCUMENTATION
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * When a user makes a login request, the following happens:
 * <p>
 * 1. HTTP REQUEST ARRIVES at /user/login with LoginForm (email, password)
 * ↓
 * 2. SecurityFilterChain intercepts the request
 * - UsernamePasswordAuthenticationFilter checks if this is a login endpoint
 * - Creates UsernamePasswordAuthenticationToken(email, password)
 * ↓
 * 3. AuthenticationManager.authenticate() is called with the token
 * - This delegates to DaoAuthenticationProvider
 * ↓
 * 4. DaoAuthenticationProvider:
 * a) Calls userDetailsService.loadUserByUsername(email)
 * → Goes to UserRepoImpl (implements UserDetailsService)
 * → Calls getUserByEmail(email) from database
 * → Gets User entity
 * → Gets user's role via roleRepository.getRoleByUserId()
 * → Creates UserPrincipal(user, authorities)
 * → UserPrincipal implements UserDetails interface (needed by Spring Security)
 * b) Gets the password hash from UserPrincipal
 * c) Compares provided password with stored BCrypt hash using passwordEncoder
 * d) If matches: Creates Authentication token with authorities (GrantedAuthority objects)
 * e) If doesn't match: Throws BadCredentialsException
 * ↓
 * 5. If authentication succeeds:
 * - Spring Security sets SecurityContextHolder with authenticated principal
 * - Controller method executes
 * - Application generates JWT token (optional next step)
 * ↓
 * 6. If authentication fails OR user tries to access protected resource without auth:
 * - CustomAuthenticationEntryPoint.commence() is called → returns 401 Unauthorized
 * ↓
 * 7. If authenticated user lacks required authorities:
 * - CustomAccessDeniedHandler.handle() is called → returns 403 Forbidden
 * <p>
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * KEY COMPONENTS:
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * <p>
 * UserDetailsService Interface:
 * - Spring Security contract for loading user information
 * - We implement it in UserRepoImpl
 * - Must implement: loadUserByUsername(String username) → UserDetails
 * - Returns UserPrincipal which implements UserDetails
 * <p>
 * UserDetails Interface:
 * - Spring Security contract for user information
 * - We implement it with UserPrincipal
 * - Must implement:
 * * getAuthorities() → returns GrantedAuthority objects
 * * getPassword() → returns BCrypted password
 * * getUsername() → returns email (used as username)
 * * isAccountNonExpired() → is account still valid?
 * * isAccountNonLocked() → is account locked?
 * * isCredentialsNonExpired() → did password not expire?
 * * isEnabled() → is account enabled?
 * <p>
 * GrantedAuthority:
 * - Represents a permission/role the user has
 * - Simple implementation: SimpleGrantedAuthority("ROLE_USER")
 * - Used in @Secured, @PreAuthorize annotations
 * - Examples: "ROLE_USER", "READ:USER", "DELETE:USER"
 * <p>
 * Authentication:
 * - Represents an authenticated user in Spring Security
 * - Contains: principal (UserPrincipal), credentials (password), authorities (roles)
 * - Stored in SecurityContextHolder for duration of request
 * <p>
 * SecurityContext:
 * - Holds the Authentication for the current request/thread
 * - Accessed via SecurityContextHolder.getContext()
 * - ThreadLocal storage - each request thread has its own context
 * <p>
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
/**
 * @EnableMethodSecurity enables method-level security annotations like @Secured, @PreAuthorize
 * This allows you to control access to individual methods based on authorities
 */
@EnableMethodSecurity()
class SecurityConfig {
    private static final Logger securityLogger = LoggerFactory.getLogger(SecurityConfig.class);
    /**
     * Public URLs that don't require authentication
     */
    private static final String[] PUBLIC_URLS = {"/user/login/**", "/user/verify/code/**", "/user/register/**", "/actuator/**"};
    private final CustomAuthFilter customAuthFilter;
    /**
     * BCrypt password encoder with strength 12 - used to hash/verify passwords
     */
    private final BCryptPasswordEncoder passwordEncoder;
    /**
     * Handles 403 Forbidden responses when authenticated users lack permissions
     */
    private final CustomAccessDeniedHandler customAccessDeniedHandler;
    /**
     * Handles 401 Unauthorized responses when unauthenticated users access protected resources
     */
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

    /**
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * SECURITY FILTER CHAIN CONFIGURATION
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * This bean defines the security policy for the entire application. Every HTTP request
     * passes through this filter chain. The chain makes decisions about:
     * - Which URLs require authentication
     * - Which authorities are needed for different endpoints
     * - How to handle security exceptions
     * - Session management strategy
     * - CSRF and CORS settings
     * <p>
     * REQUEST LIFECYCLE:
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * 1. HTTP REQUEST arrives at any endpoint
     * ↓
     * 2. SecurityFilterChain evaluates the request against rules
     * ↓
     * 3. For PROTECTED endpoints:
     * - Check if user is authenticated
     * - If YES: Check if user has required authorities
     * - If HAS authority: Allow request to proceed to controller
     * - If NO authority: → customAccessDeniedHandler.handle() → 403 Forbidden
     * - If NO authentication: → customAuthenticationEntryPoint.commence() → 401 Unauthorized
     * ↓
     * 4. For PUBLIC endpoints (/user/login, /user/register):
     * - Allow request to proceed without authentication check
     * - Controller handles the request
     * ↓
     * 5. For endpoints requiring specific AUTHORITY:
     * - Examples: hasAnyAuthority("DELETE:USER", "READ:CUSTOMER")
     * - GrantedAuthority must match exactly (case-sensitive)
     * - Authorities come from UserPrincipal.getAuthorities()
     * → Which comes from Role.permission field
     * → Which is split by comma: "READ:USER,UPDATE:USER,DELETE:USER"
     * → Each authority becomes a SimpleGrantedAuthority
     * <p>
     * AUTHORITY vs ROLE vs PERMISSION:
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * In Spring Security:
     * - ROLE: A group of permissions. Example: "ROLE_USER", "ROLE_ADMIN"
     * Convention: prefix with "ROLE_"
     * <p>
     * - AUTHORITY: Specific permission. Example: "READ:USER", "DELETE:USER"
     * No prefix convention, can be anything
     * <p>
     * - PERMISSION: The database representation.
     * In our DB: Role.permission = "READ:USER,UPDATE:USER,DELETE:USER"
     * <p>
     * In this application, we use AUTHORITIES (not roles) for fine-grained control:
     * Example: User with ROLE_USER might have authorities: "READ:USER", "UPDATE:USER"
     * User with ROLE_ADMIN might have authorities: "READ:USER", "READ:CUSTOMER", "DELETE:USER", "DELETE:CUSTOMER"
     *
     * @param http the HttpSecurity object that we configure
     * @return the built SecurityFilterChain bean
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        securityLogger.debug("═══ Configuring SecurityFilterChain: setting up CSRF, CORS, session management, and authorization rules ═══");

        http
                /**
                 * CSRF (Cross-Site Request Forgery) PROTECTION
                 * ───────────────────────────────────────────────────────────────────────────────
                 * .csrf(AbstractHttpConfigurer::disable)
                 *
                 * WHY DISABLED:
                 * - We're building a REST API with stateless authentication (JWT/Token-based)
                 * - CSRF attacks require cookies to be automatically sent by browsers
                 * - We don't use cookies for authentication; we use headers (Authorization: Bearer <token>)
                 * - Headers are NOT automatically sent like cookies, so no CSRF vulnerability
                 *
                 * If we were using cookie-based sessions: CSRF would be ENABLED
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /**
                 * CORS (Cross-Origin Resource Sharing) CONFIGURATION
                 * ───────────────────────────────────────────────────────────────────────────────
                 * .cors(configure -> configure.configurationSource(corsConfigurationSource()))
                 *
                 * WHY CONFIGURED:
                 * - Our frontend (Angular on localhost:4200) makes requests to backend (localhost:8080)
                 * - Different origins → browser blocks by default (Same-Origin Policy)
                 * - CORS tells browser: "It's OK to receive requests from localhost:4200"
                 *
                 * corsConfigurationSource() specifies:
                 * - Allowed origins: localhost:4200, localhost:3000, etc.
                 * - Allowed methods: GET, POST, PUT, DELETE, etc.
                 * - Allowed headers: Authorization, Content-Type, etc.
                 * - Exposed headers: JWT-Token, Authorization, etc.
                 *
                 * FLOW:
                 * 1. Browser sees request to different origin
                 * 2. Browser sends preflight OPTIONS request
                 * 3. CORS config response tells browser if request is allowed
                 * 4. Browser sends actual request if allowed
                 */
                .cors(configure -> configure.configurationSource(corsConfigurationSource()))

                /**
                 * HTTP BASIC AUTHENTICATION
                 * ───────────────────────────────────────────────────────────────────────────────
                 * .httpBasic(AbstractHttpConfigurer::disable)
                 *
                 * WHY DISABLED:
                 * - HTTP Basic sends credentials as Base64 in every request
                 * - Security risk: credentials visible in every request
                 * - We use JWT tokens instead: send token once, then use in Authorization header
                 * - More secure: if token compromised, we can revoke it; credentials can't change per-request
                 */
                .httpBasic(AbstractHttpConfigurer::disable)

                /**
                 * SESSION MANAGEMENT
                 * ───────────────────────────────────────────────────────────────────────────────
                 * .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                 *
                 * SessionCreationPolicy.STATELESS means:
                 * - No HttpSession created by Spring Security
                 * - No cookies tracking user between requests
                 * - Each request is independent
                 * - Authentication info in request (JWT token in Authorization header)
                 * - Scales better: no session storage needed on server
                 *
                 * WHY STATELESS:
                 * - REST APIs should be stateless
                 * - Microservices can't share session data
                 * - Allows horizontal scaling (multiple server instances)
                 * - JWT is self-contained: contains user info + authorities
                 *
                 * ALTERNATIVE (STATEFUL - NOT USED HERE):
                 * - SessionCreationPolicy.IF_REQUIRED
                 * - SessionCreationPolicy.ALWAYS
                 * These create HttpSession and store auth in session/cookie
                 */
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))

                /**
                 * AUTHORIZATION RULES - CRITICAL PART
                 * ───────────────────────────────────────────────────────────────────────────────
                 * .authorizeHttpRequests() defines which URLs require what authorities
                 *
                 * Rules are evaluated in ORDER - first match wins!
                 * Put more specific rules first, general rules last
                 */
                .authorizeHttpRequests(auth -> auth
                        /**
                         * PUBLIC ENDPOINTS - permitAll()
                         * ───────────────────────────────────────────────────────────────────────
                         * Anyone can access these without authentication
                         * Exceptions for login/register are added here
                         */
                        .requestMatchers(POST, "/user/register").permitAll()
                        .requestMatchers(POST, "/user/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(PUBLIC_URLS).permitAll()

                        /**
                         * AUTHORITY-BASED AUTHORIZATION
                         * ───────────────────────────────────────────────────────────────────────
                         *
                         * hasAnyAuthority() checks if user has AT LEAST ONE of the listed authorities
                         *
                         * Authority comes from:
                         * - Role.permission (e.g., "READ:USER,UPDATE:USER,DELETE:USER")
                         * - Split by comma
                         * - Each becomes a SimpleGrantedAuthority (case-sensitive!)
                         * - UserPrincipal.getAuthorities() returns List<GrantedAuthority>
                         *
                         * FLOW EXAMPLE - User tries DELETE /user/delete/5:
                         * 1. SecurityFilterChain matches .requestMatchers(DELETE, "/user/delete/**")
                         * 2. Rule is: .hasAnyAuthority("DELETE:USER")
                         * 3. Gets authenticated user from SecurityContextHolder
                         * 4. Gets UserPrincipal from Authentication.getPrincipal()
                         * 5. Calls UserPrincipal.getAuthorities() → returns List of GrantedAuthority
                         * 6. Checks if any authority matches "DELETE:USER"
                         * 7. If YES: allows request to controller
                         * 8. If NO: calls CustomAccessDeniedHandler → returns 403
                         */
                        .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
                        .requestMatchers(DELETE, "/customer/delete/**").hasAnyAuthority("DELETE:CUSTOMER")
                        .requestMatchers(GET, "/**").hasAnyAuthority("READ:USER", "READ:CUSTOMER")
                        .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER")
                        .requestMatchers(PUT, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER", "UPDATE:ROLE")

                        /**
                         * DEFAULT RULE
                         * ───────────────────────────────────────────────────────────────────────
                         * .anyRequest().authenticated()
                         *
                         * ALL OTHER requests must be authenticated
                         * Means: user must be logged in (have valid token/session)
                         * But: no specific authority check (as long as authenticated, allowed)
                         *
                         * If not authenticated: CustomAuthenticationEntryPoint → 401
                         */
                        .anyRequest().authenticated()

                )
                .addFilterBefore(customAuthFilter, UsernamePasswordAuthenticationFilter.class)
                /**
                 * EXCEPTION HANDLING
                 * ───────────────────────────────────────────────────────────────────────────────
                 * .exceptionHandling() configures handlers for security exceptions
                 */
                .exceptionHandling(ex -> ex
                        /**
                         * accessDeniedHandler: Called when user IS AUTHENTICATED but lacks authorities
                         * Example: User has ROLE_USER but tries to DELETE resource (needs ROLE_ADMIN)
                         * Response: HTTP 403 Forbidden
                         * Handler: CustomAccessDeniedHandler.handle()
                         */
                        .accessDeniedHandler(customAccessDeniedHandler)

                        /**
                         * authenticationEntryPoint: Called when user is NOT AUTHENTICATED
                         * Example: Missing JWT token or token is invalid
                         * Response: HTTP 401 Unauthorized
                         * Handler: CustomAuthenticationEntryPoint.commence()
                         */
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                );

        // Note: JWT filter would be added here in .addFilterBefore() if using custom JWT filtering
        // http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * CORS (CROSS-ORIGIN RESOURCE SHARING) CONFIGURATION
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * CORS allows our Angular frontend (different origin) to make requests to our backend API.
     * <p>
     * Browser Same-Origin Policy:
     * ─────────────────────────────────────────────────────────────────────────────────────────
     * Frontend (http://localhost:4200) tries to fetch from Backend (http://localhost:8080)
     * → Different origins (different port)
     * → Browser BLOCKS by default (Same-Origin Policy)
     * <p>
     * CORS Solution:
     * ─────────────────────────────────────────────────────────────────────────────────────────
     * 1. Browser sees cross-origin request
     * 2. Browser sends preflight OPTIONS request:
     * OPTIONS /user/login
     * Origin: http://localhost:4200
     * Access-Control-Request-Method: POST
     * 3. Server responds with allowed origins/methods/headers
     * 4. Browser checks response against request
     * 5. If allowed: sends actual POST request
     * 6. If blocked: throws CORS error in console
     *
     * @return CorsConfigurationSource configured for frontend origins
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var corsConfiguration = new CorsConfiguration();

        // Allow credentials (Authorization header, cookies) in cross-origin requests
        corsConfiguration.setAllowCredentials(true);

        /**
         * ALLOWED ORIGINS: Which frontend domains can make requests to this backend
         * ─────────────────────────────────────────────────────────────────────────
         * - http://localhost:4200: Angular dev server (ng serve)
         * - http://localhost:3000: React dev server or other frontend
         * - http://angularsecureapp.org: Production domain
         * - 192.168.1.164: Local network IP for testing
         *
         * Security: Only add origins you trust!
         * NEVER use "*" with credentials=true (inconsistent per spec)
         */
        corsConfiguration.setAllowedOrigins(List.of(
                "http://localhost:4200",
                "http://localhost:3000",
                "http://angularsecureapp.org",
                "192.168.1.164"
        ));

        /**
         * ALLOWED HEADERS: Which request headers can be sent in cross-origin requests
         * ─────────────────────────────────────────────────────────────────────────
         * - Origin: Where request comes from (browser auto-adds)
         * - Content-Type: What format data is in (application/json)
         * - Authorization: JWT token (Authorization: Bearer <token>)
         * - Jwt-Token: Custom token header (if used)
         *
         * These let frontend send Authorization header with JWT token
         */
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

        /**
         * EXPOSED HEADERS: Which response headers frontend JavaScript can read
         * ─────────────────────────────────────────────────────────────────────
         * JavaScript can only read certain headers by default (security measure)
         * These headers must be explicitly exposed:
         *
         * Example: Backend sends response:
         * HTTP/1.1 200 OK
         * Content-Type: application/json
         * Authorization: Bearer <new_jwt_token>
         *
         * Without exposing "Authorization", frontend JS cannot read it
         * So we expose it so frontend can extract new token from response
         */
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

        /**
         * ALLOWED METHODS: Which HTTP methods are allowed
         * ─────────────────────────────────────────────────
         * - GET: Fetch data
         * - POST: Create data
         * - PUT: Replace entire resource
         * - PATCH: Partial update
         * - DELETE: Remove resource
         * - OPTIONS: Preflight request (CORS)
         */
        corsConfiguration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        // Register this config for all paths ("/**")
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return source;
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * AUTHENTICATION MANAGER AND PROVIDER CONFIGURATION
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * The AuthenticationManager is the ORCHESTRATOR of authentication.
     * When a user logs in, this is what processes the authentication.
     * <p>
     * AUTHENTICATION FLOW (DETAILED):
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * 1. User submits /user/login with email + password
     * ↓
     * 2. Controller calls: authenticationManager.authenticate(
     * new UsernamePasswordAuthenticationToken(email, password)
     * )
     * ↓
     * 3. AuthenticationManager (ProviderManager) receives the token
     * - It looks for a matching AuthenticationProvider
     * - Found: DaoAuthenticationProvider
     * ↓
     * 4. DaoAuthenticationProvider.authenticate():
     * a) Calls: retrieveUser() which calls userDetailsService.loadUserByUsername(email)
     * → UserRepoImpl.loadUserByUsername(email):
     * - Queries database: SELECT * FROM users WHERE email = ?
     * - Gets User entity
     * - Calls roleRepository.getRoleByUserId(user.id)
     * → Gets Role entity with permission field
     * - Permissions example: "READ:USER,UPDATE:USER,DELETE:USER"
     * - Splits by comma → Creates List<GrantedAuthority>
     * - Creates UserPrincipal(user, authorities)
     * - Returns UserPrincipal (implements UserDetails)
     * b) Gets password hash: userPrincipal.getPassword()
     * → Returns: $2a$12$abcd...xyz (BCrypt hash)
     * c) Gets provided password: authenticationToken.getCredentials()
     * → Returns: "1234567" (plain text password from request)
     * d) Compares using passwordEncoder: passwordEncoder.matches(provided, stored)
     * → passwordEncoder.matches("1234567", "$2a$12$abcd...xyz")
     * → Returns: true or false
     * e) If MATCHES:
     * → Creates new Authentication object with:
     * * Principal: UserPrincipal
     * * Credentials: null (don't expose password)
     * * Authorities: List<GrantedAuthority> from UserPrincipal
     * * Authenticated: true
     * → Returns this new Authentication
     * f) If DOESN'T MATCH:
     * → Throws BadCredentialsException
     * → Spring Security catches it
     * → Calls authenticationEntryPoint (customAuthenticationEntryPoint)
     * → Returns 401 Unauthorized
     * ↓
     * 5. Back in Controller:
     * - Authentication succeeded
     * - Spring Security sets SecurityContextHolder.getContext().setAuthentication(auth)
     * - Now authentication is available in SecurityContextHolder
     * - Controller can access it: SecurityContextHolder.getContext().getAuthentication()
     * ↓
     * 6. Controller returns response with token (if using JWT)
     * - TokenProvider.createAccessToken(userPrincipal)
     * - Creates JWT with user info and authorities
     * - Returns token to frontend
     * <p>
     * <p>
     * KEY CONCEPT: DaoAuthenticationProvider
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * "Dao" = Data Access Object
     * DaoAuthenticationProvider loads users from a UserDetailsService (database)
     * <p>
     * Components it needs:
     * 1. UserDetailsService: Where to load users from
     * - We provide: UserRepoImpl (implements UserDetailsService)
     * 2. PasswordEncoder: How to verify passwords
     * - We provide: BCryptPasswordEncoder
     * <p>
     * It uses these to:
     * - Load user from database
     * - Verify password matches stored hash
     * - Extract authorities/roles
     * - Create authenticated Authentication object
     * <p>
     * <p>
     * BCrypt Password Verification:
     * ═══════════════════════════════════════════════════════════════════════════════════════════
     * <p>
     * Registration (once):
     * User provides: "myPassword123"
     * → passwordEncoder.encode("myPassword123")
     * → Returns: "$2a$12$..." (includes salt, hash, metadata)
     * → Store in database
     * <p>
     * Login (every time):
     * User provides: "myPassword123"
     * → Get stored hash from database: "$2a$12$..."
     * → passwordEncoder.matches("myPassword123", "$2a$12$...")
     * → BCrypt internally:
     * 1. Extracts salt from stored hash
     * 2. Hashes provided password with that salt
     * 3. Compares result with stored hash
     * → Returns: true or false
     * <p>
     * Why BCrypt is secure:
     * - Salt: Each hash includes random salt (prevents rainbow tables)
     * - Rounds: STRENGTH=12 means 2^12 iterations (slows down brute force)
     * - One-way: Can't reverse hash to get password
     *
     * @param userDetailsService the UserDetailsService to load users from database
     *                           (injected - must be a bean - which is UserRepoImpl)
     * @return AuthenticationManager bean that handles authentication
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService) {
        securityLogger.debug("═══ Configuring AuthenticationManager ═══");
        securityLogger.debug("Setting up DaoAuthenticationProvider with:");
        securityLogger.debug("  1. UserDetailsService (loads users from database)");
        securityLogger.debug("  2. PasswordEncoder (BCrypt for password verification)");

        /**
         * Create DaoAuthenticationProvider
         * ─────────────────────────────────────────────────────────────────
         * Constructor takes UserDetailsService (where to load users)
         * In Spring Security 7+, userDetailsService is required in constructor
         */
        DaoAuthenticationProvider daoAuthenticationProvider =
                new DaoAuthenticationProvider(userDetailsService);

        /**
         * Set PasswordEncoder
         * ─────────────────────────────────────────────────────────────────
         * This is used in DaoAuthenticationProvider.additionalAuthenticationChecks()
         * When verifying password during authentication
         *
         * Flow:
         * 1. User provides password: "1234567"
         * 2. Load user from DB, get stored hash: "$2a$12$..."
         * 3. Call: passwordEncoder.matches("1234567", "$2a$12$...")
         * 4. Returns: true/false
         */
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder);

        /**
         * Create and return ProviderManager with the DaoAuthenticationProvider
         * ─────────────────────────────────────────────────────────────────────
         * ProviderManager is the default AuthenticationManager implementation
         * It delegates to AuthenticationProvider instances
         *
         * When authenticationManager.authenticate(token) is called:
         * 1. ProviderManager loops through providers
         * 2. Finds provider that supports the token type (UsernamePasswordAuthenticationToken)
         * 3. Calls provider.authenticate(token)
         * 4. Returns result
         *
         * With multiple providers, would try each until one succeeds
         * We only have one provider here: DaoAuthenticationProvider
         */
        AuthenticationManager authenticationManager =
                new ProviderManager(daoAuthenticationProvider);

        securityLogger.debug("═══ AuthenticationManager configured successfully ═══");
        return authenticationManager;
    }
}