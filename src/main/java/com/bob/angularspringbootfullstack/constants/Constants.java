package com.bob.angularspringbootfullstack.constants;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Application-wide literal constants: the two pre-authentication route lists, JWT header/claim
 * names and lifetimes, and a handful of small string constants shared across otherwise-unrelated
 * classes to keep them from drifting into independently-typed duplicates.
 * <p>
 * The two route lists are this class's most important content and must be read together, never
 * separately: {@link #PUBLIC_URLS} is consumed by
 * {@code SecurityConfig#securityFilterChain}'s {@code .permitAll()} (Spring Security's own
 * authorization layer), while {@link #PUBLIC_ROUTES} is consumed by
 * {@code CustomAuthFilter#shouldNotFilter} (whether the JWT-parsing filter runs at all). A route
 * missing from one list but present in the other either 403s a caller who legitimately has no
 * token yet, or lets a stale {@code Authorization: Bearer} header break a route that should have
 * ignored it — see each list's own Javadoc for the failure mode specific to that direction.
 * {@code ConstantsPublicRouteLockstepTest} asserts the two stay mechanically in sync.
 * <p>
 * Everything else here is grouped by the class it primarily serves — session/JWT constants for
 * {@code TokenProvider} and {@code SessionServiceImpl}, the Turnstile header for
 * {@code UserController}'s registration endpoint, the org-SSO registration-id prefixes shared by
 * {@code OrganizationIdentityProviderServiceImpl}/{@code OrgAwareClientRegistrationRepository}/
 * {@code OAuth2LoginSuccessHandler}, and the demo-account email domain shared by
 * {@code DemoDataSeeder} and {@code EmailServiceImpl} — rather than being split into one class per
 * concern, since most of these values are one or two lines and used by only two or three classes
 * each.
 */
public class Constants {

    //security constants

    /**
     * URL patterns that bypass JWT authentication entirely.
     * <p>
     * Includes registration, login, MFA verification, password reset, and token refresh.
     * Any path not listed here requires a valid JWT and the appropriate authority.
     * <p>
     * Actuator endpoints are deliberately NOT part of this list: {@code /actuator/health} and
     * {@code /actuator/info} are permitted directly in {@code SecurityConfig} (ahead of this
     * list, so the more specific rule wins), while every other {@code /actuator/**} path —
     * including {@code /actuator/metrics} — requires {@code UPDATE:USER}/{@code UPDATE:ROLE},
     * same as {@code /admin/**}. See the {@code /actuator/**} matcher in
     * {@code SecurityConfig#securityFilterChain} for the full rationale.
     */
    public static final String[] PUBLIC_URLS =
            {"/user/login/**",
                    "/user/verify/code/**", "/user/register/**",
                    // Resend an outstanding 2FA/step-up code (UserController#resendVerificationCode):
                    // the caller is mid-login, same as /user/verify/code above.
                    "/user/verify/resend/**",
                    "/user/resetpassword/**", "/user/verify/password/**",
                    "/user/new/password/**",
                    "/user/verify/account/**", "/user/refresh/token/**",
                    "/user/profile/image/**", "/user/image/**",
                    // TOTP login completion (FR-MFA-4): the caller is mid-login and holds no
                    // token; the server-side challenge minted at first-factor success is the
                    // security boundary (see TotpService#verifyLoginChallenge).
                    "/user/verify/totp/**",
                    // Passkey (WebAuthn) login: the caller holds no token during a usernameless
                    // sign-in — the WebAuthn assertion signature itself is the security boundary,
                    // verified against the stored public key in PasskeyService#finishAuthentication.
                    "/user/verify/webauthn/**",
                    // Federated login (FR-FED): /oauth2/authorization/{provider} starts the
                    // Authorization Code flow, /login/oauth2/code/{provider} is the provider
                    // callback, and /oauth2/providers lets the SPA discover which providers
                    // are configured. All are pre-authentication by definition.
                    "/oauth2/**", "/login/oauth2/**",
                    // Per-organization SAML SSO (FUTURE-ENHANCEMENTS.md §3.1, Stage 3):
                    // /saml2/authenticate/{id} starts the SAML redirect binding,
                    // /login/saml2/sso/{id} is the assertion consumer service (ACS) callback, and
                    // /saml2/service-provider-metadata/{id} (also under /saml2/**) lets an IdP admin
                    // fetch this application's SP metadata to configure their side. All are
                    // pre-authentication or metadata-only, same reasoning as /oauth2/** above.
                    "/saml2/**", "/login/saml2/**",
                    // Public services catalog browsing (PublicServicesController): a prospective
                    // customer looking at what the business offers has no account yet by definition.
                    "/services/public/**",
                    // Contact Us submission (ContactController): a visitor with no account at all
                    // is exactly who this route exists for.
                    "/contact/**",
                    // OAuth2 client-credentials token endpoint (FUTURE-ENHANCEMENTS.md §3.1, P2-3
                    // Option B, RFC 6749 §4.4): the presented client_id/client_secret IS the
                    // credential, exactly as a bare Authorization header is for /user/login.
                    // Distinct path prefix from the /oauth2/** federated-login block above on
                    // purpose — "/oauth/token" issues OUR tokens to a machine caller, "/oauth2/**"
                    // is Spring's own consumer-side flow for a human signing in via Google/etc.
                    "/oauth/token/**",
            };

    /*
     * Key for storing JWT token in the request values map.
     * It is no longer necessary since we are now getting the user ID directly from the token instead of the email.
     * Retained for reference in case we need to revert back to the previous implementation.
     */
    // protected static final String TOKEN_KEY = "token";
    /**
     * Key previously used for storing the JWT subject (user email) in the request values map.
     * Retained for reference; the filter now uses {@link #getUserID(HttpServletRequest)} directly.
     */
    // protected static final String EMAIL_KEY = "email";
    public static final String HTTP_METHOD_OPTIONS = "OPTIONS";
    public static final String TOKEN_PREFIX = "Bearer ";
    /**
     * URI prefixes that do not require authentication.
     * <p>
     * Matched with {@code startsWith} (see {@link #isPublicRoute(String)}) so any
     * path variables tacked onto the end (e.g. {@code /user/verify/account/abc-123})
     * still resolve as public. Must stay in lockstep with
     * {@link com.bob.angularspringbootfullstack.configuration.SecurityConfig#PUBLIC_URLS} —
     * if a route is permitted by the SecurityFilterChain but not skipped here, a
     * stale {@code Authorization: Bearer} header from the client will cause this
     * filter to attempt token parsing and fail before the request ever reaches
     * the public controller.
     */
    public static final String[] PUBLIC_ROUTES = {
            "/user/login", "/user/verify/code", "/user/verify/resend", "/user/register",
            // Only health/info are unauthenticated (ALB + ECS health checks carry no Authorization
            // header — see aws/setup.sh / task-definition.json). Deliberately NOT bare "/actuator":
            // that would startsWith-match /actuator/metrics too, which SecurityConfig now gates
            // behind UPDATE:USER/UPDATE:ROLE — skipping JWT parsing here would mean CustomAuthFilter
            // never installs a principal for that request no matter what Bearer token is sent, making
            // the SecurityConfig authority check unreachable and turning the gate into a permanent 403
            // for everyone instead of an admin-only allow.
            "/actuator/health", "/actuator/info",
            "/user/refresh/token", "/user/profile/image", "/user/image", "/user/verify/account",
            "/user/verify/password", "/user/resetpassword", "/user/new/password",
            // TOTP login completion (FR-MFA-4): the caller holds no token mid-login, so the
            // filter must not attempt to parse a (possibly stale) Bearer header here.
            "/user/verify/totp",
            // Passkey (WebAuthn) login completion: same reasoning as /user/verify/totp above —
            // the caller is mid-login and holds no token.
            "/user/verify/webauthn",
            // Federated login (FR-FED): skipped here so a stale Bearer header from the SPA
            // can never break the browser-redirect OAuth2 dance or provider discovery.
            "/oauth2", "/login/oauth2",
            // Per-organization SAML SSO — must stay in lockstep with PUBLIC_URLS above.
            "/saml2", "/login/saml2",
            // Public services catalog browsing — must stay in lockstep with PUBLIC_URLS above.
            "/services/public",
            // Contact Us submission — must stay in lockstep with PUBLIC_URLS above.
            "/contact",
            // OAuth2 client-credentials token endpoint — must stay in lockstep with PUBLIC_URLS
            // above. The caller here is a machine presenting its client_id/client_secret, never a
            // Bearer JWT, so this filter must not attempt to parse one.
            "/oauth/token"
    };

    public static final String BOBBYLON_LLC = "BOBBYLON_LLC";
    public static final String BOBS_MANAGEMENT = "BOBS_MANAGEMENT";
    public static final String AUTHORITIES = "authorities";
    /**
     * JWT claim carrying the refresh-session FAMILY id (plan.md M5, FR-JWT-5). Present on
     * both token types: on the refresh token it pairs with the {@code jti} for rotation
     * bookkeeping, and on the access token it lets the sessions endpoint (and the SPA,
     * which can decode its own token) identify which listed session is "this one".
     */
    public static final String SESSION_FAMILY = "sid";
    public static final long ACCESS_TOKEN_EXPIRE_TIME = 1_800_000;
    public static final long REFRESH_TOKEN_EXPIRE_TIME = 432_000_000;
    public static final String TOKEN_UNVERIFIABLE = "Invalid JWT secret key";

    //Request Headers
    public static final String USER_AGENT_HEADER = "User-Agent";
    public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
    /**
     * Carries the Cloudflare Turnstile widget token on {@code POST /user/register}
     * (FUTURE-ENHANCEMENTS.md §3.1). A header rather than a {@code User} field — the registration
     * payload binds directly to the JDBC-mapped {@link com.bob.angularspringbootfullstack.model.User}
     * model, which has no business carrying a transient, non-column CAPTCHA token.
     */
    public static final String TURNSTILE_TOKEN_HEADER = "X-Turnstile-Token";
    /**
     * Standard MySQL-compatible timestamp format used when persisting expiration timestamps.
     */
    public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * Prefix of every per-organization OIDC registration id (FUTURE-ENHANCEMENTS.md §3.1
     * "Per-organization external IdP", Stage 2), e.g. {@code "org-oidc-42"} for organization 42.
     * A single shared constant rather than three independently-typed literals: it is built once
     * ({@code OrganizationIdentityProviderServiceImpl#resolveByEmailDomain}, constructing the login
     * URL) and parsed twice ({@code OrgAwareClientRegistrationRepository} resolving a live login,
     * {@code OAuth2LoginSuccessHandler} recovering the organization id after callback) — a typo'd
     * duplicate of this string in only one of those three spots would fail closed silently rather
     * than loudly, since every path here already treats "unrecognized id" as "no SSO configured".
     */
    public static final String ORG_OIDC_REGISTRATION_PREFIX = "org-oidc-";

    /**
     * Prefix of every per-organization SAML registration id (FUTURE-ENHANCEMENTS.md §3.1
     * "Per-organization external IdP", Stage 3), e.g. {@code "org-saml-42"} for organization 42.
     * Sibling to {@link #ORG_OIDC_REGISTRATION_PREFIX} — same rationale, one per protocol, since an
     * organization's registration id must self-describe which {@code RelyingPartyRegistrationRepository}
     * (SAML) vs. {@code ClientRegistrationRepository} (OIDC) resolves it.
     */
    public static final String ORG_SAML_REGISTRATION_PREFIX = "org-saml-";

    /**
     * Domain shared by every {@code DemoDataSeeder} account (FUTURE-ENHANCEMENTS.md "Report Digest
     * Bounces to Demo Accounts"). These mailboxes never exist, so real delivery always hard-bounces
     * back into {@code EmailServiceImpl}'s own {@code FROM_ADDRESS} inbox — the seeder builds every
     * demo email with this constant and {@code EmailServiceImpl} suppresses dispatch to it, so the
     * seeded addresses and the suppression check cannot silently drift apart.
     */
    public static final String DEMO_EMAIL_DOMAIN = "@tessera.dev";

    /**
     * {@code users.origin} value stamped on a machine account created by
     * {@code ServiceAccountServiceImpl#create} (FUTURE-ENHANCEMENTS.md §3.1 "P2-3 — Machine-to-machine
     * API access"). An ordinary {@code users} row with this origin authenticates via
     * {@link com.bob.angularspringbootfullstack.filter.ApiKeyAuthFilter} instead of a password, so it
     * reuses the entire existing {@code users → userroles → roles.permission} authority pipeline with
     * zero changes to {@code TokenProvider}/{@code CustomAuthFilter}/{@code UserPrincipal}. Referenced
     * by {@code schema.sql}'s comment on the {@code apikeys} table since that table was added
     * (commit {@code 8165195}); this is where the literal it names actually lives.
     */
    public static final String SERVICE_ACCOUNT_ORIGIN = "SERVICE_ACCOUNT";

    /**
     * Domain used to build a service account's synthetic, never-delivered email address (the
     * {@code users.email} column is {@code NOT NULL UNIQUE}, so a machine account still needs one).
     * Sibling to {@link #DEMO_EMAIL_DOMAIN} — same reasoning, different purpose: nobody reads this
     * mailbox, {@code ServiceAccountServiceImpl#create} just needs a collision-free unique value.
     */
    public static final String SERVICE_ACCOUNT_EMAIL_DOMAIN = "@service.tessera.internal";

    /**
     * Header carrying a raw API key (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A), read by
     * {@link com.bob.angularspringbootfullstack.filter.ApiKeyAuthFilter} ahead of
     * {@code CustomAuthFilter}. A caller sends exactly one of this header or
     * {@code Authorization: Bearer ...} — never both — so the two authentication front doors never
     * contend over the same request; see {@code ApiKeyAuthFilter}'s Javadoc for why.
     */
    public static final String API_KEY_HEADER = "X-API-Key";
}
