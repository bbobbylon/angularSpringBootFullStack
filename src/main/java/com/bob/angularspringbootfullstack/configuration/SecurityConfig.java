package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.filter.CustomAuthFilter;
import com.bob.angularspringbootfullstack.handler.CustomAccessDeniedHandler;
import com.bob.angularspringbootfullstack.handler.CustomAuthenticationEntryPoint;
import com.bob.angularspringbootfullstack.handler.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static com.bob.angularspringbootfullstack.constants.Constants.PUBLIC_URLS;
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
     * The token-exchange point for federated sign-in (SRS FR-FED-4): converts a
     * completed OAuth2 Authorization Code flow into the application's own JWTs and
     * redirects the browser back to the Angular SPA.
     */
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    /**
     * SPA origin used when a federated login attempt fails mid-flow: the browser is
     * mid-redirect (not an XHR), so the only sane recovery is sending it back to the
     * SPA login screen with a coarse error code.
     */
    @Value("${ui.app.url:http://localhost:4200}")
    private String uiAppUrl;

    /**
     * Comma-separated origin patterns the browser may call this API from.
     *
     * <p>Read from configuration rather than hardcoded because the correct answer differs in every
     * environment, and baking a list into the jar means a rebuild to change it — and, worse, that
     * the production jar ships whatever placeholder was convenient during development.
     *
     * <p>This bean is the <b>single</b> definition of the application's CORS policy. It used to
     * have a rival: a servlet-level {@code CorsFilter} in the application class built its own
     * {@link CorsConfiguration} from this same property, while this class hardcoded a different
     * list — including a {@code angularsecureapp.org} origin that no longer exists anywhere. Since
     * the security filter chain is what answers preflights, the hardcoded list silently won and the
     * configurable one was decorative. That divergence was invisible because the deployed shape
     * serves the SPA and the API from one origin, so almost nothing is genuinely cross-origin; it
     * would have surfaced the moment a second client appeared. The filter now consumes this bean.
     */
    @Value("${app.cors.allowed-origin-patterns}")
    private String allowedOriginPatterns;

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
                            // X-Frame-Options: DENY — blocks clickjacking (the Angular SPA never
                            // needs to be embedded in a frame).
                            .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                            // X-Content-Type-Options: nosniff — prevents MIME-type sniffing.
                            .contentTypeOptions(Customizer.withDefaults())
                            // HSTS: tells browsers to always use HTTPS for 1 year, including
                            // subdomains. Only effective under TLS (ignored over plain HTTP).
                            .httpStrictTransportSecurity(hsts -> hsts
                                    .includeSubDomains(true)
                                    .maxAgeInSeconds(31536000))
                            // Content-Security-Policy: restricts which origins can load scripts,
                            // styles, images, and API connections. 'unsafe-inline' for styles is
                            // required because Angular injects component styles at runtime.
                            // img-src includes https: to allow S3-hosted profile images when
                            // IMAGE_STORAGE_TYPE=s3. Adjust connect-src when adding third-party
                            // analytics or error-reporting endpoints.
                            //
                            // The sha256 hash in script-src allow-lists ONE specific inline
                            // script: index.html's tiny theme-flash-prevention snippet (reads
                            // localStorage before first paint so there's no dark/light flash).
                            // It's the exact hash Chrome's own CSP violation reports for that
                            // script's content — NOT a blanket 'unsafe-inline' — so any other
                            // inline script (or a change to this one's contents, which changes
                            // its hash) stays blocked. If that script is ever edited, recompute
                            // the hash (the browser console error reports the new one directly)
                            // and update it here.
                            .contentSecurityPolicy(csp -> csp.policyDirectives(
                                    "default-src 'self'; " +
                                    "script-src 'self' 'sha256-+tarR50wdDg867HQDss7r1ZcpsJqINIeko9y0srSPCw='; " +
                                    "style-src 'self' 'unsafe-inline'; " +
                                    "img-src 'self' data: blob: https:; " +
                                    "font-src 'self'; " +
                                    "connect-src 'self'; " +
                                    "frame-ancestors 'none'; " +
                                    "base-uri 'self'; " +
                                    "form-action 'self'"
                            ))
                            // Referrer-Policy: sends the full URL on same-origin navigations but
                            // only the origin (no path/query) on cross-origin requests, preventing
                            // sensitive path parameters from leaking to third-party pages.
                            .referrerPolicy(rp -> rp.policy(
                                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN
                            ))
                            // Permissions-Policy: disables browser features this SPA has no
                            // legitimate need for. Reduces the attack surface if a dependency or
                            // injected script attempts to access these APIs.
                            .permissionsPolicy(pp -> pp.policy(
                                    "camera=(), microphone=(), geolocation=(), payment=()"
                            ))
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
                            .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
                            .requestMatchers(DELETE, "/customer/delete/**").hasAnyAuthority("DELETE:CUSTOMER")
                            // Administrative endpoints (FR-ADMIN / FR-RBAC-4). Matchers are evaluated
                            // top-down, so these MUST precede the broad GET/POST catch-alls below:
                            // role reassignment demands UPDATE:ROLE, account-state changes demand
                            // UPDATE:USER, and everything else under /admin/** requires at least one
                            // staff-grade authority. AdminUserController repeats these checks with
                            // @PreAuthorize so URL- and method-level enforcement stay in lockstep
                            // (FR-RBAC-2).
                            .requestMatchers(PATCH, "/admin/user/*/role/**").hasAnyAuthority("UPDATE:ROLE")
                            .requestMatchers(PATCH, "/admin/user/*/settings").hasAnyAuthority("UPDATE:USER")
                            .requestMatchers(PATCH, "/admin/user/*/update").hasAnyAuthority("UPDATE:USER")
                            .requestMatchers("/admin/**").hasAnyAuthority("UPDATE:USER", "UPDATE:ROLE")
                            // Self-service account security (FR-MFA-4 / plan.md M4-M5): managing
                            // one's OWN second factor and sessions must not require staff
                            // authorities, so these are matched BEFORE the POST/GET catch-alls
                            // below (which demand UPDATE:USER / READ:USER). Authentication alone
                            // suffices; every handler scopes its work to the token's principal.
                            .requestMatchers("/user/totp/**").authenticated()
                            .requestMatchers("/user/webauthn/**").authenticated()
                            .requestMatchers("/user/sessions/**").authenticated()
                            // The Angular SPA is compiled into this jar's static resources (see
                            // Dockerfile) and served from this SAME origin in Docker/prod — unlike
                            // local dev, where Angular runs on its own dev server (port 4200) and
                            // never touches this filter chain at all. Without this permit, the broad
                            // GET/** rule below demands READ:USER on the SPA's own index.html/JS/CSS,
                            // making it impossible for an unauthenticated browser to ever load the
                            // login page — a chicken-and-egg 401 that only surfaces once the app is
                            // actually deployed with Angular baked in, not in local dev.
                            .requestMatchers(GET, "/", "/index.html", "/favicon.ico", "/manifest.webmanifest",
                                    "/*.js", "/*.css", "/*.ico", "/*.png", "/*.svg", "/*.jpg", "/*.jpeg",
                                    "/*.webp", "/*.woff", "/*.woff2", "/*.ttf", "/*.map").permitAll()
                            .requestMatchers(GET, "/assets/**").permitAll()
                            // Angular's builder places CSS-referenced binary assets (the
                            // self-hosted IBM Plex/bootstrap-icons fonts, in this app's case) under
                            // /media/ in the build output — a separate location from /assets/ that
                            // the permit above doesn't cover, discovered when self-hosted fonts
                            // 401'd in production despite the /assets/** and /*.woff2 permits.
                            .requestMatchers(GET, "/media/**").permitAll()
                            // Direct navigation (or a hard refresh) to an Angular client-side
                            // route — e.g. typing /login in the address bar — hits Spring
                            // Security BEFORE the WebMvcConfig SPA-fallback ever gets a chance to
                            // forward it to index.html, since security filters run ahead of MVC
                            // dispatch. Every path here is a real entry in app.routes.ts.
                            //
                            // The invariant that makes this safe is a NAMESPACE SPLIT: the API is
                            // entirely singular/namespaced (/user/**, /customer/**, /admin/**),
                            // while SPA routes are plural or bare (/customers, /services, /billing).
                            // Permitting a path here therefore cannot expose a controller, because
                            // no controller answers on these paths — Spring MVC finds no handler and
                            // WebMvcConfig forwards to index.html.
                            //
                            // That invariant had exactly one breach, and it was load-bearing:
                            // the email-verification landing page was routed at
                            // /user/verify/{type}/:key in Angular, byte-for-byte the same URL as
                            // UserController#verifyAccount. In split-origin dev the SPA answered it;
                            // once Angular is baked into this jar there is one origin, the real
                            // @GetMapping wins, and the recipient of an activation email is shown
                            // the raw JSON envelope. It now lives at /verify/{type}/:key — hence
                            // "/verify/**" below — and the split holds everywhere. Do not add an SPA
                            // route under /user, /customer, or /admin.
                            //
                            // This permits loading the SPA shell only — the actual protected DATA
                            // for these pages still comes from those separate, still-authenticated
                            // API endpoints, so this grants no new access to anything real.
                            .requestMatchers(GET, "/login", "/verify", "/verify/**", "/resetpassword",
                                    "/register", "/customers", "/customers/**", "/customer/new",
                                    "/invoice/new", "/invoices", "/invoice/**", "/profile", "/security",
                                    "/users", "/users/**", "/roles", "/billing", "/services",
                                    "/services/manage", "/analytics", "/security-overview").permitAll()
                            .requestMatchers(GET, "/**").hasAnyAuthority("READ:USER", "READ:CUSTOMER")
                            .requestMatchers(POST, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER")
                            .requestMatchers(PUT, "/**").hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER", "UPDATE:ROLE")
                            .anyRequest().authenticated()
                    )
                    // Federated login (SRS §4.3, CON-5). Spring Security's OAuth2 client owns the
                    // protocol: /oauth2/authorization/{provider} initiates the Authorization Code
                    // flow and /login/oauth2/code/{provider} receives the provider callback (both
                    // are in PUBLIC_URLS). On success the custom handler issues OUR JWTs — the
                    // token-exchange point — so downstream requests are stateless Bearer calls
                    // exactly like in-house sessions. NOTE on CON-3 (stateless): the OAuth2
                    // handshake itself briefly uses the container session to hold the CSRF `state`
                    // parameter between the outbound redirect and the callback; no SecurityContext
                    // is ever stored, and the session plays no part after token issuance.
                    .oauth2Login(oauth -> oauth
                            .successHandler(oAuth2LoginSuccessHandler)
                            .failureHandler((request, response, exception) -> {
                                log.warn("Federated login failed: {}", exception.getMessage());
                                response.sendRedirect(uiAppUrl + "/login?error=federated");
                            })
                    )
                    .addFilterBefore(customAuthFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(ex -> ex
                            .accessDeniedHandler(customAccessDeniedHandler)
                            // Force the custom 401 (JSON) entry point for EVERY unauthenticated request.
                            // Rationale: enabling oauth2Login() installs a LoginUrlAuthenticationEntryPoint
                            // that 302-redirects "browser-negotiated" requests to /login. For this stateless
                            // JSON API served to an SPA on another origin, that redirect surfaces in the
                            // browser as an opaque CORS failure (the /login response carries no CORS headers)
                            // and, critically, defeats the frontend's token.interceptor auto-refresh, which
                            // only retries on HTTP 401 — a 302 is never seen as one. Registering the custom
                            // entry point for AnyRequestMatcher overrides oauth2Login's html redirect so an
                            // expired/missing token always yields a clean 401 the SPA can silently refresh.
                            // The federated-login *initiation* (/oauth2/authorization/**) is a public route
                            // handled by Spring's redirect filter directly, so it never needs this entry point.
                            .defaultAuthenticationEntryPointFor(customAuthenticationEntryPoint, AnyRequestMatcher.INSTANCE)
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
        // setAllowedOriginPATTERNS, not setAllowedOrigins: with allowCredentials(true) the CORS
        // spec forbids the "*" wildcard, and Spring enforces that by refusing to start if
        // setAllowedOrigins contains one. Patterns are the supported way to express "any host on my
        // LAN" — Spring matches the request's Origin against them and echoes back that exact
        // origin, which is spec-compliant. Exact origins (the prod case) are written literally and
        // still match exactly.
        corsConfiguration.setAllowedOriginPatterns(
                Arrays.stream(allowedOriginPatterns.split(","))
                        .map(String::trim)
                        .filter(pattern -> !pattern.isEmpty())
                        .toList());
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
