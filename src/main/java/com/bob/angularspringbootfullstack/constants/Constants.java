package com.bob.angularspringbootfullstack.constants;

import jakarta.servlet.http.HttpServletRequest;

public class Constants {

    //security constants

    /**
     * URL patterns that bypass JWT authentication entirely.
     * <p>
     * Includes registration, login, MFA verification, password reset, token refresh,
     * profile images, and Actuator endpoints. Any path not listed here requires a
     * valid JWT and the appropriate authority.
     */
    public static final String[] PUBLIC_URLS =
            {"/user/login/**",
                    "/user/verify/code/**", "/user/register/**", "/actuator/**",
                    "/user/resetpassword/**", "/user/verify/password/**",
                    "/user/new/password/**",
                    "/user/verify/account/**", "/user/refresh/token/**",
                    "/user/profile/image/**", "/user/image/**",
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
            "/user/login", "/user/verify/code", "/user/register", "/actuator",
            "/user/refresh/token", "/user/image", "/user/verify/account",
            "/user/verify/password", "/user/resetpassword", "/user/new/password"
    };

    public static final String BOBBYLON_LLC = "BOBBYLON_LLC";
    public static final String BOBS_MANAGEMENT = "BOBS_MANAGEMENT";
    public static final String AUTHORITIES = "authorities";
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
}
