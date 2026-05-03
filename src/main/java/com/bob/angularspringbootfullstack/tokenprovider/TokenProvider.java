package com.bob.angularspringbootfullstack.tokenprovider;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;
import static java.lang.System.currentTimeMillis;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

/**
 * In this class we are generating the tokens for the user. The following methods are used to generate the tokens:
 * - createAccessToken: generates an access token for the user
 * - createRefreshToken: generates a refresh token for the user
 * - getClaimsFromUser: gets the claims from the userPrincipal, which is the user that is logged in, then we are mapping the authorities to a string array, and finally we are returning the array
 * we use UserPrincipal because it has the user and the permissions that we need to generate the token. We are using the JWT library to generate the tokens, and we are using the HMAC512 algorithm to sign the tokens with a secret key. The secret key is stored in the application.properties file and is injected into this class using the @Value annotation. The access token expires in 30 minutes, and the refresh token expires in 5 days.
 * <p>
 * This token provider will be able to be injected and used to create the access and refresh tokens for the user.
 **/
@Component
//@RequiredArgsConstructor is for our dependency injection, it will generate a constructor with the required arguments, which in this case is the UserService. This allows us to inject the UserService into this class without having to write a constructor ourselves.
@RequiredArgsConstructor
public class TokenProvider {
    private static final String BOBBYLON_LLC = "BOBBYLON_LLC";
    private static final String BOBS_MANAGEMENT = "BOBS_MANAGEMENT";
    private static final String AUTHORITIES = "authorities";
    private static final long ACCESS_TOKEN_EXPIRE_TIME = 1_800_000;
    private static final long REFRESH_TOKEN_EXPIRE_TIME = 432_000_000;
    private static final String TOKEN_UNVERIFIABLE = "Invalid JWT secret key";
    private final UserService userService;
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Generates a JWT access token for the given UserPrincipal.
     * The token includes issuer, audience, issued at, subject (username),
     * authorities (permissions/roles), and an expiration time (30 minutes).
     * The token is signed using HMAC512 with the secret key.
     *
     * @param userPrincipal an authenticated user
     * @return a signed JWT access token as a String
     */
    public String createAccessToken(UserPrincipal userPrincipal) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withSubject(userPrincipal.getUsername())
                .withArrayClaim(AUTHORITIES, getClaimsFromUser(userPrincipal))
                .withExpiresAt(new Date(currentTimeMillis() + ACCESS_TOKEN_EXPIRE_TIME))
                .sign(HMAC512(secret.getBytes()));
    }

    // this method is for getting the AUTHORITIES from the userPrincipal, which is the user that is logged in, then we are mapping the authorities to a string array, and finally we are returning the array. This will allow us to get the permissions of the user.

    /**
     * Extracts the authorities (roles/permissions) from the UserPrincipal
     * and returns them as a String array. This is used to embed the user's
     * permissions into the JWT token as a claim.
     *
     * @param userPrincipal an authenticated user
     * @return an array of authority names (e.g., ROLE_USER, ROLE_ADMIN)
     */
    private String[] getClaimsFromUser(UserPrincipal userPrincipal) {
        return userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toArray(String[]::new);
    }

    /**
     * Generates a JWT refresh token for the given UserPrincipal.
     * The refresh token includes issuer, audience, issued at, subject (username),
     * and an expiration time (5 days). It does NOT include authorities.
     * The token is signed using HMAC512 with the secret key.
     *
     * @param userPrincipal an authenticated user
     * @return a signed JWT refresh token as a String
     */
    public String createRefreshToken(UserPrincipal userPrincipal) {
        return JWT.create()
                .withIssuer(BOBBYLON_LLC)
                .withAudience(BOBS_MANAGEMENT)
                .withIssuedAt(new Date())
                .withSubject(userPrincipal.getUsername())
                .withExpiresAt(new Date(currentTimeMillis() + REFRESH_TOKEN_EXPIRE_TIME))
                .sign(HMAC512(secret.getBytes()));
    }


    /**
     * Extracts the authorities (roles/permissions) from a JWT token.
     * The authorities are stored as a claim in the token and are converted
     * back into a list of GrantedAuthority objects for use by Spring Security.
     *
     * @param token the JWT token
     * @return a list of GrantedAuthority objects representing the user's permissions
     */
    public List<GrantedAuthority> getAuthorities(String token) {
        String[] claims = getClaimsFromToken(token);
        return stream(claims).map(SimpleGrantedAuthority::new).collect(toList());
    }

    /**
     * Retrieves the "authorities" claim from a JWT token safely, handling tokens that don't have it.
     * <p>
     * <b>Purpose:</b><br/>
     * Extracts the authorities (permissions/roles) from a JWT token's claims. This is called when
     * CustomAuthFilter processes a request, so it needs to be resilient to different token types.
     * <p>
     * <b>Why Safe Handling is Critical:</b>
     * <ul>
     *   <li><b>Access Tokens:</b> Always include "authorities" claim with user's permissions</li>
     *   <li><b>Refresh Tokens:</b> Intentionally DO NOT include authorities (they don't need permissions)</li>
     *   <li><b>Old Tokens:</b> Legacy tokens from different systems may not have the claim</li>
     * </ul>
     * <p>
     * This method must not throw an exception when the "authorities" claim is missing, because
     * refresh tokens are valid and need processing even though they lack that claim.
     * <p>
     * <b>Implementation Details:</b>
     * <ol>
     *   <li>Verify token signature, issuer, and expiration (using getJWTVerifier())</li>
     *   <li>Extract the decoded JWT payload</li>
     *   <li>Get the "authorities" claim from the payload</li>
     *   <li>If claim is missing (null) or marked as null: return empty String array</li>
     *   <li>Otherwise: convert Claim to String[] and return it (or empty array if conversion fails)</li>
     * </ol>
     * <p>
     * <b>Return Value Semantics:</b>
     * <ul>
     *   <li><b>Non-empty array:</b> Token has authorities; calling code (filter) will create Authentication</li>
     *   <li><b>Empty array:</b> Token has no authorities (refresh token or other); calling code must handle this</li>
     * </ul>
     * <p>
     * <b>Usage in CustomAuthFilter:</b>
     * <pre>
     * List&lt;GrantedAuthority&gt; authorities = tokenProvider.getAuthorities(token);
     * // getAuthorities() calls this method, then converts String[] to List&lt;GrantedAuthority&gt;
     *
     * // Filter checks: if authorities is empty, this is likely a refresh token
     * if (authorities == null || authorities.isEmpty()) {
     *     SecurityContextHolder.clearContext();  // Do NOT authenticate
     * } else {
     *     Authentication auth = tokenProvider.getAuthentication(email, authorities, request);
     *     SecurityContextHolder.getContext().setAuthentication(auth);  // Authenticate
     * }
     * </pre>
     * <p>
     * <b>Example Outputs:</b>
     * <ul>
     *   <li><b>Access Token with authorities:</b><br/>
     *       Input: JWT with "authorities": ["READ:USER", "UPDATE:USER", "DELETE:USER"]<br/>
     *       Output: ["READ:USER", "UPDATE:USER", "DELETE:USER"]
     *   </li>
     *   <li><b>Refresh Token (no authorities):</b><br/>
     *       Input: JWT with no "authorities" claim (or claim is null)<br/>
     *       Output: [] (empty array)
     *   </li>
     * </ul>
     * <p>
     * <b>Why This Method Changed:</b>
     * <ul>
     *   <li><b>Before (old code):</b> Called .asArray(String.class) directly → throws exception if claim missing</li>
     *   <li><b>After (new code):</b> Check if claim is null/missing first → return empty array gracefully</li>
     *   <li><b>Trigger:</b> Refresh token implementation; refresh tokens must be verifiable without authorities</li>
     * </ul>
     *
     * @param token the JWT token string to extract authorities from (already verified by caller)
     * @return an array of authority/permission strings (e.g., ["READ:USER", "UPDATE:USER"])
     *         Returns empty String[0] if the "authorities" claim is missing or null
     * @throws JWTVerificationException if token signature is invalid or other verification fails
     * @see #getJWTVerifier() for verification logic
     * @see TokenProvider#getAuthorities(String) for conversion of String[] to List&lt;GrantedAuthority&gt;
     */
    private String[] getClaimsFromToken(String token) {
        JWTVerifier verifier = getJWTVerifier();
        DecodedJWT decoded = verifier.verify(token);
        Claim claim = decoded.getClaim(AUTHORITIES);
        if (claim == null || claim.isNull()) {
            return new String[0];
        }
        String[] arr = claim.asArray(String.class);
        return arr == null ? new String[0] : arr;
    }

    /**
     * Creates and returns a JWTVerifier instance using the secret key and HMAC512 algorithm.
     * This verifier is used to validate the signature and claims of JWT tokens.
     *
     * @return a configured JWTVerifier instance
     * @throws RuntimeException if the secret is invalid or the verifier cannot be created
     */
    private JWTVerifier getJWTVerifier() {
        JWTVerifier verifier;
        try {
            Algorithm alg = HMAC512(secret);
            // Do not require the 'authorities' claim at verification time because refresh tokens
            // do not include authorities. Claim presence is enforced only when authorities are needed.
            verifier = JWT.require(alg).withIssuer(BOBBYLON_LLC).build();
        } catch (JWTVerificationException e) {
            throw new JWTVerificationException(TOKEN_UNVERIFIABLE);
        }
        return verifier;
    }

    // this is for building the filter of our Spring Security request flow

    /**
     * Creates an authentication token for Spring Security based on the provided email, authorities, and HTTP request.
     *
     * @param email       the user's email
     * @param authorities the user's granted authorities
     * @param request     the HTTP request
     * @return an Authentication object for Spring Security
     */
    public Authentication getAuthentication(String email, List<GrantedAuthority> authorities, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userService.getUserByEmail(email), null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    /**
     * Validates whether a JWT token is valid for the given email address.
     * <p>
     * A token is considered valid if:
     * 1. The email is not empty (StringUtils.isNotEmpty)
     * 2. The token has not expired (checked via isTokenExpired())
     * <p>
     * This method is typically called before attempting to extract user information from a token.
     * <p>
     * Flow:
     * 1. Check if email is provided (not null, not empty)
     * 2. Create JWTVerifier with secret key
     * 3. Verify token signature using verifier
     * 4. Extract expiration date from verified token
     * 5. Compare expiration date with current time
     * 6. Return true if email valid AND token not expired
     *
     * @param email the user's email to validate against token subject
     * @param token the JWT token string to validate
     * @return true if token is valid and not expired, false otherwise
     * @throws JWTVerificationException if token signature is invalid or secret key is wrong
     */
    public boolean isTokenValid(String email, String token) {
        JWTVerifier verifier = getJWTVerifier();
        return StringUtils.isNotEmpty(email) && !isTokenExpired(verifier, token);
    }

    /**
     * Checks if a JWT token has expired.
     * <p>
     * Extracts the expiration date from the token payload and compares it
     * with the current system time. A token is considered expired if its
     * expiration time is in the past.
     * <p>
     * How JWT expiration works:
     * 1. Token contains "exp" claim: Unix timestamp of expiration
     * 2. Example: exp = 1713247333 (April 16, 2024 at 10:22:13 UTC)
     * 3. Current time: 1713247400 (April 16, 2024 at 10:23:20 UTC)
     * 4. Current time > expiration → Token is expired
     * <p>
     * Internal process:
     * 1. verifier.verify(token) validates signature and returns DecodedJWT
     * 2. getExpiresAt() extracts the "exp" claim as a Date object
     * 3. expiration.before(new Date()) checks if expiration is before now
     * 4. Returns true if token is in the past (expired)
     *
     * @param verifier the JWTVerifier instance with secret key and issuer configured
     * @param token    the JWT token string to check for expiration
     * @return true if token has expired (expiration time is before current time), false if still valid
     * @throws JWTVerificationException if token signature is invalid
     */
    private boolean isTokenExpired(JWTVerifier verifier, String token) {
        Date expiration = verifier.verify(token).getExpiresAt();
        return expiration.before(new Date());
    }

    /**
     * Extracts the subject (username/email) from a JWT token with comprehensive error handling.
     * <p>
     * <b>Purpose:</b><br/>
     * The subject claim contains the user's email/username and is used to identify which user the token
     * belongs to. This is called after authentication to get the user's identifier for database lookups,
     * logging, refresh token processing, etc.
     * <p>
     * <b>Key Responsibilities:</b>
     * <ul>
     *   <li>Verify JWT token signature and claims (issuer, audience, expiration)</li>
     *   <li>Extract and return the "sub" (subject/email) claim from valid tokens</li>
     *   <li>Handle various failure modes (decode errors, expired, invalid signature, etc.)</li>
     *   <li>Store error details in request attributes for downstream exception handlers</li>
     *   <li>Throw appropriate exceptions for each error type (BadCredentialsException, ApiException, etc.)</li>
     * </ul>
     * <p>
     * <b>Why This Method is Critical:</b>
     * <ul>
     *   <li><b>Access Token Flow:</b> CustomAuthFilter calls this during authentication to extract the user's email</li>
     *   <li><b>Refresh Token Flow:</b> UserController.sendNewRefreshToken() calls this to extract email for token refresh</li>
     *   <li><b>Error Reporting:</b> Catches low-level JWT library exceptions and translates them to application exceptions</li>
     *   <li><b>Security:</b> Validates token signature prevents replay and tampering attacks</li>
     * </ul>
     * <p>
     * <b>Exception Handling Strategy (Detailed):</b>
     * <p>
     * This method catches 5 categories of exceptions and handles each appropriately:
     * <p>
     * <b>1. TokenExpiredException (401 Unauthorized):</b>
     * <pre>
     * - Thrown: Token.exp timestamp is before current time
     * - Example: User's access token from 2 hours ago (exp: 30 min)
     * - Action: Set request attribute "expiredMessage" and re-throw as TokenExpiredException
     * - Client sees: 401 {"reason": "Token has expired"}
     * - Frontend action: Call /user/refresh/token with refresh token to get new access token
     * </pre>
     * <p>
     * <b>2. InvalidClaimException (401 Unauthorized):</b>
     * <pre>
     * - Thrown: A claim doesn't match expected value
     * - Examples: Issuer != "BOBBYLON_LLC", Audience != "BOBS_MANAGEMENT"
     * - Action: Set request attribute "invalidClaim" and re-throw as InvalidClaimException
     * - Client sees: 401 {"reason": "Invalid claim"}
     * - Frontend action: User likely tampered with token; force redirect to login
     * </pre>
     * <p>
     * <b>3. JWTDecodeException / IllegalArgumentException (400 Bad Request):</b>
     * <pre>
     * - Thrown: Token cannot be decoded as Base64 (malformed JWT)
     * - Examples: Token missing dots, invalid Base64 characters, corrupted data
     * - Action: Map to BadCredentialsException with clear message
     * - Client sees: 400 {"reason": "Could not decode the token. The input is not a valid Base64-encoded JWT."}
     * - Frontend action: User provided invalid token; prompt for login again
     * - NOTE: This is NEW behavior (as of refresh token implementation). Previously this error bubbled up
     *         as a garbled JSON message from the JWT library. Now it's caught and translated.
     * </pre>
     * <p>
     * <b>4. Other JWTVerificationException (400 Bad Request):</b>
     * <pre>
     * - Thrown: Any other verification failure (invalid signature, etc.)
     * - Action: Map to ApiException with "Invalid token" message
     * - Client sees: 400 {"reason": "Invalid token. [library message]"}
     * - Frontend action: Treat as invalid token; prompt for login
     * </pre>
     * <p>
     * <b>5. Any other exception (not mapped above):</b>
     * <pre>
     * - Action: NOT caught here; bubbles to global exception handler
     * - Client sees: 500 Internal Server Error (serialized by ExceptionUtils)
     * </pre>
     * <p>
     * <b>Usage Flows:</b>
     * <p>
     * <b>Scenario A: Access Token (Normal Request)</b>
     * <pre>
     * 1. Client sends: GET /user/profile -H "Authorization: Bearer eyJhbGci..." (access token, valid 30 min)
     * 2. CustomAuthFilter.doFilterInternal() calls: tokenProvider.getSubject(token, request)
     * 3. Token is valid → returns "bob@example.com"
     * 4. Filter looks up user → sets Authentication in SecurityContext
     * 5. Controller receives authenticated request, returns user profile
     * </pre>
     * <p>
     * <b>Scenario B: Refresh Token (Token Refresh)</b>
     * <pre>
     * 1. Client sends: GET /user/refresh/token -H "Authorization: Bearer eyJhbGci..." (refresh token, valid 5 days)
     * 2. UserController.sendNewRefreshToken() calls: tokenProvider.getSubject(refreshToken, request)
     * 3. Refresh token verification:
     *    - No "authorities" claim present (expected; refresh tokens don't have it)
     *    - Signature valid, issuer matches, not expired → returns "bob@example.com"
     * 4. Controller gets user, creates new access token, returns it
     * 5. Client stores new access token, uses it for subsequent requests
     * </pre>
     * <p>
     * <b>Scenario C: Malformed/Corrupted Token</b>
     * <pre>
     * 1. Client sends: GET /user/profile -H "Authorization: Bearer corrupted.data.here"
     * 2. CustomAuthFilter.doFilterInternal() calls: tokenProvider.getSubject(token, request)
     * 3. JWTDecodeException thrown (cannot parse as valid Base64 JWT)
     * 4. Caught and mapped to BadCredentialsException("Could not decode the token...")
     * 5. Re-thrown → caught by ExceptionUtils → returns 400 Bad Request with clean JSON
     * 6. Client sees: {"reason": "Could not decode the token. The input is not a valid Base64-encoded JWT."}
     * </pre>
     * <p>
     * <b>Request Attributes Set (for downstream handlers):</b>
     * <ul>
     *   <li><b>expiredMessage:</b> Set when token is expired (TokenExpiredException case)</li>
     *   <li><b>invalidClaim:</b> Set when claim validation fails (InvalidClaimException case)</li>
     *   <li><b>invalidToken:</b> Set when token cannot be decoded or verified (new cases)</li>
     * </ul>
     * These attributes are used by exception handlers to provide context in error responses.
     * <p>
     * <b>Security Notes:</b>
     * <ul>
     *   <li>This method does NOT log the token itself (tokens are sensitive/secrets)</li>
     *   <li>Low-level exception messages from JWT library are stored in request attributes for server-side logs only</li>
     *   <li>Client-facing error messages are generic and do not expose internal implementation details</li>
     * </ul>
     *
     * @param token   the JWT token string to extract subject from (typically from "Authorization: Bearer <token>" header)
     * @param request the HTTP servlet request for storing error details and context
     * @return the subject (username/email) from the verified token (e.g., "bob@example.com")
     * @throws TokenExpiredException    if token.exp < current time (catch in filter and return 401)
     * @throws InvalidClaimException    if issuer/audience don't match (catch in filter and return 401)
     * @throws BadCredentialsException  if token cannot be decoded as Base64 (catch in ExceptionUtils and return 400)
     * @throws ApiException             if any other verification failure (catch in ExceptionUtils and return 400)
     * @throws JWTVerificationException (parent class) any verification issue not explicitly handled
     */
    public String getSubject(String token, HttpServletRequest request) throws JWTVerificationException {
        try {
            return getJWTVerifier().verify(token).getSubject();
        } catch (TokenExpiredException e) {
            request.setAttribute("expiredMessage", e.getMessage());
            throw e;
        } catch (InvalidClaimException e) {
            request.setAttribute("invalidClaim", e.getMessage());
            throw e;
        } catch (com.auth0.jwt.exceptions.JWTDecodeException | IllegalArgumentException decodeEx) {
            // Token couldn't be decoded (not valid Base64 or malformed). Map to a clear client error.
            String msg = "Could not decode the token. The input is not a valid Base64-encoded JWT.";
            request.setAttribute("invalidToken", decodeEx.getMessage());
            throw new org.springframework.security.authentication.BadCredentialsException(msg);
        } catch (JWTVerificationException verificationEx) {
            // Any other verification issues (signature invalid, claim checks) - return a clear message
            String msg = "Invalid token. " + verificationEx.getMessage();
            request.setAttribute("invalidToken", verificationEx.getMessage());
            throw new com.bob.angularspringbootfullstack.exception.ApiException(msg);
        }
    }
}