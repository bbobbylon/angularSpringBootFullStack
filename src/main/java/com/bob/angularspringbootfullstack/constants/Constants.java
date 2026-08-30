package com.bob.angularspringbootfullstack.constants;

import jakarta.servlet.http.HttpServletRequest;

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
            "/contact"
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
}
